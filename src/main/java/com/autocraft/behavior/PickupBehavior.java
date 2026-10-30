package com.autocraft.behavior;

import com.autocraft.Bot;
import com.autocraft.control.InvUtil;
import com.autocraft.path.Goal;
import com.autocraft.path.Navigator;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Picks up whatever lying nearby is worth carrying — our own things after a death, mob loot and meat, mined blocks,
 * a spilled chest — and throws junk away to make room when the inventory is full. Junk (the storage behavior's rule,
 * {@link StorageBehavior#worthCarrying}) is left lying, so nothing it throws away comes back. Nearest first; a drop
 * not reached within a few seconds (in lava, behind a wall) is left alone for a minute.
 */
public final class PickupBehavior implements Behavior {
	private static final double RADIUS = 12;
	private static final int ITEM_TICKS = 20 * 6;
	private static final int SKIP_TICKS = 20 * 60;

	/** Drops given up on: entity id → until when. */
	private final Map<Integer, Long> skipped = new HashMap<>();
	private @Nullable ItemEntity chasing;
	private int chaseTicks;
	private long tick;

	@Override
	public String name() {
		return "pickup";
	}

	@Override
	public void reset() {
		skipped.clear();
		chasing = null;
	}

	@Override
	public boolean tick(Bot bot) {
		tick++;
		// quiet (an ancient city): moving about for drops wakes the warden; the loot procedure picks up its own
		if (!bot.hasWork() && bot.recovery() == null || bot.quiet()) return false;
		LocalPlayer p = bot.player();
		if (chasing == null || !chasing.isAlive() || !wanted(bot, p, chasing)) {
			if (chasing != null) bot.nav().stop();
			chasing = next(bot, p);
			chaseTicks = 0;
			if (chasing == null) return false;
		}
		ItemStack stack = chasing.getItem();
		if (!InvUtil.hasRoomFor(p, stack)) {
			int junk = StorageBehavior.findJunk(bot);
			if (junk < 0) { // nothing to throw away for it
				skip(chasing);
				return false;
			}
			if (!InvUtil.inventoryMenuActive(p)) p.closeContainer();
			bot.setStatus("dropping " + p.getInventory().getItem(junk).getHoverName().getString() + " to make room");
			return StorageBehavior.throwAway(bot, p, junk, chasing.position());
		}
		if (inReach(p, chasing)) { // the game picks it up as soon as it may: get on with the work
			chasing = null;
			bot.nav().stop();
			return false;
		}
		bot.setStatus("picking up " + stack.getHoverName().getString());
		bot.nav().setGoal(new Goal.Near(chasing.blockPosition(), 1));
		if (++chaseTicks > ITEM_TICKS || bot.nav().tick() == Navigator.Status.FAILED) {
			bot.nav().stop();
			skip(chasing);
		}
		return true;
	}

	/** Nearest drop worth carrying that isn't in reach already. */
	private @Nullable ItemEntity next(Bot bot, LocalPlayer p) {
		for (ItemEntity e : bot.scanner().items(RADIUS, s -> true)) {
			if (wanted(bot, p, e) && !inReach(p, e)) return e;
		}
		return null;
	}

	private boolean wanted(Bot bot, LocalPlayer p, ItemEntity e) {
		Long until = skipped.get(e.getId());
		if (until != null && until > tick) return false;
		if (e.isInLava()) return false; // netherite floats on it; nothing walks there
		return StorageBehavior.worthCarrying(bot, p, e.getItem());
	}

	/** Within the box the game picks items up from (a block around the body). */
	private static boolean inReach(LocalPlayer p, ItemEntity e) {
		return p.getBoundingBox().inflate(1.0, 0.5, 1.0).intersects(e.getBoundingBox());
	}

	private void skip(ItemEntity e) {
		skipped.put(e.getId(), tick + SKIP_TICKS);
		if (skipped.size() > 256) skipped.values().removeIf(t -> t <= tick);
		chasing = null;
	}
}
