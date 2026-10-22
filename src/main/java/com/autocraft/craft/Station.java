package com.autocraft.craft;

import com.autocraft.Bot;
import com.autocraft.control.ContainerOpener;
import com.autocraft.control.InvUtil;
import com.autocraft.control.Placement;
import com.autocraft.path.Goal;
import com.autocraft.path.Navigator;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.Nullable;

/**
 * Gets a work block's menu open: reuse one within 16 blocks or place ours beside us, walk into reach,
 * right-click until the menu shows. Remembers whether it placed the block so it can be picked up later.
 */
final class Station {
	enum Status { WORKING, OPEN, FAILED }

	private static final int SEARCH_RADIUS = 16;
	private static final int TIMEOUT = 20 * 20;

	private final Block block;
	private final Item item;
	private final Class<? extends AbstractContainerMenu> menu;
	private @Nullable BlockPos pos;
	private boolean placedByUs;
	private @Nullable ContainerOpener opener;
	private int ticks;
	private int placedAt;

	Station(Block block, Item item, Class<? extends AbstractContainerMenu> menu) {
		this.block = block;
		this.item = item;
		this.menu = menu;
	}

	Status tick(Bot bot) {
		LocalPlayer p = bot.player();
		if (menu.isInstance(p.containerMenu)) return Status.OPEN;
		if (++ticks > TIMEOUT) return Status.FAILED;
		if (pos == null || !bot.mc().level.getBlockState(pos).is(block)) {
			if (placedByUs && ticks - placedAt < 20) return Status.WORKING; // server hasn't confirmed yet
			pos = nearby(bot, block);
			placedByUs = false;
			if (pos == null) {
				if (!InvUtil.inventoryMenuActive(p)) p.closeContainer();
				pos = Placement.besideFeet(bot, s -> s.is(item));
				if (pos == null) return Status.FAILED;
				placedByUs = true;
				placedAt = ticks;
				return Status.WORKING;
			}
		}
		if (!p.isWithinBlockInteractionRange(pos, -0.5)) {
			bot.nav().setGoal(new Goal.Adjacent(pos));
			return bot.nav().tick() == Navigator.Status.FAILED ? Status.FAILED : Status.WORKING;
		}
		bot.nav().stop();
		if (opener == null) opener = new ContainerOpener(pos, menu);
		return switch (opener.tick(bot)) {
			case OPEN -> Status.OPEN;
			case FAILED -> Status.FAILED;
			case WAITING -> Status.WORKING;
		};
	}

	/** Position of the block if we placed it (to pick it back up), else null. */
	@Nullable BlockPos placed() {
		return placedByUs ? pos : null;
	}

	static @Nullable BlockPos nearby(Bot bot, Block block) {
		BlockPos feet = Navigator.feet(bot.player());
		List<BlockPos> found = bot.scanner().findBlocks(s -> s.is(block), 1, 8);
		for (BlockPos b : found) {
			if (b.distSqr(feet) <= SEARCH_RADIUS * SEARCH_RADIUS) return b;
		}
		return null;
	}
}
