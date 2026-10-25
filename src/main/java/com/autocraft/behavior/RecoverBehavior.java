package com.autocraft.behavior;

import com.autocraft.Bot;
import com.autocraft.Chat;
import com.autocraft.path.Goal;
import com.autocraft.path.Navigator;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;

/**
 * After a death (with "recover after death" on): go back to where we died and pick up what's lying there, then
 * let the job carry on. Another dimension is reached by the portal behavior; items wait in unloaded chunks, and
 * get five minutes once loaded again.
 */
public final class RecoverBehavior implements Behavior {
	private static final double PICKUP_RADIUS = 10;
	private static final int ARRIVE_DISTANCE = 3;
	/** Nothing left to pick up for this long after arriving: done. */
	private static final int IDLE_TICKS = 20 * 5;
	private static final int TIMEOUT = 20 * 60 * 5;

	private int ticks;
	private int idle;
	private boolean arrived;
	private int picked;

	@Override
	public String name() {
		return "recover";
	}

	@Override
	public void reset() {
		ticks = 0;
		idle = 0;
		arrived = false;
		picked = 0;
	}

	@Override
	public boolean tick(Bot bot) {
		Bot.Recovery r = bot.recovery();
		if (r == null || r.dim() != bot.dimension()) return false;
		if (++ticks > TIMEOUT) return finish(bot, "gave up going back for the items");
		BlockPos feet = Navigator.feet(bot.player());
		if (!arrived) {
			if (feet.distSqr(r.pos()) <= ARRIVE_DISTANCE * ARRIVE_DISTANCE) {
				arrived = true;
				bot.nav().stop();
				return true;
			}
			bot.setStatus("going back for our items at " + r.pos().toShortString());
			if (bot.startFlight(r.pos())) return true;
			bot.nav().setGoal(new Goal.Near(r.pos(), ARRIVE_DISTANCE - 1));
			if (bot.nav().tick() == Navigator.Status.FAILED) {
				bot.nav().stop();
				return finish(bot, "can't get back to where we died");
			}
			return true;
		}
		List<ItemEntity> items = bot.scanner().items(PICKUP_RADIUS, s -> true);
		ItemEntity nearest = items.stream().filter(e -> e.blockPosition().distSqr(r.pos()) <= PICKUP_RADIUS * PICKUP_RADIUS)
				.min(Comparator.comparingDouble(e -> e.distanceToSqr(bot.player()))).orElse(null);
		if (nearest == null) {
			if (++idle > IDLE_TICKS) return finish(bot, picked > 0 ? "picked our things back up" : "nothing left where we died");
			bot.nav().stop();
			return true;
		}
		idle = 0;
		bot.setStatus("picking our items back up");
		bot.nav().setGoal(new Goal.Near(nearest.blockPosition(), 0));
		if (bot.nav().tick() == Navigator.Status.ARRIVED) picked++;
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
