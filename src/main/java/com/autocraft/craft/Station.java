package com.autocraft.craft;

import com.autocraft.Bot;
import com.autocraft.Chat;
import com.autocraft.control.ContainerOpener;
import com.autocraft.control.InvUtil;
import com.autocraft.control.Placement;
import com.autocraft.path.Goal;
import com.autocraft.path.Navigator;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.Nullable;

/**
 * Gets a work block's menu open: reuse one within {@link Stations#USE_RADIUS} or place ours beside us (mining a
 * wall cell out first when there's no room), walk into reach, right-click until the menu shows.
 */
final class Station {
	enum Status { WORKING, OPEN, FAILED }

	private static final int TIMEOUT = 20 * 30;

	private final Block block;
	private final Item item;
	private final Class<? extends AbstractContainerMenu> menu;
	private @Nullable BlockPos pos;
	private @Nullable BlockPos digging;
	private boolean placedByUs;
	private @Nullable ContainerOpener opener;
	private int ticks;
	/** A nearby one we couldn't walk to: ours goes down here instead. */
	private @Nullable BlockPos unreachable;

	Station(Block block, Item item, Class<? extends AbstractContainerMenu> menu) {
		this.block = block;
		this.item = item;
		this.menu = menu;
	}

	Status tick(Bot bot) {
		LocalPlayer p = bot.player();
		if (menu.isInstance(p.containerMenu)) return Status.OPEN;
		if (++ticks > TIMEOUT) return failed("timed out at " + pos);
		if (pos == null || !bot.level().getBlockState(pos).is(block)) {
			pos = Stations.nearby(bot, block);
			placedByUs = false;
			if (pos == null || pos.equals(unreachable)) return placeOurs(bot, p);
		}
		if (!p.isWithinBlockInteractionRange(pos, -0.5)) {
			bot.setStatus("walking to the " + block.getName().getString().toLowerCase());
			bot.nav().setGoal(new Goal.Adjacent(pos));
			if (bot.nav().tick() != Navigator.Status.FAILED) return Status.WORKING;
			// that one can't be reached (across water, walled in): set ours down here if we carry one — every craft
			// walked at the same unreachable table and failed on the spot, five times a second
			bot.nav().stop();
			unreachable = pos;
			pos = null;
			return placeOurs(bot, p);
		}
		bot.nav().stop();
		if (opener == null) opener = new ContainerOpener(pos, menu);
		return switch (opener.tick(bot)) {
			case OPEN -> Status.OPEN;
			case FAILED -> failed("couldn't open the one at " + pos);
			case WAITING -> Status.WORKING;
		};
	}

	private Status placeOurs(Bot bot, LocalPlayer p) {
		if (InvUtil.count(p, s -> s.is(item)) == 0) return failed("none carried to set down (unreachable: " + unreachable + ")"); // the planner should have made one
		if (!InvUtil.inventoryMenuActive(p)) p.closeContainer();
		bot.nav().stop();
		if (digging != null) {
			bot.setStatus("making room for a " + block.getName().getString().toLowerCase());
			if (!bot.controls().mine(digging)) return Status.WORKING;
			digging = null;
		}
		BlockPos at = Placement.besideFeet(bot, s -> s.is(item), false);
		if (at != null) {
			pos = at;
			placedByUs = true;
			Stations.notePlaced(bot, at);
			return Status.WORKING;
		}
		digging = Placement.roomToDig(bot);
		return digging == null ? failed("no spot beside " + p.blockPosition().toShortString() + " to set one down") : Status.WORKING;
	}

	private Status failed(String why) {
		Chat.LOG.debug("{} station failed: {}", block.getName().getString(), why);
		return Status.FAILED;
	}

	/** Position of the block if we placed it, else null. */
	@Nullable BlockPos placed() {
		return placedByUs ? pos : null;
	}
}
