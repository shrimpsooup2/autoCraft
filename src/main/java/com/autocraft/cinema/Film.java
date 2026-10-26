package com.autocraft.cinema;

import com.autocraft.Chat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The director: `!ac film` plays the explainer as a film in this singleplayer world, with whatever shader pack is
 * on. It builds every shot's set near you (all within render distance, so cuts are instant), then plays the shots
 * back to back — puppets, camera moves, floating graphics, titles — with a dip to black between shots. Record the
 * screen while it plays; what you get is the finished cut.
 */
public final class Film {
	public record CameraPose(double x, double y, double z, float yaw, float pitch, float fov) {}

	private enum Phase { IDLE, LOADING, PLAYING }

	static final double FADE = 0.8;
	private static final int LOAD_MIN_TICKS = 60;
	private static final int LOAD_MAX_TICKS = 20 * 40;

	private static Phase phase = Phase.IDLE;
	private static final List<Shot> shots = new ArrayList<>();
	private static int index;
	private static @Nullable Shot current;
	private static int tick;
	private static int chapter;
	private static @Nullable Vec3 restorePos;
	private static @Nullable GameType restoreMode;

	private Film() {}

	public static boolean active() {
		return phase != Phase.IDLE;
	}

	/** All shots, in order. */
	static List<Shot> all() {
		return List.of(new OpenShot(), new PickShot(), new PlanShot(), new FindShot(), new SurviveShot(), new LootShot(), new TeamShot(),
				new EndShot());
	}

	// ---- command -------------------------------------------------------------------------------------------------

	public static void command(String[] args) {
		String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
		switch (sub) {
			case "play" -> play(args);
			case "stop" -> stop("Stopped.");
			case "list" -> {
				int i = 1;
				for (Shot s : all()) Chat.info(i++ + ". " + s.name + " (" + Math.round(s.duration) + " s)");
			}
			default -> Chat.info("!ac film play [all | 1-8 | name...]  — builds the sets here and plays the explainer (record your screen). "
					+ "!ac film list, !ac film stop. Use a fresh creative world, stand somewhere open and flat-ish, render distance 16+.");
		}
	}

	private static void play(String[] args) {
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
		BlockPos hub = player.blockPosition();
		Chat.info("Building " + chosen.size() + " set(s)…");
		Stage.command("gamemode spectator " + name);
		Stage.command("difficulty peaceful");
		for (String rule : List.of("advance_time false", "advance_weather false", "spawn_mobs false", "random_tick_speed 0",
				"show_death_messages false", "send_command_feedback false")) {
			Stage.command("gamerule " + rule);
		}
		Stage.command("weather clear");
		try {
			for (Shot s : chosen) {
				BlockPos at = hub.offset(s.offset());
				Stage.sync(level -> s.build(level, at));
			}
		} catch (RuntimeException e) {
			Chat.warn("Building the sets failed: " + e);
			restore();
			return;
		}
		Stage.command("tp " + name + " " + hub.getX() + " " + (hub.getY() + 24) + " " + hub.getZ());
		shots.clear();
		shots.addAll(chosen);
		index = 0;
		chapter = 0;
		tick = 0;
		phase = Phase.LOADING;
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
		if (current != null) current.end();
		current = null;
		shots.clear();
		phase = Phase.IDLE;
		restore();
		if (message != null) Chat.info(message);
	}

	private static void restore() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		String name = mc.player.getGameProfile().name();
		if (restoreMode != null) Stage.command("gamemode " + restoreMode.getName() + " " + name);
		if (restorePos != null) Stage.command("tp " + name + " " + restorePos.x + " " + restorePos.y + " " + restorePos.z);
		restorePos = null;
		restoreMode = null;
	}

	// ---- playback ------------------------------------------------------------------------------------------------

	/** End of every client tick. */
	public static void clientTick(Minecraft mc) {
		if (phase == Phase.IDLE) return;
		if (mc.level == null || mc.player == null) {
			phase = Phase.IDLE;
			current = null;
			return;
		}
		tick++;
		try {
			if (phase == Phase.LOADING) {
				if (tick >= LOAD_MAX_TICKS || tick >= LOAD_MIN_TICKS && mc.levelRenderer.hasRenderedAllSections()) startShot(mc);
				return;
			}
			Shot s = current;
			if (s == null) return;
			double t = tick / 20.0;
			s.step(t);
			if (t >= s.duration) {
				s.end();
				index++;
				if (index >= shots.size()) {
					stop("That's a wrap.");
				} else {
					startShot(mc);
				}
			}
		} catch (RuntimeException e) {
			Chat.warn("Film stopped: " + e);
			stop(null);
		}
	}

	private static void startShot(Minecraft mc) {
		Shot s = shots.get(index);
		Stage.command("time set " + s.dayTime);
		if (s.tag != null) chapter++;
		current = s;
		tick = 0;
		phase = Phase.PLAYING;
		s.begin(mc.level);
	}

	private static double time(float partial) {
		return (tick + partial) / 20.0;
	}

	public static @Nullable CameraPose camera(float partial) {
		Shot s = current;
		if (phase != Phase.PLAYING || s == null || s.cam.isEmpty()) return null;
		Cam.Pose p = s.cam.at(Math.min(s.duration, time(partial)));
		return new CameraPose(p.pos().x, p.pos().y, p.pos().z, p.yaw(), p.pitch(), p.fov());
	}

	/** Level render: the shot's floating graphics. */
	public static void collect(LevelRenderContext ctx) {
		Shot s = current;
		if (phase != Phase.PLAYING || s == null) return;
		float partial = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
		try {
			Fx fx = new Fx(ctx, partial);
			s.lights(fx);
			s.draw(fx, time(partial));
		} catch (RuntimeException e) {
			Chat.warn("Film graphics failed: " + e);
			stop(null);
		}
	}

	/** HUD: the shot's overlays, the chapter tag, the dip to black between shots, or black while loading. */
	public static void hud(GuiGraphicsExtractor g, DeltaTracker delta) {
		if (phase == Phase.IDLE) return;
		FilmHud hud = new FilmHud(g);
		if (phase == Phase.LOADING) {
			hud.fade(1);
			hud.text(Component.literal("Setting up the shot…"), hud.w / 2.0, hud.h - 20, 1, 0x808080, 0.6, false);
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
