package com.autocraft.build;

import com.autocraft.Bot;
import com.autocraft.control.ContainerOpener;
import com.autocraft.control.InvUtil;
import com.autocraft.path.Goal;
import com.autocraft.path.Navigator;
import com.autocraft.world.BlockUtil;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import org.jspecify.annotations.Nullable;

/**
 * A gatherer's delivery: walk to the team depot, put down a chest there if there isn't one, and shift-click the
 * build materials in. NEED_CHEST asks the caller to craft a chest first.
 */
public final class DepotDeposit {
	public enum Status { RUNNING, DONE, NEED_CHEST }

	private enum Phase { WALK, FIND, PLACE, APPROACH, OPEN, FILL }

	private static final int RADIUS = 4;
	private static final int PLACE_WAIT = 6;
	private static final int MAX_PLACE_TRIES = 6;

	private final BlockPos depot;
	private final Set<Item> items;
	private final Map<Item, Integer> delivered = new HashMap<>();
	private final List<BlockPos> chests = new ArrayList<>();
	private Phase phase = Phase.WALK;
	private int chestIndex;
	private @Nullable ContainerOpener opener;
	private int placeTries;
	private int wait;

	public DepotDeposit(BlockPos depot, Set<Item> items) {
		this.depot = depot.immutable();
		this.items = Set.copyOf(items);
	}

	public Map<Item, Integer> delivered() {
		return delivered;
	}

	public Status tick(Bot bot) {
		LocalPlayer p = bot.player();
		if (wait > 0) {
			wait--;
			return Status.RUNNING;
		}
		switch (phase) {
			case WALK -> {
				bot.setStatus("taking materials to the depot");
				if (bot.startFlight(depot)) return Status.RUNNING;
				bot.nav().setGoal(new Goal.Near(depot, 2));
				Navigator.Status s = bot.nav().tick();
				if (s == Navigator.Status.FAILED) return Status.DONE;
				if (s == Navigator.Status.ARRIVED) {
					bot.nav().stop();
					phase = Phase.FIND;
				}
			}
			case FIND -> {
				chests.clear();
				chests.addAll(bot.scanner().findBlocks(s -> s.getBlock() instanceof ChestBlock || s.getBlock() instanceof BarrelBlock, 1, 32));
				chests.removeIf(c -> c.distSqr(depot) > RADIUS * RADIUS);
				chestIndex = 0;
				phase = chests.isEmpty() ? Phase.PLACE : Phase.APPROACH;
			}
			case PLACE -> {
				if (InvUtil.count(p, s -> s.is(Items.CHEST)) == 0) return Status.NEED_CHEST;
				if (++placeTries > MAX_PLACE_TRIES) return Status.DONE;
				BlockPos spot = chestSpot(bot.mc().level);
				if (spot == null) return Status.DONE;
				bot.controls().place(spot, s -> s.is(Items.CHEST));
				wait = PLACE_WAIT;
				phase = Phase.FIND;
			}
			case APPROACH -> {
				if (chestIndex >= chests.size()) {
					phase = Phase.PLACE; // every chest is full: add one
					return Status.RUNNING;
				}
				BlockPos chest = chests.get(chestIndex);
				if (p.isWithinBlockInteractionRange(chest, -0.5)) {
					bot.nav().stop();
					opener = new ContainerOpener(chest, AbstractContainerMenu.class);
					phase = Phase.OPEN;
				} else {
					bot.nav().setGoal(new Goal.Adjacent(chest));
					if (bot.nav().tick() == Navigator.Status.FAILED) chestIndex++;
				}
			}
			case OPEN -> {
				ContainerOpener.Status s = opener.tick(bot);
				if (s == ContainerOpener.Status.FAILED) nextChest(p);
				else if (s == ContainerOpener.Status.OPEN) {
					if (p.containerMenu instanceof ChestMenu) phase = Phase.FILL;
					else nextChest(p);
				}
			}
			case FILL -> {
				if (!(p.containerMenu instanceof ChestMenu menu)) {
					phase = Phase.APPROACH;
					return Status.RUNNING;
				}
				if (!moveOne(p, menu)) {
					p.closeContainer();
					if (carried(p) == 0) return Status.DONE;
					nextChest(p); // full
				}
			}
		}
		return Status.RUNNING;
	}

	private boolean moveOne(LocalPlayer p, ChestMenu menu) {
		for (int inv = 0; inv < InvUtil.MAIN_SIZE; inv++) {
			ItemStack stack = p.getInventory().getItem(inv);
			if (stack.isEmpty() || !items.contains(stack.getItem())) continue;
			int slot = InvUtil.menuSlot(menu, p, inv);
			if (slot < 0) continue;
			Item item = stack.getItem();
			int before = stack.getCount();
			InvUtil.quickMove(p, slot);
			int moved = before - p.getInventory().getItem(inv).getCount();
			if (moved <= 0) return false;
			delivered.merge(item, moved, Integer::sum);
			return true;
		}
		return false;
	}

	private int carried(LocalPlayer p) {
		return InvUtil.count(p, s -> items.contains(s.getItem()));
	}

	private void nextChest(LocalPlayer p) {
		if (!InvUtil.inventoryMenuActive(p)) p.closeContainer();
		chestIndex++;
		opener = null;
		phase = Phase.APPROACH;
	}

	/** Empty cell with solid ground under it next to the depot. */
	private @Nullable BlockPos chestSpot(ClientLevel level) {
		for (int r = 0; r <= 2; r++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dx = -r; dx <= r; dx++) {
					for (int dz = -r; dz <= r; dz++) {
						if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
						BlockPos pos = depot.offset(dx, dy, dz);
						BlockPos below = pos.below();
						if (level.getBlockState(pos).canBeReplaced() && level.getBlockState(pos).getFluidState().isEmpty()
								&& BlockUtil.canStandOn(level, below, level.getBlockState(below))) {
							return pos;
						}
					}
				}
			}
		}
		return null;
	}
}
