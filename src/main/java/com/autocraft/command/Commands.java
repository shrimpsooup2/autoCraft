package com.autocraft.command;

import com.autocraft.Bot;
import com.autocraft.Chat;
import com.autocraft.goal.Job;
import com.autocraft.goal.Requirement;
import com.autocraft.goal.Resources;
import com.autocraft.path.Goal;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

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
				bot.setLogoffWhenDone(on);
				Chat.info("Log off when done: " + (on ? "on" : "off"));
			}
			case "goto" -> {
				if (args.length < 4) throw new IllegalArgumentException("usage: !ac goto <x> <y> <z>");
				BlockPos target = new BlockPos(Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]));
				bot.startGoto(new Goal.Block(target));
			}
			case "resources" -> Chat.info("Known: " + Resources.describe() + ". Any block item id also works.");
			default -> help();
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
			Requirement r = Resources.get(args[i], amount);
			if (r == null) throw new IllegalArgumentException("unknown resource '" + args[i] + "' (try !ac resources)");
			reqs.add(r);
		}
		bot.start(new Job(reqs));
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
		if (bot.subTask() != null) Chat.info("Prerequisite: " + bot.subTask());
		Chat.info(String.format("HP %.0f/20, food %d, pos %s", p.getHealth(), p.getFoodData().getFoodLevel(), p.blockPosition().toShortString()));
	}

	private static void help() {
		Chat.info("!ac collect <resource> <amount> ... | stop | pause | resume | status | base | logoff on|off | goto x y z | resources");
	}
}
