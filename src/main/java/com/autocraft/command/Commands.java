package com.autocraft.command;

import com.autocraft.Bot;
import com.autocraft.Chat;
import com.autocraft.Settings;
import com.autocraft.behavior.FarmBehavior;
import com.autocraft.build.BuildOrder;
import com.autocraft.build.MaterialAdvisor;
import com.autocraft.build.MaterialSettings;
import com.autocraft.build.StructureRegistry;
import com.autocraft.goal.Job;
import com.autocraft.goal.Requirement;
import com.autocraft.goal.Resources;
import com.autocraft.path.Goal;
import com.autocraft.path.Navigator;
import com.autocraft.schematic.Schematic;
import com.autocraft.schematic.SchematicLibrary;
import com.autocraft.team.Team;
import com.autocraft.ui.Placements;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import org.jspecify.annotations.Nullable;

/** {@code !ac ...} chat commands. Handled lines are swallowed and never reach the server. */
public final class Commands {
	public static final String PREFIX = "!ac";

	private Commands() {}

	/** Returns true if the message was an autoCraft command (and therefore must not be sent). */
	public static boolean handle(String message) {
		String trimmed = message.trim();
		if (!trimmed.equals(PREFIX) && !trimmed.startsWith(PREFIX + " ")) return false;
		String[] args = trimmed.substring(PREFIX.length()).trim().split("\\s+");
		try {
			run(args[0].isEmpty() ? "help" : args[0].toLowerCase(), args);
		} catch (RuntimeException e) {
			Chat.error("Command failed: " + e.getMessage());
			Chat.LOG.error("Command failed", e);
		}
		return true;
	}

	private static void run(String cmd, String[] args) {
		Bot bot = Bot.get();
		LocalPlayer player = bot.player();
		switch (cmd) {
			case "collect" -> collect(bot, args);
			case "stop" -> bot.stop("by command");
			case "pause" -> {
				bot.setPaused(true);
				Chat.info("Paused.");
			}
			case "resume" -> {
				bot.setPaused(false);
				Chat.info("Resumed.");
			}
			case "status" -> status(bot);
			case "base" -> {
				BlockPos pos = player.blockPosition();
				bot.setBase(pos);
				Chat.info("Base set to " + pos.toShortString() + ". Chests within 8 blocks will be used for storage.");
			}
			case "logoff" -> {
				boolean on = args.length < 2 || !args[1].equalsIgnoreCase("off");
				Settings.get().logoffWhenDone = on;
				Settings.get().save();
				Chat.info("Log off when done: " + (on ? "on" : "off"));
			}
			case "goto" -> {
				if (args.length < 4) throw new IllegalArgumentException("usage: !ac goto <x> <y> <z>");
				bot.startGoto(new Goal.Block(position(player, args, 1)));
			}
			case "resources" -> Chat.info("Named: " + Resources.describe() + ". Any natural block's item id works, and any craftable or smeltable item"
					+ " (torch, glass, piston, iron_ingot...).");
			case "build" -> build(bot, args);
			case "place" -> place(args);
			case "schematics" -> schematics();
			case "farm" -> farm(bot, args);
			case "built", "farms" -> built();
			case "team" -> team(args);
			default -> help();
		}
	}

	/** {@code !ac team host [port] | join <host[:port]> <code> | leave} or just {@code !ac team} for the roster. */
	private static void team(String[] args) {
		String sub = args.length > 1 ? args[1].toLowerCase() : "";
		switch (sub) {
			case "host" -> Team.host(args.length > 2 ? Integer.parseInt(args[2]) : Team.DEFAULT_PORT);
			case "join" -> {
				if (args.length < 4) throw new IllegalArgumentException("usage: !ac team join <host[:port]> <code>");
				String address = args[2];
				int colon = address.lastIndexOf(':');
				String host = colon > 0 ? address.substring(0, colon) : address;
				int port = colon > 0 ? Integer.parseInt(address.substring(colon + 1)) : Team.DEFAULT_PORT;
				Team.join(host, port, args[3]);
			}
			case "leave" -> Team.leave();
			default -> {
				if (!Team.isActive()) {
					Chat.info("Not in a team. !ac team host  (then others: !ac team join <address> <code>)");
					return;
				}
				if (Team.isLeader()) Chat.info("Hosting at " + Team.shareAddress() + ", code " + Team.code() + ".");
				for (Team.MemberView m : Team.roster()) {
					Chat.info(m.name() + (m.role().isEmpty() ? "" : " [" + m.role() + "]") + ": " + m.activity());
				}
			}
		}
	}

	private static void collect(Bot bot, String[] args) {
		if (args.length < 3 || args.length % 2 == 0) {
			throw new IllegalArgumentException("usage: !ac collect <resource> <amount> [<resource> <amount> ...]");
		}
		List<Requirement> reqs = new ArrayList<>();
		for (int i = 1; i < args.length; i += 2) {
			int amount = Integer.parseInt(args[i + 1]);
			if (amount <= 0) throw new IllegalArgumentException("amount must be positive: " + args[i + 1]);
			Requirement r = Resources.forJob(args[i], amount);
			if (r == null) throw new IllegalArgumentException("can't mine or craft '" + args[i] + "' (try !ac resources)");
			reqs.add(r);
		}
		bot.start(new Job(reqs));
	}

	/** {@code !ac build <name> <x> <y> <z> [rotation]} or {@code !ac build <name> here [rotation]}. */
	private static void build(Bot bot, String[] args) {
		if (args.length < 3) {
			throw new IllegalArgumentException("usage: !ac build <schematic> <x> <y> <z> [0|90|180|270]  or  !ac build <schematic> here [rotation]");
		}
		Schematic s = SchematicLibrary.get(args[1]);
		if (s == null) throw new IllegalArgumentException("no schematic '" + args[1] + "' (see !ac schematics)");
		boolean here = args[2].equalsIgnoreCase("here");
		if (!here && args.length < 5) throw new IllegalArgumentException("give x y z (~ for relative) or 'here'");
		BlockPos origin = here ? Navigator.feet(bot.player()) : position(bot.player(), args, 2);
		Rotation rotation = rotation(args.length > (here ? 3 : 5) ? args[here ? 3 : 5] : "0");
		Chat.info(String.format("%s is %dx%dx%d (x/y/z), %d blocks; origin = its corner at %s.", s.name(), s.size().getX(), s.size().getY(),
				s.size().getZ(), s.nonAirCount(), origin.toShortString()));
		bot.startBuild(new BuildOrder(s, origin, rotation, Mirror.NONE, MaterialAdvisor.resolve(MaterialSettings.defaults(), s),
				BuildOrder.Purpose.USER));
	}

	/** {@code !ac place <name>}: show it as a ghost at the crosshair and position it with the placement keys. */
	private static void place(String[] args) {
		if (args.length < 2) throw new IllegalArgumentException("usage: !ac place <schematic>");
		Schematic s = SchematicLibrary.get(args[1]);
		if (s == null) throw new IllegalArgumentException("no schematic '" + args[1] + "' (see !ac schematics)");
		Placements.start(s);
	}

	/** {@code !ac farm <resource> [x y z | here] [rotation]}: build a farm for a resource (site picked if no position). */
	private static void farm(Bot bot, String[] args) {
		if (args.length < 2) throw new IllegalArgumentException("usage: !ac farm <resource> [<x> <y> <z> | here] [rotation]");
		String resource = args[1].toLowerCase().replace("minecraft:", "");
		BlockPos origin = null;
		int next = 2;
		if (args.length > 2 && args[2].equalsIgnoreCase("here")) {
			origin = Navigator.feet(bot.player());
			next = 3;
		} else if (args.length > 4) {
			origin = position(bot.player(), args, 2);
			next = 5;
		}
		Rotation rotation = rotation(args.length > next ? args[next] : "0");
		if (SchematicLibrary.farmsFor(resource).isEmpty()) {
			throw new IllegalArgumentException("no farm design for '" + resource + "' (built-in: cobblestone, sugar_cane, log, wheat; or add farm-"
					+ resource + "-<name>.litematic)");
		}
		if (!FarmBehavior.buildFarm(bot, resource, origin, rotation)) throw new IllegalArgumentException("no suitable spot nearby; give a position");
	}

	private static void schematics() {
		List<String> names = SchematicLibrary.names();
		Chat.info("Schematics (" + names.size() + "): " + String.join(", ", names));
		Chat.info("Add your own .litematic/.schem/.nbt files to " + SchematicLibrary.folder()
				+ " (name a farm farm-<resource>-<anything> so jobs can use it).");
	}

	private static void built() {
		List<StructureRegistry.Entry> all = StructureRegistry.all();
		if (all.isEmpty()) {
			Chat.info("Nothing built yet.");
			return;
		}
		for (StructureRegistry.Entry e : all) {
			Chat.info(e.schematic() + " at " + e.origin().toShortString() + " (" + e.dimension().name().toLowerCase() + ")"
					+ (e.provides() == null ? "" : ", farms " + e.provides()));
		}
	}

	/** Three coordinates starting at {@code from}; {@code ~} / {@code ~5} are relative to the player. */
	private static BlockPos position(LocalPlayer p, String[] args, int from) {
		BlockPos feet = Navigator.feet(p);
		return new BlockPos(coord(args[from], feet.getX()), coord(args[from + 1], feet.getY()), coord(args[from + 2], feet.getZ()));
	}

	private static int coord(String s, int relativeTo) {
		if (s.startsWith("~")) return relativeTo + (s.length() == 1 ? 0 : Integer.parseInt(s.substring(1)));
		return Integer.parseInt(s);
	}

	private static Rotation rotation(@Nullable String degrees) {
		return switch (degrees == null ? "0" : degrees) {
			case "0" -> Rotation.NONE;
			case "90" -> Rotation.CLOCKWISE_90;
			case "180" -> Rotation.CLOCKWISE_180;
			case "270", "-90" -> Rotation.COUNTERCLOCKWISE_90;
			default -> throw new IllegalArgumentException("rotation must be 0, 90, 180 or 270");
		};
	}

	private static void status(Bot bot) {
		LocalPlayer p = bot.player();
		if (!bot.isRunning()) {
			Chat.info("Idle.");
			return;
		}
		Job job = bot.job();
		Chat.info((bot.isPaused() ? "[paused] " : "") + bot.activeBehavior() + ": " + bot.status());
		if (job != null) Chat.info("Progress: " + job.progress(p));
		BuildOrder build = bot.buildOrder();
		if (build != null) {
			Chat.info("Building " + build.schematic().name() + " at " + build.origin().toShortString() + ": " + bot.buildPlaced() + "/"
					+ bot.buildTotal() + " blocks");
		}
		Requirement need = bot.activeNeed();
		if (need != null) Chat.info("Prerequisite: " + need);
		Chat.info(String.format("HP %.0f/20, food %d, pos %s", p.getHealth(), p.getFoodData().getFoodLevel(), p.blockPosition().toShortString()));
	}

	private static void help() {
		Chat.info("Press K for the control panel. Commands: !ac collect <item> <amount> ... (mined or crafted) | place <schematic> (ghost preview,"
				+ " then position it) | build <schematic> <x y z|here> [rot] | farm <resource> [x y z|here] | team host|join|leave | schematics"
				+ " | built | stop | pause | resume | status | base | logoff on|off | goto x y z | resources");
	}
}
