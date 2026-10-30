package com.autocraft;

import com.autocraft.command.Commands;
import com.autocraft.craft.Planner;
import com.autocraft.craft.Stations;
import com.autocraft.goal.Requirement;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * Dev only (-Dautocraft.harness=true, see the runHarness task): runs lines from harness-in.txt in the game folder
 * ("/cmd" as a server command, "!ac ..." as a bot command, "dump", "plan [item]", "explain <item> [amount]",
 * "block x y z [radius]", "wait <ticks>", "panel" / "close" for the control panel, "widgets" / "click <label|x y>" to
 * list and press the open screen's buttons, "respawn" after a death, "shot [name]" for a screenshot in screenshots/,
 * "record [name]" / "record stop" for a video in recordings/ ({@link DevRecorder}), "camera first|back|front" for
 * the view) and logs the bot's state every 5 s.
 */
public final class DevHarness {
	private static final Path IN = Path.of("harness-in.txt");
	private static final java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
	private static int ticks;
	private static int waitUntil;
	private static String lastStatus = "";
	private static float lastHealth = -1;

	private DevHarness() {}

	/**
	 * Load every mod class now: rebuilding while a dev client runs rewrites the class files, and a class first needed
	 * mid-rebuild (the pathfinder on the first long walk) wasn't there and crashed the game.
	 */
	public static void preload() {
		try {
			Path root = Path.of(DevHarness.class.getProtectionDomain().getCodeSource().getLocation().toURI());
			if (!Files.isDirectory(root)) return; // a jar: nothing gets rewritten
			try (var files = Files.walk(root.resolve("com/autocraft"))) {
				List<String> names = files.filter(f -> f.toString().endsWith(".class")).map(f -> root.relativize(f).toString())
						.map(n -> n.substring(0, n.length() - ".class".length()).replace('\\', '.').replace('/', '.')).toList();
				for (String name : names) {
					if (name.contains("mixin")) continue; // mixin classes must not be loaded directly
					try {
						Class.forName(name, false, DevHarness.class.getClassLoader());
					} catch (Throwable t) {
						Chat.LOG.debug("[H] preload {}: {}", name, t.toString());
					}
				}
				Chat.LOG.info("[H] preloaded {} classes", names.size());
			}
		} catch (Exception e) {
			Chat.LOG.warn("[H] preload failed", e);
		}
	}

	public static void tick(Minecraft mc) {
		ticks++;
		DevRecorder.tick(mc);
		if (ticks % 10 == 0 && mc.player != null && Files.exists(IN)) {
			try {
				List<String> lines = Files.readAllLines(IN);
				Files.delete(IN);
				for (String line : lines) queue.add(line.trim());
			} catch (Exception e) {
				Chat.LOG.error("[H] input failed", e);
			}
		}
		while (mc.player != null && ticks >= waitUntil && !queue.isEmpty()) {
			String line = queue.poll();
			if (line.startsWith("wait ")) waitUntil = ticks + Integer.parseInt(line.substring(5).trim()); // ticks
			else run(mc, line);
		}
		if (ticks % 100 == 0 && mc.player != null) dump(mc);
		// every change of what the bot is doing, with health: a fight can be followed tick by tick
		Bot bot = Bot.get();
		if (mc.player != null && bot.isRunning()) {
			String now = bot.activeBehavior() + ": " + bot.status();
			if (!now.equals(lastStatus)) {
				lastStatus = now;
				Chat.LOG.info("[S] {} hp={} {}", ticks, (int) mc.player.getHealth(), now);
			}
		}
		// every hit taken, with the mobs near and where they stand relative to us: what reached us, from where
		if (mc.player != null && bot.isRunning()) {
			float hp = mc.player.getHealth();
			if (lastHealth >= 0 && hp < lastHealth - 0.01F) {
				LocalPlayer p = mc.player;
				StringBuilder near = new StringBuilder();
				for (net.minecraft.world.entity.Mob m : mc.level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, p.getBoundingBox().inflate(6))) {
					near.append(String.format(" %s@%.1f,%.1f,%.1f", m.getType().toShortString(), m.getX() - p.getX(), m.getY() - p.getY(), m.getZ() - p.getZ()));
				}
				Chat.LOG.info(String.format("[D] %d hp %.1f->%.1f at %.2f,%.2f,%.2f ground=%s src=%s near:%s", ticks, lastHealth, hp, p.getX(), p.getY(), p.getZ(),
						p.onGround(), p.getLastDamageSource() == null ? "?" : p.getLastDamageSource().getMsgId(), near));
			}
			lastHealth = hp;
		}
	}

	private static void run(Minecraft mc, String line) {
		if (line.isEmpty()) return;
		Chat.LOG.info("[H] > " + line);
		if (line.equals("dump")) {
			dump(mc);
		} else if (line.equals("quit")) {
			DevRecorder.stop(); // finish the video first: a cut-off MP4 has no index and won't play
			mc.stop(); // saves the world on the way out
		} else if (line.startsWith("record")) {
			String name = line.substring(6).trim();
			if (name.equals("stop")) DevRecorder.stop();
			else DevRecorder.start(mc, name.isEmpty() ? "harness" : name);
		} else if (line.startsWith("camera ")) {
			mc.options.setCameraType(switch (line.substring(7).trim()) {
				case "back" -> net.minecraft.client.CameraType.THIRD_PERSON_BACK;
				case "front" -> net.minecraft.client.CameraType.THIRD_PERSON_FRONT;
				default -> net.minecraft.client.CameraType.FIRST_PERSON;
			});
		} else if (line.startsWith("plan")) {
			plan(line.substring(4).trim());
		} else if (line.startsWith("explain ")) {
			String[] a = line.substring(8).trim().split("\\s+");
			Requirement r = com.autocraft.goal.Resources.forJob(a[0], a.length > 1 ? Integer.parseInt(a[1]) : 1);
			if (r != null) printPlan(Stations.planner(Bot.get(), s -> false).explain(r), 0);
		} else if (line.startsWith("block ")) {
			String[] a = line.substring(6).trim().split("\\s+");
			net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2]));
			int r = a.length > 3 ? Integer.parseInt(a[3]) : 0;
			for (net.minecraft.core.BlockPos b : net.minecraft.core.BlockPos.betweenClosed(pos.offset(-r, -r, -r), pos.offset(r, r, r))) {
				if (r == 0 || !mc.level.getBlockState(b).isAir()) Chat.LOG.info("[H] block " + b.toShortString() + " = " + mc.level.getBlockState(b));
			}
		} else if (line.startsWith("/")) {
			IntegratedServer server = mc.getSingleplayerServer();
			if (server == null) return;
			String cmd = line.substring(1);
			server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd));
		} else if (line.startsWith("!ac")) {
			Commands.handle(line);
		} else if (line.equals("panel")) {
			mc.gui.setScreen(new com.autocraft.ui.AutoCraftScreen());
		} else if (line.equals("close")) {
			mc.gui.setScreen(null);
		} else if (line.equals("respawn")) {
			if (mc.player != null && mc.player.isDeadOrDying()) {
				mc.player.respawn(); // as the death screen's button does (the bot does the same when it has work)
				mc.gui.setScreen(null);
			}
		} else if (line.equals("widgets")) {
			if (mc.gui.screen() == null) return;
			for (var w : mc.gui.screen().children()) {
				if (w instanceof net.minecraft.client.gui.components.AbstractWidget a && a.visible) {
					Chat.LOG.info("[H] widget '" + a.getMessage().getString() + "' at " + a.getX() + "," + a.getY() + " " + a.getWidth() + "x"
							+ a.getHeight() + (a.active ? "" : " (inactive)"));
				}
			}
		} else if (line.startsWith("click ")) {
			click(mc, line.substring(6).trim());
		} else if (line.startsWith("biomemap")) {
			String[] a = line.substring(8).trim().split("\\s+");
			biomeMap(mc, a.length > 0 && !a[0].isEmpty() ? Integer.parseInt(a[0]) : 2048, a.length > 1 ? Integer.parseInt(a[1]) : 64);
		} else if (line.startsWith("shot")) {
			String name = line.length() > 4 ? line.substring(4).trim() : "harness";
			net.minecraft.client.Screenshot.grab(mc.gameDirectory, name + ".png", mc.gameRenderer.mainRenderTarget(), 1,
					c -> Chat.LOG.info("[H] " + c.getString()));
		}
	}

	/** "click x y" at GUI coordinates, or "click <label>": the visible, active widget whose label contains it. */
	private static void click(Minecraft mc, String what) {
		var screen = mc.gui.screen();
		if (screen == null) return;
		double x = -1, y = -1;
		String[] xy = what.split("\\s+");
		if (xy.length == 2 && xy[0].matches("\\d+") && xy[1].matches("\\d+")) {
			x = Integer.parseInt(xy[0]);
			y = Integer.parseInt(xy[1]);
		} else {
			for (var w : screen.children()) {
				if (w instanceof net.minecraft.client.gui.components.AbstractWidget a && a.visible && a.active
						&& a.getMessage().getString().toLowerCase().contains(what.toLowerCase())) {
					x = a.getX() + a.getWidth() / 2.0;
					y = a.getY() + a.getHeight() / 2.0;
					break;
				}
			}
		}
		if (x < 0) {
			Chat.LOG.info("[H] click: no widget '" + what + "'");
			return;
		}
		var event = new net.minecraft.client.input.MouseButtonEvent(x, y,
				new net.minecraft.client.input.MouseButtonInfo(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT, 0));
		screen.mouseMoved(x, y);
		var under = screen.getChildAt(x, y);
		String who = under.map(c -> c.getClass().getSimpleName()
				+ (c instanceof net.minecraft.client.gui.components.AbstractWidget a ? " '" + a.getMessage().getString() + "'" : "")).orElse("nothing");
		boolean taken = screen.mouseClicked(event, false);
		screen.mouseReleased(event);
		Chat.LOG.info("[H] clicked '" + what + "' at " + (int) x + "," + (int) y + " on " + who + (taken ? "" : " (not taken)"));
	}

	/**
	 * "biomemap [radius] [step]": the overworld's surface biomes around the player as a character map in the log, north
	 * up, read from the integrated server's generator (ground truth for judging exploration; the bot never sees this).
	 */
	private static void biomeMap(Minecraft mc, int radius, int step) {
		IntegratedServer server = mc.getSingleplayerServer();
		if (server == null || mc.player == null) return;
		net.minecraft.server.level.ServerLevel level = server.overworld();
		var source = level.getChunkSource();
		var generator = source.getGenerator();
		var random = source.randomState();
		int px = mc.player.getBlockX(), pz = mc.player.getBlockZ();
		Map<String, Character> legend = new TreeMap<>();
		for (int z = pz - radius; z <= pz + radius; z += step) {
			StringBuilder row = new StringBuilder();
			for (int x = px - radius; x <= px + radius; x += step) {
				if (Math.abs(x - px) < step / 2 + 1 && Math.abs(z - pz) < step / 2 + 1) {
					row.append('@');
					continue;
				}
				int y = generator.getBaseHeight(x, z, net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, level, random);
				String name = level.getUncachedNoiseBiome(net.minecraft.core.QuartPos.fromBlock(x), net.minecraft.core.QuartPos.fromBlock(y),
						net.minecraft.core.QuartPos.fromBlock(z)).unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
				char c = biomeChar(name);
				legend.putIfAbsent(name, c);
				row.append(c);
			}
			Chat.LOG.info("[H] map " + row);
		}
		Chat.LOG.info("[H] map legend " + legend);
	}

	private static char biomeChar(String name) {
		return switch (name) {
			case "snowy_slopes" -> 'S';
			case "grove" -> 'G';
			case "frozen_peaks", "jagged_peaks" -> 'P';
			case "stony_peaks" -> 'p';
			case "snowy_plains", "ice_spikes", "snowy_taiga", "snowy_beach", "frozen_river" -> '*';
			case "taiga", "old_growth_pine_taiga", "old_growth_spruce_taiga" -> 't';
			case "meadow", "cherry_grove" -> 'm';
			case "windswept_hills", "windswept_forest", "windswept_gravelly_hills" -> 'w';
			case "forest", "flower_forest", "birch_forest", "old_growth_birch_forest", "dark_forest", "pale_garden" -> 'f';
			case "plains", "sunflower_plains" -> '.';
			case "river" -> '-';
			case "frozen_ocean", "deep_frozen_ocean" -> '=';
			case "desert" -> 'd';
			case "badlands", "wooded_badlands", "eroded_badlands" -> 'b';
			case "savanna", "savanna_plateau", "windswept_savanna" -> 'v';
			case "jungle", "sparse_jungle", "bamboo_jungle" -> 'j';
			case "swamp", "mangrove_swamp" -> 's';
			case "beach" -> ',';
			case "stony_shore" -> ':';
			case "mushroom_fields" -> 'u';
			default -> name.contains("ocean") ? '~' : '?';
		};
	}

	/** "plan": the gear planner's tool step for the current target; "plan <item>": its next step toward one, tool first. */
	private static void plan(String item) {
		Bot bot = Bot.get();
		Planner.trace = m -> Chat.LOG.info("[P] " + m);
		try {
			Planner pl = Stations.planner(bot).borrowingJobLoot();
			if (item.isEmpty()) {
				Requirement target = bot.targetForGear();
				Chat.LOG.info("[P] target " + target);
				if (target != null) Chat.LOG.info("[P] = " + pl.toolFirst(new Planner.Gather(target)));
			} else {
				Planner.Step s = pl.next(BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(item)), 1);
				Chat.LOG.info("[P] next = " + s);
				if (s instanceof Planner.Gather g) Chat.LOG.info("[P] toolFirst = " + pl.toolFirst(g));
			}
		} finally {
			Planner.trace = null;
		}
	}

	private static void printPlan(Planner.PlanNode n, int depth) {
		Chat.LOG.info("[E] " + "  ".repeat(depth) + n.name() + " " + n.have() + "/" + n.amount() + " — " + n.how());
		for (Planner.PlanNode part : n.parts()) printPlan(part, depth + 1);
	}

	private static void dump(Minecraft mc) {
		LocalPlayer p = mc.player;
		Bot bot = Bot.get();
		Map<String, Integer> inv = new TreeMap<>();
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (!s.isEmpty()) inv.merge(BuiltInRegistries.ITEM.getKey(s.getItem()).getPath(), s.getCount(), Integer::sum);
		}
		Chat.LOG.info("[H] t=" + ticks / 20 + "s pos=" + p.blockPosition().toShortString() + " hp=" + (int) p.getHealth() + " food=" + p.getFoodData().getFoodLevel()
				+ (p.isUsingItem() ? " using=" + p.getUseItem().getItem() + "/" + p.getUseItemRemainingTicks() : "") + (p.isShiftKeyDown() ? " sneak" : "")
				+ " run=" + bot.isRunning() + " beh=" + bot.activeBehavior() + " status=" + bot.status() + " need=" + bot.activeNeed() + " target="
				+ bot.currentTarget() + " gear=" + bot.gearNote() + " inv=" + inv);
	}
}
