package com.autocraft.behavior;

import com.autocraft.Bases;
import com.autocraft.Bot;
import com.autocraft.Chat;
import com.autocraft.Settings;
import com.autocraft.craft.Stations;
import com.autocraft.goal.Ending;
import com.autocraft.goal.Job;
import com.autocraft.path.Goal;
import com.autocraft.path.Navigator;
import com.autocraft.tasks.TaskTree;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jspecify.annotations.Nullable;

/**
 * The end-of-job flow, the same for every task. Once its goal is met — the job's items collected (noticed here),
 * the build finished, the items fetched, the destination reached ({@link Bot#goalMet}) — the tables and furnaces put
 * down are picked up; a task that ends at a base then goes there (the portal behavior takes it first when the base
 * is in the other dimension) and, by its deposit choice, stores what it carries ({@link BaseDeposit},
 * {@link StorageBehavior#storesAtBase}); then the bot stops and logs off (when set, and the task tree has nothing
 * more). A task without an ending stops where it is.
 */
public final class FinishBehavior implements Behavior {
	private static final int ARRIVE = 3;
	/** Searches that may come up empty (a world still loading after a portal) before the trip is given up. */
	private static final int MAX_NAV_FAILURES = 4;
	private static final int RETRY_TICKS = 40;

	private enum Step { WALK, STORE, STOP }

	private final Stations.Cleanup cleanup = new Stations.Cleanup();
	private Step step = Step.WALK;
	private @Nullable BaseDeposit deposit;
	private int navFailures;
	private int wait;

	@Override
	public String name() {
		return "finish";
	}

	@Override
	public void reset() {
		step = Step.WALK;
		deposit = null;
		navFailures = 0;
		wait = 0;
		cleanup.reset();
	}

	@Override
	public boolean tick(Bot bot) {
		String reason = bot.finishing();
		if (reason == null) {
			Job job = bot.job();
			if (job == null || !job.isComplete(bot.player())) return false;
			Chat.info("Job complete: " + job.progress(bot.player()));
			bot.goalMet(Bot.JOB_COMPLETE); // from here on it finishes, whatever happens to the items on the way
			reason = Bot.JOB_COMPLETE;
		}
		if (cleanup.tick(bot)) return true; // don't leave tables and furnaces behind
		if (step != Step.STOP) {
			Ending end = bot.ending();
			Bases.Base base = bot.endBase();
			if (end == null) {
				step = Step.STOP;
			} else if (base == null) {
				Chat.warn("No base named \"" + end.base() + "\" any more; the task ends here.");
				step = Step.STOP;
			} else if (base.dim() != bot.dimension()) {
				return false; // the portal behavior takes us there
			} else if (bot.tripPickaxe() != null) {
				return false; // gear makes a pickaxe first (Bot.considerTripPickaxe)
			} else if (step == Step.WALK) {
				switch (walk(bot, base)) {
					case ARRIVED -> {
						Chat.info("At base \"" + base.name() + "\".");
						step = end.deposit() == Ending.Deposit.NONE ? Step.STOP : Step.STORE;
					}
					case FAILED -> step = Step.STOP;
					default -> {
						return true;
					}
				}
			} else {
				if (deposit == null) deposit = new BaseDeposit(base.pos(), chests(end.deposit()), StorageBehavior::storesAtBase);
				if (deposit.tick(bot) == BaseDeposit.Status.RUNNING) return true;
				bot.nav().stop();
				step = Step.STOP;
			}
			if (step != Step.STOP) return true;
		}
		bot.stop(reason);
		if (Settings.get().logoffWhenDone && !TaskTree.hasMore()) { // the task tree has more to do: stay
			Minecraft mc = bot.mc();
			Chat.info("Logging off.");
			mc.schedule(() -> mc.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE)); // outside the tick
		}
		return true;
	}

	/** Toward the base: MOVING, then ARRIVED — or FAILED when no way there turns up (the task then ends where it is). */
	private Navigator.Status walk(Bot bot, Bases.Base base) {
		bot.setStatus("going to base \"" + base.name() + "\" at " + base.pos().toShortString());
		if (wait > 0) {
			wait--; // right after a portal the world around is still loading: a search can come up empty
			return Navigator.Status.MOVING;
		}
		if (bot.startFlight(base.pos())) return Navigator.Status.MOVING;
		bot.nav().setGoal(new Goal.Near(base.pos(), ARRIVE));
		Navigator.Status s = bot.nav().tick();
		if (s == Navigator.Status.ARRIVED) {
			bot.nav().stop();
			return s;
		}
		if (s != Navigator.Status.FAILED) return Navigator.Status.MOVING;
		bot.nav().stop();
		if (bot.pickaxeForStuckTrip()) return Navigator.Status.MOVING; // planned again once it's made
		if (++navFailures < MAX_NAV_FAILURES) {
			wait = RETRY_TICKS;
			return Navigator.Status.MOVING;
		}
		Chat.warn("Can't find a way to base \"" + base.name() + "\"; the task ends here.");
		return Navigator.Status.FAILED;
	}

	private static BaseDeposit.Chests chests(Ending.Deposit d) {
		return d == Ending.Deposit.SIMILAR ? BaseDeposit.Chests.SIMILAR : BaseDeposit.Chests.EMPTIEST;
	}
}
