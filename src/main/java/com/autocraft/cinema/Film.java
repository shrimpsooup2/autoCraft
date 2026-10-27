package com.autocraft.cinema;

import com.autocraft.Chat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The director: `!ac film` plays the explainer as a film in this singleplayer world, with whatever shader pack is
 * on. It builds every shot's set near you (all within render distance, so cuts are instant; a shot set somewhere
 * far, like a real ancient city, is travelled to while the screen is black), then plays the shots back to back —
 * puppets, camera moves, floating graphics, titles — with a dip to black between shots. The camera goes round
 * blocks ({@link Rig}); the puppets don't walk through them ({@link Actor}). If anything in the film breaks, it
 * says what and where in chat, logs it, and stops — it never takes the game down.
 */
public final class Film {
	public record CameraPose(double x, double y, double z, float yaw, float pitch, float fov) {}

	private enum Phase { IDLE, LOADING, PLAYING }

	/** The dip to black at each cut, out and back in (short: the cuts fall on the beat). */
	static final double FADE = 0.3;
	private static final int LOAD_MIN_TICKS = 60;
	private static final int LOAD_MAX_TICKS = 20 * 40;
	/** A shot whose set is further than this from the hub is travelled to (the hub's chunks wouldn't reach it). */
	private static final double FAR = 120;

	private static Phase phase = Phase.IDLE;
	private static final List<Shot> shots = new ArrayList<>();
	private static int index;
	private static @Nullable Shot current;
	/** Client ticks of film shown (only while a shot plays: travelling doesn't count), and while loading. */
	private static int ticks;
	private static int loadTicks;
	/** Where the current shot starts on the film's clock (seconds): the cuts stay on the music, never drift. */
	private static double shotStart;
	private static int chapter;
	private static BlockPos hub = BlockPos.ZERO;
	private static @Nullable Vec3 restorePos;
	private static @Nullable GameType restoreMode;
	private static @Nullable Boolean restorePauseOnLostFocus;
	private static final Rig rig = new Rig();
	/** Something broke mid-frame: stop at the next tick (a safe point). */
	private static boolean failed;
	/** The sets being built on the server (null once done). */
	private static @Nullable CompletableFuture<Void> building;

	private Film() {}

	public static boolean active() {
		return phase != Phase.IDLE;
	}

	/** A shot is on screen (not idle, not loading). */
	static boolean playing() {
		return phase == Phase.PLAYING && !failed;
	}

	/** All shots, in order. */
	static List<Shot> all() {
		return List.of(new OpenShot(), new PickShot(), new PlanShot(), new FindShot(), new SurviveShot(), new NetherShot(), new LootShot(),
				new TeamShot(), new EndShot());
	}

	// ---- command -------------------------------------------------------------------------------------------------

	public static void command(String[] args) {
		String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
		switch (sub) {
			case "play" -> play(args, 0);
			case "record" -> record(args);
			case "stop" -> stop("Stopped.");
			case "list" -> {
				int i = 1;
				for (Shot s : all()) Chat.info(i++ + ". " + s.name + " (" + Math.round(s.duration) + " s)");
			}
			default -> Chat.info("!ac film play [all | 1-9 | name...]  — builds the sets here and plays the explainer (record your screen), or "
					+ "!ac film record [60fps] [shots...] to have it saved as a video by itself (exact frame rate; an MP4 with ffmpeg installed, "
					+ "else PNG frames). !ac film list, !ac film stop. Use a throwaway creative world, stand somewhere open, render distance 16+.");
		}
	}

	/** {@code !ac film record [24fps|30fps|60fps] [shots...]}: play the film and save it frame by frame. */
	private static void record(String[] args) {
		int fps = 30;
		List<String> rest = new ArrayList<>();
		for (String a : args) {
			String l = a.toLowerCase(Locale.ROOT);
			if (l.endsWith("fps")) {
				try {
					fps = Math.max(10, Math.min(120, Integer.parseInt(l.substring(0, l.length() - 3))));
				} catch (NumberFormatException e) {
					Chat.warn("Not a frame rate: " + a + " (try 30fps or 60fps).");
					return;
				}
			} else {
				rest.add(a);
			}
		}
		play(rest.toArray(String[]::new), fps);
	}

	private static void play(String[] args, int recordFps) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || Stage.server() == null) {
			Chat.info("Films play in a singleplayer world.");
			return;
		}
		if (active()) stop(null);
		com.autocraft.Bot.get().stop("film");   // it would steer you during the takes
		List<Shot> chosen = pick(args);
		if (chosen.isEmpty()) {
			Chat.info("No such shot. !ac film list");
			return;
		}
		if (mc.options.getEffectiveRenderDistance() < 12) Chat.warn("Render distance is under 12 chunks: some sets may be out of view.");
		restorePos = player.position();
		restoreMode = mc.gameMode != null ? mc.gameMode.getPlayerMode() : GameType.CREATIVE;
		String name = player.getGameProfile().name();
		hub = player.blockPosition();
		Chat.info("Building " + chosen.size() + " set(s)… (finding and loading a real ancient city can take a minute)");
		Stage.command("gamemode spectator " + name);
		Stage.command("difficulty peaceful");
		for (String rule : List.of("advance_time false", "advance_weather false", "spawn_mobs false", "random_tick_speed 0",
				"show_death_messages false", "send_command_feedback false")) {
			Stage.command("gamerule " + rule);
		}
		Stage.command("weather clear");
		restorePauseOnLostFocus = mc.options.pauseOnLostFocus;
		mc.options.pauseOnLostFocus = false;   // clicking away mustn't pause the take
		// the sets are built on the server while the screen shows "Building the sets…" (the window stays alive)
		MinecraftServer server = Stage.server();
		BlockPos h = hub;
		building = server.submit(() -> {
			for (Shot s : chosen) s.build(server.overworld(), h.offset(s.offset()));
		});
		shots.clear();
		shots.addAll(chosen);
		index = 0;
		chapter = 0;
		ticks = 0;
		loadTicks = 0;
		shotStart = 0;
		failed = false;
		phase = Phase.LOADING;
		if (recordFps > 0) Recorder.arm(recordFps, musicStart(chosen));
	}

	/** Where in the song a recording of these shots starts, or -1 when they aren't one unbroken stretch of the film. */
	private static double musicStart(List<Shot> chosen) {
		for (int i = 1; i < chosen.size(); i++) {
			if (chosen.get(i).slot != chosen.get(i - 1).slot + 1) return -1;
		}
		return Score.start(chosen.getFirst().slot);
	}

	private static List<Shot> pick(String[] args) {
		List<Shot> all = all();
		if (args.length <= 2 || args[2].equalsIgnoreCase("all")) return all;
		List<Shot> out = new ArrayList<>();
		for (int i = 2; i < args.length; i++) {
			String a = args[i].toLowerCase(Locale.ROOT);
			for (int k = 0; k < all.size(); k++) {
				if (a.equals(String.valueOf(k + 1)) || all.get(k).name.toLowerCase(Locale.ROOT).startsWith(a)) out.add(all.get(k));
			}
		}
		return out;
	}

	private static void stop(@Nullable String message) {
		failed = false;
		building = null;   // a build still running just finishes; nothing waits for it
		Shot s = current;
		current = null;
		phase = Phase.IDLE;
		try {
			if (s != null) s.end();
		} catch (RuntimeException e) {
			report("cleaning up", e);
		}
		shots.clear();
		Recorder.finish();
		restore();
		if (message != null) Chat.info(message);
	}

	private static void restore() {
		Minecraft mc = Minecraft.getInstance();
		if (restorePauseOnLostFocus != null) mc.options.pauseOnLostFocus = restorePauseOnLostFocus;
		restorePauseOnLostFocus = null;
		if (mc.player == null) return;
		String name = mc.player.getGameProfile().name();
		if (restoreMode != null) Stage.command("gamemode " + restoreMode.getName() + " " + name);
		if (restorePos != null) Stage.command("tp " + name + " " + restorePos.x + " " + restorePos.y + " " + restorePos.z);
		restorePos = null;
		restoreMode = null;
	}

	/** Something in the film broke outside a tick (camera, drawing, a puppet): report it, stop at the next tick. */
	static void fail(String where, Throwable e) {
		if (failed || phase == Phase.IDLE) return;
		failed = true;
		report(where, e);
	}

	/** In chat: what broke and the first line of ours it went through; the whole trace goes to the game log. */
	private static void report(String where, Throwable e) {
		Chat.LOG.error("Film stopped ({})", where, e);
		StackTraceElement at = null;
		for (StackTraceElement s : e.getStackTrace()) {
			if (s.getClassName().startsWith("com.autocraft")) {
				at = s;
				break;
			}
		}
		Chat.warn("Film stopped (" + where + "): " + e + (at != null ? " at " + at.getFileName() + ":" + at.getLineNumber() : "")
				+ ". The full error is in logs/latest.log.");
	}

	// ---- playback ------------------------------------------------------------------------------------------------

	/** End of every client tick. */
	public static void clientTick(Minecraft mc) {
		if (phase == Phase.IDLE) return;
		if (failed) {
			stop(null);
			return;
		}
		if (mc.level == null || mc.player == null) {   // left the world mid-film
			phase = Phase.IDLE;
			current = null;
			shots.clear();
			Recorder.finish();
			restore();
			return;
		}
		try {
			if (phase == Phase.LOADING) {
				CompletableFuture<Void> b = building;
				if (b != null) {   // still building: wait; then go up over the hub and let the chunks draw
					if (!b.isDone()) return;
					building = null;
					try {
						b.join();
					} catch (CompletionException | CancellationException e) {
						report("building the sets", e.getCause() != null ? e.getCause() : e);
						stop(null);
						return;
					}
					Stage.command("tp " + mc.player.getGameProfile().name() + " " + hub.getX() + " " + (hub.getY() + 24) + " " + hub.getZ());
					loadTicks = 0;
					return;
				}
				loadTicks++;
				if (loadTicks >= LOAD_MAX_TICKS || loadTicks >= LOAD_MIN_TICKS && mc.levelRenderer.hasRenderedAllSections()) beginShot(mc);
				return;
			}
			ticks++;
			Shot s = current;
			if (s == null) return;
			double t = ticks / 20.0 - shotStart;
			if (ticks % 20 == 0) Chat.LOG.info("Film: {} at {} s", s.name, String.format(Locale.ROOT, "%.1f", t));   // a trail in latest.log
			s.step(t);
			if (t >= s.duration) {
				shotStart += s.duration;
				s.end();
				current = null;
				index++;
				if (index >= shots.size()) {
					stop("That's a wrap.");
				} else {
					nextShot(mc);
				}
			}
		} catch (RuntimeException e) {
			report("tick", e);
			stop(null);
		}
	}

	/** Where the camera's player waits during a shot: over the hub, or at the shot itself when it's far away. */
	private static Vec3 home(Shot s) {
		Vec3 o = Vec3.atCenterOf(s.origin);
		boolean far = Math.hypot(o.x - hub.getX(), o.z - hub.getZ()) > FAR;
		return far ? o.add(0, 2, 0) : new Vec3(hub.getX() + 0.5, hub.getY() + 24, hub.getZ() + 0.5);
	}

	/** Between shots: go straight on, or travel to the next set and wait (black) for it to be drawn. */
	private static void nextShot(Minecraft mc) {
		Vec3 home = home(shots.get(index));
		if (mc.player != null && mc.player.position().distanceTo(home) > 32) {
			Stage.command("tp " + mc.player.getGameProfile().name() + " " + home.x + " " + home.y + " " + home.z);
			loadTicks = 0;
			phase = Phase.LOADING;
			return;
		}
		beginShot(mc);
	}

	private static void beginShot(Minecraft mc) {
		Shot s = shots.get(index);
		if (phase == Phase.LOADING && mc.player != null && mc.player.position().distanceTo(home(s)) > 32) {   // the first shot may be far too
			nextShot(mc);
			return;
		}
		Chat.LOG.info("Film: starting {} (shot {} of {})", s.name, index + 1, shots.size());
		Recorder.begin(mc);   // no-op unless recording; from the first shot on, the clock runs by frames
		Stage.command("time set " + s.dayTime);
		if (s.tag != null) chapter++;
		current = s;
		loadTicks = 0;
		rig.reset();
		phase = Phase.PLAYING;
		s.begin(mc.level);
	}

	private static double time(float partial) {
		return (ticks + partial) / 20.0 - shotStart;
	}

	public static @Nullable CameraPose camera(float partial) {
		Shot s = current;
		Minecraft mc = Minecraft.getInstance();
		if (phase != Phase.PLAYING || failed || s == null || s.cam.isEmpty() || mc.level == null) return null;
		try {
			double t = Math.min(s.duration, time(partial));
			Cam.Pose p = s.cam.at(t);
			Rig.View v = rig.place(mc.level, p.pos(), p.look(), t);
			Cam.Pose q = Cam.pose(v.pos(), v.look(), p.fov());
			return new CameraPose(v.pos().x, v.pos().y, v.pos().z, q.yaw(), q.pitch(), q.fov());
		} catch (RuntimeException e) {
			fail("camera", e);
			return null;
		}
	}

	/** Level render: the shot's floating graphics. */
	public static void collect(LevelRenderContext ctx) {
		Shot s = current;
		if (phase != Phase.PLAYING || failed || s == null) return;
		try {
			float partial = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
			Fx fx = new Fx(ctx, partial);
			double t = time(partial);
			s.lights(fx, t);
			s.draw(fx, t);
		} catch (RuntimeException e) {
			fail("graphics", e);
		}
	}

	/** HUD: the shot's overlays, the chapter tag, the dip to black between shots, or black while loading. */
	public static void hud(GuiGraphicsExtractor g, DeltaTracker delta) {
		if (phase == Phase.IDLE || failed) return;
		try {
			FilmHud hud = new FilmHud(g);
			if (phase == Phase.LOADING) {
				hud.fade(1);
				hud.text(Component.literal(building != null ? "Building the sets…" : "Setting up the shot…"), hud.w / 2.0, hud.h - 20, 1, 0x808080, 0.6, false);
				return;
			}
			Shot s = current;
			if (s == null) return;
			double t = time(delta.getGameTimeDeltaPartialTick(false));
			s.hud(hud, t);
			if (s.tag != null) hud.tag(chapter, s.tag, Anim.window(t, 0.9, 1.4, 4.2, 4.8));
			double black = Math.max(1 - Anim.ramp(t, 0, FADE), Anim.ramp(t, s.duration - FADE, s.duration));
			if (index == 0) black = Math.max(black, 1 - Anim.ramp(t, 0, 1.6));
			hud.fade(black);
		} catch (RuntimeException e) {
			fail("overlay", e);
		}
	}

	/** Hide the game's own HUD while a film plays (registered once at startup). */
	public static void hideVanillaHud() {
		for (Identifier id : List.of(VanillaHudElements.MISC_OVERLAYS, VanillaHudElements.CROSSHAIR, VanillaHudElements.SPECTATOR_MENU,
				VanillaHudElements.HOTBAR, VanillaHudElements.ARMOR_BAR, VanillaHudElements.HEALTH_BAR, VanillaHudElements.FOOD_BAR,
				VanillaHudElements.AIR_BAR, VanillaHudElements.MOUNT_HEALTH, VanillaHudElements.INFO_BAR, VanillaHudElements.EXPERIENCE_LEVEL,
				VanillaHudElements.HELD_ITEM_TOOLTIP, VanillaHudElements.SPECTATOR_TOOLTIP, VanillaHudElements.MOB_EFFECTS, VanillaHudElements.BOSS_BAR,
				VanillaHudElements.SCOREBOARD, VanillaHudElements.OVERLAY_MESSAGE, VanillaHudElements.TITLE_AND_SUBTITLE, VanillaHudElements.CHAT,
				VanillaHudElements.PLAYER_LIST, VanillaHudElements.SUBTITLES)) {
			HudElementRegistry.replaceElement(id, original -> (g, delta) -> {
				if (!active()) original.extractRenderState(g, delta);
			});
		}
	}
}
