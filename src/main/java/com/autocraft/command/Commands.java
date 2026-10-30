package com.autocraft.command;

import com.autocraft.Bases;
import com.autocraft.Bot;
import com.autocraft.Chat;
import com.autocraft.Settings;
import com.autocraft.SpawnPoint;
import com.autocraft.behavior.FarmBehavior;
import com.autocraft.build.BuildOrder;
import com.autocraft.build.MaterialAdvisor;
import com.autocraft.build.MaterialSettings;
import com.autocraft.build.StructureRegistry;
import com.autocraft.goal.Destination;
import com.autocraft.goal.Ending;
import com.autocraft.goal.Job;
import com.autocraft.goal.Requirement;
import com.autocraft.goal.Requirement.Dim;
import com.autocraft.goal.Resources;
import com.autocraft.loot.LootSites;
import com.autocraft.path.Navigator;
import com.autocraft.schematic.Schematic;
import com.autocraft.schematic.SchematicLibrary;
import com.autocraft.tasks.TaskNode;
import com.autocraft.tasks.TaskTree;
import com.autocraft.team.Team;
import com.autocraft.ui.Placements;
import java.util.ArrayList;
import java.util.Arrays;
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
				if (args.length >= 2 && args[1].equalsIgnoreCase("stocked")) {
					boolean on = args.length < 3 || !args[2].equalsIgnoreCase("off");
					Settings.get().materialsAtBase = on;
					Settings.get().save();
					Chat.info(on ? "OK: the base chests will be checked before gathering anything." : "OK: only base chests known to hold an item are visited.");
					return;
				}
				if (args.length >= 3 && args[1].equalsIgnoreCase("remove")) {
					String name = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
					Chat.info(Bases.remove(name) ? "Removed base \"" + name + "\"." : "No base named \"" + name + "\".");
					return;
				}
				BlockPos pos = player.blockPosition();
				String name = args.length >= 2 ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)) : null;
				String used = Bases.set(name, pos, bot.dimension());
				Chat.info("Base \"" + used + "\" set to " + pos.toShortString() + ". Chests within 8 blocks of it are used for storage.");
			}
			case "bases" -> {
				if (Bases.all().isEmpty()) Chat.info("No bases yet: !ac base [name] marks one where you stand.");
				for (Bases.Base b : Bases.all()) Chat.info(b.name() + ": " + b.pos().toShortString() + " (" + b.dim().name().toLowerCase() + ")");
			}
			case "logoff" -> {
				boolean on = args.length < 2 || !args[1].equalsIgnoreCase("off");
				Settings.get().logoffWhenDone = on;
				Settings.get().save();
				Chat.info("Log off when done: " + (on ? "on" : "off"));
			}
			case "goto" -> goTo(bot, args);
			case "why" -> {
				Chat.info("Now: " + bot.activeBehavior() + (bot.isRunning() ? "" : " (not running)") + " — " + bot.status());
				Requirement need = bot.activeNeed();
				Requirement target = bot.currentTarget();
				Chat.info("Prerequisite: " + (need == null ? "none" : need) + " · collector target: " + (target == null ? "none" : target));
				Chat.info("Gear: " + bot.gearNote());
				List<String> recent = bot.recentTrace();
				for (String line : recent.subList(Math.max(0, recent.size() - 12), recent.size())) Chat.info("  " + line);
			}
			case "resources" -> Chat.info("Named: " + Resources.describe() + ". Any natural block's item id works, and any craftable or smeltable item"
					+ " (torch, glass, piston, iron_ingot...).");
			case "build" -> build(bot, args);
			case "place" -> place(args);
			case "schematics" -> schematics();
			case "farm" -> farm(bot, args);
			case "built", "farms" -> built();
			case "team" -> team(args);
			case "spawn" -> spawn(bot, args);
			case "film" -> com.autocraft.cinema.Film.command(args);
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

	/** {@code !ac collect <resource> <amount> ... [then ...]}: run as task tree tasks ({@link TaskTree#runCommand}). */
	private static void collect(Bot bot, String[] all) {
		Tail t = tail(all);
		String[] args = t.args();
		if (args.length < 3 || args.length % 2 == 0) {
			throw new IllegalArgumentException("usage: !ac collect <resource> <amount> [<resource> <amount> ...] [then <base> [go|emptiest|similar]]");
		}
		List<TaskNode> items = new ArrayList<>();
		for (int i = 1; i < args.length; i += 2) {
			int amount = Integer.parseInt(args[i + 1]);
			if (amount <= 0) throw new IllegalArgumentException("amount must be positive: " + args[i + 1]);
			if (Resources.forJob(args[i], amount) == null) throw new IllegalArgumentException(Resources.cantGet(args[i]));
			items.add(TaskNode.collect(args[i], amount));
		}
		TaskTree.runCommand(bot, items, t.ending());
	}

	/**
	 * {@code !ac goto <x> [<y>] <z> [overworld|nether] | base <name> | structure <kind> [then ...]}: a go-to task, run from
	 * the task tree. No y: any height on that column; no dimension: the one we're in. {@code !ac goto structure} lists
	 * the kinds.
	 */
	private static void goTo(Bot bot, String[] all) {
		Tail t = tail(all);
		String[] args = t.args();
		String usage = "usage: !ac goto <x> [<y>] <z> [overworld|nether] | goto base <name> | goto structure <kind>  [then <base> [go|emptiest|similar]]";
		if (args.length < 2) throw new IllegalArgumentException(usage);
		Destination to;
		switch (args[1].toLowerCase()) {
			case "base" -> {
				if (args.length < 3) throw new IllegalArgumentException(usage);
				Bases.Base b = base(String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
				to = Destination.base(b.name(), b.pos(), b.dim());
			}
			case "structure" -> {
				String names = String.join(", ", LootSites.structures().stream().map(LootSites.Site::name).toList());
				if (args.length < 3) {
					Chat.info("Structures: " + names + " (nether fortress and bastion are in the Nether).");
					return;
				}
				String name = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
				LootSites.Site site = LootSites.structure(name);
				if (site == null) throw new IllegalArgumentException("no structure '" + name + "' (one of: " + names + ")");
				to = Destination.structure(site.name(), site.dim());
			}
			default -> {
				int end = args.length;
				Dim dim = bot.dimension();
				String last = args[end - 1].toLowerCase();
				if (last.equals("overworld") || last.equals("nether")) {
					dim = last.equals("nether") ? Dim.NETHER : Dim.OVERWORLD;
					end--;
				}
				if (end != 3 && end != 4) throw new IllegalArgumentException(usage);
				BlockPos feet = Navigator.feet(bot.player());
				int x = coord(args[1], feet.getX());
				Integer y = end == 4 ? coord(args[2], feet.getY()) : null;
				int z = coord(args[end - 1], feet.getZ());
				to = Destination.coords(x, y, z, dim);
			}
		}
		TaskTree.runCommand(bot, List.of(TaskNode.goTo(to)), t.ending());
	}

	/** A command's words before {@code then}, and the ending after it ({@code then <base> [go|emptiest|similar]}), if any. */
	private record Tail(String[] args, @Nullable Ending ending) {}

	private static Tail tail(String[] args) {
		int then = -1;
		for (int i = 1; i < args.length; i++) {
			if (args[i].equalsIgnoreCase("then")) {
				then = i;
				break;
			}
		}
		if (then < 0) return new Tail(args, null);
		List<String> words = new ArrayList<>(Arrays.asList(args).subList(then + 1, args.length));
		if (words.isEmpty()) throw new IllegalArgumentException("then <base> [go|emptiest|similar]: which base?");
		Ending.Deposit deposit = words.size() > 1 ? Ending.Deposit.ofWord(words.getLast()) : null;
		if (deposit != null) words.removeLast();
		Bases.Base b = base(String.join(" ", words));
		return new Tail(Arrays.copyOf(args, then), new Ending(b.name(), deposit == null ? Ending.Deposit.NONE : deposit));
	}

	private static Bases.Base base(String name) {
		Bases.Base b = Bases.named(name);
		if (b == null) {
			throw new IllegalArgumentException("no base named '" + name + "' (bases: "
					+ String.join(", ", Bases.all().stream().map(Bases.Base::name).toList()) + "; !ac base <name> adds one)");
		}
		return b;
	}

	/** {@code !ac build <name> <x> <y> <z> [rotation] [then ...]} or {@code !ac build <name> here [rotation] [then ...]}. */
	private static void build(Bot bot, String[] all) {
		Tail t = tail(all);
		String[] args = t.args();
		if (args.length < 3) {
			throw new IllegalArgumentException("usage: !ac build <schematic> <x> <y> <z> [0|90|180|270]  or  !ac build <schematic> here [rotation]"
					+ "  [then <base> [go|emptiest|similar]]");
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
		if (t.ending() != null) bot.setEnding(t.ending());
	}

	/** {@code !ac place <name>}: show it as a ghost at the crosshair and position it with the placement keys. */
	private static void place(String[] args) {
		if (args.length < 2) throw new IllegalArgumentException("usage: !ac place <schematic>");
		Schematic s = SchematicLibrary.get(args[1]);
		if (s == null) throw new IllegalArgumentException("no schematic '" + args[1] + "' (see !ac schematics)");
		Placements.start(s);
	}

	/** {@code !ac farm <resource> [x y z | here] [rotation] [then ...]}: build a farm for a resource (site picked if no position). */
	private static void farm(Bot bot, String[] all) {
		Tail t = tail(all);
		String[] args = t.args();
		if (args.length < 2) throw new IllegalArgumentException("usage: !ac farm <resource> [<x> <y> <z> | here] [rotation] [then <base> [go|emptiest|similar]]");
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
		if (t.ending() != null) bot.setEnding(t.ending());
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
		if (bot.destination() != null) Chat.info("Going to " + bot.destination().describe());
		BuildOrder build = bot.buildOrder();
		if (build != null) {
			Chat.info("Building " + build.schematic().name() + " at " + build.origin().toShortString() + ": " + bot.buildPlaced() + "/"
					+ bot.buildTotal() + " blocks");
		}
		Requirement need = bot.activeNeed();
		if (bot.ending() != null) Chat.info((bot.finishing() != null ? "Done; now: " : "Then: ") + bot.ending().describe());
		if (need != null) Chat.info("Prerequisite: " + need);
		Chat.info(String.format("HP %.0f/20, food %d, pos %s", p.getHealth(), p.getFoodData().getFoodLevel(), p.blockPosition().toShortString()));
	}

	/**
	 * {@code !ac spawn}: where the bot thinks the respawn point is and the beds it put down; {@code here} or {@code x y z}
	 * sets it (this dimension); {@code beds on|off [blocks]}: the respawn bed setting and its distance.
	 */
	private static void spawn(Bot bot, String[] args) {
		LocalPlayer p = bot.player();
		String dim = SpawnPoint.dimensionOf(p.level());
		Settings settings = Settings.get();
		switch (args.length > 1 ? args[1].toLowerCase() : "") {
			case "" -> {
				SpawnPoint.Spot s = SpawnPoint.current();
				Chat.info(s == null ? "Spawn point: not known yet (the world spawn shows in the Overworld; !ac spawn here sets it)."
						: "Spawn point: " + s.describe() + (Double.isInfinite(s.distance(p.blockPosition(), dim)) ? "."
								: ", " + (int) s.distance(p.blockPosition(), dim) + " blocks from here."));
				List<String> beds = SpawnPoint.beds().stream().map(b -> b.pos().toShortString()).toList();
				Chat.info("Respawn beds: " + (settings.respawnBeds ? "on (spawn point over " + settings.respawnBedDistance + " blocks away)" : "off")
						+ "; beds we put down in this world: " + (beds.isEmpty() ? "none" : String.join(" · ", beds)) + ".");
			}
			case "here" -> SpawnPoint.set(Navigator.feet(p), dim, SpawnPoint.Kind.SET, "set by you");
			case "beds" -> {
				boolean on = args.length < 3 || !args[2].equalsIgnoreCase("off");
				if (args.length > 3) {
					int blocks = Integer.parseInt(args[3]);
					if (blocks < 32) throw new IllegalArgumentException("the distance must be at least 32 blocks");
					settings.respawnBedDistance = blocks;
				}
				settings.respawnBeds = on;
				settings.save();
				Chat.info("Respawn beds: " + (on ? "on, when the spawn point is over " + settings.respawnBedDistance + " blocks away." : "off."));
			}
			default -> {
				if (args.length < 4) throw new IllegalArgumentException("usage: !ac spawn [here | <x> <y> <z> | beds on|off [blocks]]");
				SpawnPoint.set(position(p, args, 1), dim, SpawnPoint.Kind.SET, "set by you");
			}
		}
	}

	private static void help() {
		Chat.info("Press K for the control panel. Commands: !ac collect <item> <amount> ... (mined, crafted, hunted or special) | goto x [y] z"
				+ " [overworld|nether] | goto base <name> | goto structure <kind> | place <schematic> (ghost preview, then position it) | build"
				+ " <schematic> <x y z|here> [rot] | farm <resource> [x y z|here] — collect, goto, build and farm take \"then <base>"
				+ " [go|emptiest|similar]\" to end at a base (and store there) | team host|join|leave | schematics | built | stop | pause | resume"
				+ " | status | base [name] | base remove <name> | bases | base stocked on|off | logoff on|off"
				+ " | spawn [here | x y z] | spawn beds on|off [blocks]"
				+ " | resources | film play|list|stop (plays the explainer video in this world)");
	}
}
