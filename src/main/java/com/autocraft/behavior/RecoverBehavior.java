package com.autocraft.behavior;

import com.autocraft.Bot;
import com.autocraft.Chat;
import com.autocraft.path.Goal;
import com.autocraft.path.Navigator;
import net.minecraft.core.BlockPos;

/**
 * After a death (with "recover after death" on): go back to where we died, where {@link PickupBehavior} (which
 * runs first) picks up what's lying around; once it has nothing left to do there, the job carries on. Another
 * dimension is reached by the portal behavior; items wait in unloaded chunks, and get five minutes once loaded again.
 */
public final class RecoverBehavior implements Behavior {
	private static final int ARRIVE_DISTANCE = 3;
	/** Arrived and nothing left worth picking up for this long: done. */
	private static final int IDLE_TICKS = 20 * 3;
	/** Items despawn five minutes after their chunk loads: that long once the death spot is loaded, at most. */
	private static final int TIMEOUT = 20 * 60 * 5;
	/** The whole trip, however far (a long walk, or digging by hand without a pickaxe): give up after this. */
	private static final int TRIP_LIMIT = 20 * 60 * 20;
	/** Farther than this (a respawn far from the death spot) can't be walked before the timeout: don't set out. */
	private static final double MAX_TRIP = 1000;
	/** Searches that may come up empty (a world still loading after a portal) before the items are given up. */
	private static final int MAX_NAV_FAILURES = 4;
	private static final int RETRY_TICKS = 40;
	/** Below this health the trip waits (see tick). */
	private static final float HEAL_TO = 14;

	private int ticks;
	private int idle;
	private boolean arrived;
	private int navFailures;
	private int retryAt;

	@Override
	public String name() {
		return "recover";
	}

	@Override
	public void reset() {
		ticks = 0;
		idle = 0;
		arrived = false;
		navFailures = 0;
		retryAt = 0;
	}

	@Override
	public boolean tick(Bot bot) {
		Bot.Recovery r = bot.recovery();
		if (r == null || r.dim() != bot.dimension()) return false;
		// the five minutes the items have count from when their chunk is loaded (Bot#recoveryAge) — the clock used to
		// start with the trip, and a 460-block trip back to lost netherite ran out of time halfway there
		if (bot.recoveryAge() > TIMEOUT) return finish(bot, "gave up going back for the items");
		// hurt: heal first. What killed us is likely still there (a trial chamber's skeletons and breezes), and at 3 hp
		// with nothing to eat — no health coming back — it walked back into them, dying again and again. Items wait
		// while their chunk is unloaded; the food behavior (after this one) gets what heals
		if (!arrived && bot.player().getHealth() < HEAL_TO) return false;
		if (++ticks > TRIP_LIMIT) return finish(bot, "gave up going back for the items");
		if (bot.tripPickaxe() != null) return false; // gear makes a pickaxe first (Bot.considerTripPickaxe); the clock runs on
		BlockPos feet = Navigator.feet(bot.player());
		if (ticks == 1 && feet.distSqr(r.pos()) > MAX_TRIP * MAX_TRIP) {
			return bot.startFlight(r.pos()) || finish(bot, "where we died is too far away");
		}
		if (!arrived) {
			if (ticks < retryAt) {
				bot.setStatus("going back for our items at " + r.pos().toShortString() + " (waiting for the area to load)");
				return true;
			}
			if (feet.distSqr(r.pos()) <= ARRIVE_DISTANCE * ARRIVE_DISTANCE) {
				arrived = true;
				bot.nav().stop();
				return true;
			}
			bot.setStatus("going back for our items at " + r.pos().toShortString());
			if (bot.startFlight(r.pos())) return true;
			bot.nav().setGoal(new Goal.Near(r.pos(), ARRIVE_DISTANCE - 1));
			if (bot.nav().tick() == Navigator.Status.FAILED) {
				// right after a portal the world around is still loading and a search can come up "unreachable" (the
				// items were given up the moment the bot arrived): try again a few times first
				bot.nav().stop();
				if (bot.pickaxeForStuckTrip()) return true; // planned again once it's made
				if (++navFailures >= MAX_NAV_FAILURES) return finish(bot, "can't get back to where we died");
				retryAt = ticks + RETRY_TICKS;
			}
			return true;
		}
		// the pickup behavior runs before this one: getting here means nothing worth carrying is lying around any more
		bot.nav().stop();
		bot.setStatus("looking for our items");
		if (++idle > IDLE_TICKS) return finish(bot, "picked up what was left");
		return true;
	}

	private boolean finish(Bot bot, String what) {
		Chat.info("Recovery: " + what + "; carrying on.");
		bot.setRecovery(null);
		bot.nav().stop();
		reset();
		return true;
	}
}
