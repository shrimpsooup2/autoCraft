package com.autocraft.build;

import com.autocraft.Bot;
import com.autocraft.Chat;
import com.autocraft.control.ContainerOpener;
import com.autocraft.control.InvUtil;
import com.autocraft.path.Goal;
import com.autocraft.path.Navigator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import org.jspecify.annotations.Nullable;

/**
 * Takes materials out of the chests and barrels around the base before mining or crafting them — the easy way
 * to supply blocks the bot can't make itself (put them in a base chest). Either specific items and counts, or
 * any items matching a predicate up to an amount.
 */
public final class BaseWithdraw {
	public enum Status { RUNNING, DONE }

	/** Containers this close to the base count as base storage. */
	public static final int BASE_RADIUS = 8;

	private final BlockPos base;
	private final @Nullable Map<Item, Integer> wanted;
	private final Predicate<ItemStack> match;
	private int remaining;
	private final List<BlockPos> chests = new ArrayList<>();
	private int chestIndex = -1;
	private @Nullable ContainerOpener opener;
	private int taken;

	public BaseWithdraw(BlockPos base, Map<Item, Integer> wanted) {
		this.base = base.immutable();
		this.wanted = new HashMap<>(wanted);
		this.match = s -> false;
	}

	public BaseWithdraw(BlockPos base, Predicate<ItemStack> match, int amount) {
		this.base = base.immutable();
		this.wanted = null;
		this.match = match;
		this.remaining = amount;
	}

	private boolean satisfied() {
		return wanted != null ? wanted.values().stream().allMatch(n -> n <= 0) : remaining <= 0;
	}

	public Status tick(Bot bot) {
		LocalPlayer p = bot.player();
		if (chestIndex < 0) {
			bot.setStatus("fetching materials from base");
			if (bot.startFlight(base)) return Status.RUNNING;
			bot.nav().setGoal(new Goal.Near(base, 3));
			Navigator.Status s = bot.nav().tick();
			if (s == Navigator.Status.FAILED) return Status.DONE;
			if (s != Navigator.Status.ARRIVED) return Status.RUNNING;
			bot.nav().stop();
			chests.addAll(bot.scanner().findBlocks(st -> st.getBlock() instanceof ChestBlock || st.getBlock() instanceof BarrelBlock, 1, 64));
			chests.removeIf(c -> c.distSqr(base) > BASE_RADIUS * BASE_RADIUS);
			chestIndex = 0;
		}
		if (chestIndex >= chests.size() || satisfied()) return finish(p);
		BlockPos chest = chests.get(chestIndex);
		if (!(p.containerMenu instanceof ChestMenu menu)) {
			if (!p.isWithinBlockInteractionRange(chest, -0.5)) {
				bot.nav().setGoal(new Goal.Adjacent(chest));
				if (bot.nav().tick() == Navigator.Status.FAILED) nextChest(p);
				return Status.RUNNING;
			}
			bot.nav().stop();
			if (opener == null) opener = new ContainerOpener(chest, ChestMenu.class);
			if (opener.tick(bot) == ContainerOpener.Status.FAILED) nextChest(p);
			return Status.RUNNING;
		}
		if (!takeOne(p, menu)) nextChest(p);
		return Status.RUNNING;
	}

	/** Shift-click one wanted stack out of the chest; false when this chest has nothing more for us. */
	private boolean takeOne(LocalPlayer p, ChestMenu menu) {
		int size = menu.getRowCount() * 9;
		for (int slot = 0; slot < size; slot++) {
			ItemStack s = menu.getSlot(slot).getItem();
			if (s.isEmpty() || (wanted != null ? wanted.getOrDefault(s.getItem(), 0) <= 0 : remaining <= 0 || !match.test(s))) continue;
			int before = InvUtil.count(p, x -> x.is(s.getItem()));
			Item item = s.getItem();
			InvUtil.quickMove(p, slot);
			int got = InvUtil.count(p, x -> x.is(item)) - before;
			if (got <= 0) return false; // inventory full
			if (wanted != null) wanted.merge(item, -got, Integer::sum);
			else remaining -= got;
			taken += got;
			return true;
		}
		return false;
	}

	private void nextChest(LocalPlayer p) {
		if (!InvUtil.inventoryMenuActive(p)) p.closeContainer();
		chestIndex++;
		opener = null;
	}

	private Status finish(LocalPlayer p) {
		if (!InvUtil.inventoryMenuActive(p)) p.closeContainer();
		if (taken > 0) Chat.info("Took " + taken + " items from base chests.");
		return Status.DONE;
	}
}
