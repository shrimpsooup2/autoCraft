package com.autocraft.behavior;

import com.autocraft.Bot;
import com.autocraft.Chat;
import com.autocraft.control.ContainerOpener;
import com.autocraft.control.InvUtil;
import com.autocraft.goal.Job;
import com.autocraft.goal.Requirement;
import com.autocraft.path.Goal;
import com.autocraft.path.Navigator;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import org.jspecify.annotations.Nullable;

/**
 * Walk to the base, then shift-click every job item into the chests/barrels around it, crediting the job
 * with what was stored. DONE when nothing job-related is left in the inventory or every chest is full.
 */
public final class BaseDeposit {
	public enum Status { RUNNING, DONE, FAILED }

	private enum Phase { WALK, FIND, APPROACH, OPEN, FILL }

	private static final int BASE_RADIUS = 8;

	private final BlockPos base;
	private Phase phase = Phase.WALK;
	private final List<BlockPos> chests = new ArrayList<>();
	private int chestIndex;
	private @Nullable ContainerOpener opener;
	private int deposited;

	public BaseDeposit(BlockPos base) {
		this.base = base.immutable();
	}

	public Status tick(Bot bot) {
		Job job = bot.job();
		LocalPlayer p = bot.player();
		if (job == null) return Status.FAILED;
		switch (phase) {
			case WALK -> {
				bot.setStatus("returning to base " + base.toShortString());
				bot.nav().setGoal(new Goal.Near(base, 3));
				Navigator.Status s = bot.nav().tick();
				if (s == Navigator.Status.FAILED) return Status.FAILED;
				if (s == Navigator.Status.ARRIVED) {
					bot.nav().stop();
					enter(Phase.FIND);
				}
			}
			case FIND -> {
				chests.clear();
				chests.addAll(bot.scanner().findBlocks(s -> s.getBlock() instanceof ChestBlock || s.getBlock() instanceof BarrelBlock, 1, 64));
				chests.removeIf(c -> c.distSqr(base) > BASE_RADIUS * BASE_RADIUS);
				if (chests.isEmpty()) {
					Chat.warn("No chests or barrels within " + BASE_RADIUS + " blocks of the base.");
					return Status.FAILED;
				}
				chestIndex = 0;
				enter(Phase.APPROACH);
			}
			case APPROACH -> {
				if (chestIndex >= chests.size()) return finish(p, job);
				BlockPos chest = chests.get(chestIndex);
				bot.setStatus("depositing into " + chest.toShortString());
				if (p.isWithinBlockInteractionRange(chest, -0.5)) {
					bot.nav().stop();
					opener = new ContainerOpener(chest, AbstractContainerMenu.class);
					enter(Phase.OPEN);
				} else {
					bot.nav().setGoal(new Goal.Adjacent(chest));
					if (bot.nav().tick() == Navigator.Status.FAILED) nextChest(bot);
				}
			}
			case OPEN -> {
				ContainerOpener.Status s = opener.tick(bot);
				if (s == ContainerOpener.Status.FAILED) nextChest(bot);
				else if (s == ContainerOpener.Status.OPEN && p.containerMenu instanceof ChestMenu) enter(Phase.FILL);
				else if (s == ContainerOpener.Status.OPEN) nextChest(bot); // not a storage menu after all
			}
			case FILL -> {
				if (!(p.containerMenu instanceof ChestMenu menu)) return enter(Phase.APPROACH);
				if (!moveOne(p, menu, job)) {
					p.closeContainer();
					if (countJobItems(p, job) == 0) return finish(p, job);
					nextChest(bot); // this chest is full
				}
			}
		}
		return Status.RUNNING;
	}

	/** Shift-click one job stack into the open chest; false when none left or it didn't move (chest full). */
	private boolean moveOne(LocalPlayer p, ChestMenu menu, Job job) {
		for (int inv = 0; inv < InvUtil.MAIN_SIZE; inv++) {
			ItemStack stack = p.getInventory().getItem(inv);
			if (stack.isEmpty()) continue;
			List<Requirement> reqs = job.matching(stack);
			if (reqs.isEmpty()) continue;
			int slot = InvUtil.menuSlot(menu, p, inv);
			if (slot < 0) continue;
			int before = stack.getCount();
			InvUtil.quickMove(p, slot);
			int moved = before - p.getInventory().getItem(inv).getCount();
			if (moved <= 0) return false;
			job.addStored(reqs.getFirst(), moved);
			deposited += moved;
			return true;
		}
		return false;
	}

	private static int countJobItems(LocalPlayer p, Job job) {
		int n = 0;
		for (Requirement r : job.requirements()) n += InvUtil.count(p, r.counts());
		return n;
	}

	private void nextChest(Bot bot) {
		if (!InvUtil.inventoryMenuActive(bot.player())) bot.player().closeContainer();
		chestIndex++;
		enter(Phase.APPROACH);
	}

	private Status finish(LocalPlayer p, Job job) {
		if (!InvUtil.inventoryMenuActive(p)) p.closeContainer();
		Chat.info("Deposited " + deposited + " items at base. " + job.progress(p));
		return countJobItems(p, job) == 0 || deposited > 0 ? Status.DONE : Status.FAILED;
	}

	private Status enter(Phase next) {
		phase = next;
		return Status.RUNNING;
	}
}
