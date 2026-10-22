package com.autocraft.behavior;

import com.autocraft.Bot;
import com.autocraft.Chat;
import com.autocraft.control.ContainerOpener;
import com.autocraft.control.InvUtil;
import com.autocraft.control.Placement;
import com.autocraft.goal.Job;
import com.autocraft.goal.Requirement;
import com.autocraft.path.Goal;
import com.autocraft.path.Navigator;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import org.jspecify.annotations.Nullable;

/**
 * Keeps room in the inventory. When nearly full: drop junk, stuff job items into bundles, empty them into
 * a carried shulker box, or haul them to the base chests (and walk back). Last resort: drop the least
 * valuable stacks; if everything is job loot with nowhere to go, stop.
 */
public final class StorageBehavior implements Behavior {
	private static final int THROWAWAY_KEEP = 128;
	private static final int RETRY_COOLDOWN = 20 * 60;
	private static final Set<Item> KEEP = Set.of(
			Items.STICK, Items.COAL, Items.CHARCOAL, Items.RAW_IRON, Items.IRON_INGOT, Items.RAW_GOLD, Items.GOLD_INGOT,
			Items.DIAMOND, Items.FLINT, Items.OBSIDIAN, Items.CRAFTING_TABLE, Items.FURNACE, Items.TORCH, Items.MILK_BUCKET,
			Items.TOTEM_OF_UNDYING, Items.ENDER_PEARL, Items.ARROW, Items.HONEY_BOTTLE, Items.FIREWORK_ROCKET);

	private enum Mode { IDLE, SHULKER, BASE, RETURN }

	private enum ShulkerPhase { PLACE, OPEN, FILL, PICKUP }

	private Mode mode = Mode.IDLE;
	private ShulkerPhase shulkerPhase = ShulkerPhase.PLACE;
	private @Nullable BlockPos shulkerPos;
	private @Nullable ContainerOpener opener;
	private @Nullable Harvest pickup;
	private @Nullable BaseDeposit deposit;
	private @Nullable BlockPos returnPos;
	private int modeTicks;
	private long tick;
	private long shulkerRetryAt;
	private long baseRetryAt;
	private boolean bundlesFull;

	@Override
	public String name() {
		return "storage";
	}

	@Override
	public void reset() {
		mode = Mode.IDLE;
		deposit = null;
		pickup = null;
		opener = null;
		bundlesFull = false;
	}

	@Override
	public boolean tick(Bot bot) {
		tick++;
		Job job = bot.job();
		if (job == null) return false;
		LocalPlayer p = bot.player();
		switch (mode) {
			case SHULKER -> {
				return tickShulker(bot, job);
			}
			case BASE -> {
				BaseDeposit.Status s = deposit.tick(bot);
				if (s == BaseDeposit.Status.RUNNING) return true;
				if (s == BaseDeposit.Status.FAILED) baseRetryAt = tick + RETRY_COOLDOWN;
				mode = s == BaseDeposit.Status.DONE && returnPos != null ? Mode.RETURN : Mode.IDLE;
				bot.nav().stop();
				return true;
			}
			case RETURN -> {
				bot.setStatus("walking back to work");
				bot.nav().setGoal(new Goal.Near(returnPos, 2));
				if (bot.nav().tick() != Navigator.Status.MOVING) {
					mode = Mode.IDLE;
					bot.nav().stop();
				}
				return true;
			}
			case IDLE -> {
			}
		}

		if (InvUtil.freeSlots(p) > 1) return false;
		if (!InvUtil.inventoryMenuActive(p)) p.closeContainer();
		int junk = findJunk(bot);
		if (junk >= 0) {
			bot.setStatus("dropping " + p.getInventory().getItem(junk).getHoverName().getString());
			InvUtil.dropStack(p, junk);
			return true;
		}
		if (stuffBundle(p, job)) return true;
		if (tick >= shulkerRetryAt && InvUtil.find(p, StorageBehavior::shulkerWithSpace) >= 0 && hasJobItems(p, job)) {
			mode = Mode.SHULKER;
			shulkerPhase = ShulkerPhase.PLACE;
			modeTicks = 0;
			return true;
		}
		if (bot.base() != null && bot.baseDimension() == bot.dimension() && tick >= baseRetryAt) {
			deposit = new BaseDeposit(bot.base());
			returnPos = Navigator.feet(p);
			mode = Mode.BASE;
			Chat.info("Inventory full, heading to base.");
			return true;
		}
		int cheap = leastValuable(bot);
		if (cheap >= 0) {
			InvUtil.dropStack(p, cheap);
			return true;
		}
		Chat.warn("Inventory is full of job items and there is nowhere to store them.");
		bot.stop("inventory full");
		return true;
	}

	// ---- junk -----------------------------------------------------------------------------------

	/** Worth carrying: job/need items, gear, food, containers, crafting and portal materials. */
	public static boolean isKeeper(Bot bot, ItemStack s) {
		Job job = bot.job();
		if (job != null && !job.matching(s).isEmpty()) return true;
		for (Bot.NeedOwner owner : Bot.NeedOwner.values()) {
			Requirement need = bot.need(owner);
			if (need != null && need.counts().test(s)) return true;
		}
		if (s.isDamageableItem() || s.has(DataComponents.FOOD)) return true;
		Item item = s.getItem();
		if (item instanceof BoatItem || item instanceof BundleItem || item instanceof BucketItem || isShulker(s)) return true;
		return s.is(ItemTags.LOGS) || s.is(ItemTags.PLANKS) || KEEP.contains(item);
	}

	/** A stack to drop: non-keeper items first, then surplus spare scaffolding. Job loot is never junk. */
	private static int findJunk(Bot bot) {
		LocalPlayer p = bot.player();
		Predicate<ItemStack> spareBlocks = s -> InvUtil.isThrowaway(s) && !bot.isJobItem(s);
		int throwaway = InvUtil.count(p, spareBlocks);
		int smallestThrowaway = -1;
		for (int i = 0; i < InvUtil.MAIN_SIZE; i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (s.isEmpty() || bot.isJobItem(s)) continue;
			if (spareBlocks.test(s)) {
				if (smallestThrowaway < 0 || s.getCount() < p.getInventory().getItem(smallestThrowaway).getCount()) smallestThrowaway = i;
				continue;
			}
			if (!isKeeper(bot, s)) return i;
		}
		if (throwaway > THROWAWAY_KEEP && smallestThrowaway >= 0
				&& throwaway - p.getInventory().getItem(smallestThrowaway).getCount() >= THROWAWAY_KEEP / 2) {
			return smallestThrowaway;
		}
		return -1;
	}

	/** Last resort before giving up: throwaway blocks, then plain crafting materials. Never gear, food or job loot. */
	private static int leastValuable(Bot bot) {
		LocalPlayer p = bot.player();
		Job job = bot.job();
		int fallback = -1;
		for (int i = 0; i < InvUtil.MAIN_SIZE; i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (s.isEmpty() || job != null && !job.matching(s).isEmpty()) continue;
			if (InvUtil.isThrowaway(s)) return i;
			if (s.isDamageableItem() || s.has(DataComponents.FOOD) || isShulker(s) || s.getItem() instanceof BundleItem) continue;
			if (fallback < 0) fallback = i;
		}
		return fallback;
	}

	private static boolean hasJobItems(LocalPlayer p, Job job) {
		for (Requirement r : job.requirements()) {
			if (InvUtil.count(p, r.counts()) > 0) return true;
		}
		return false;
	}

	// ---- bundles --------------------------------------------------------------------------------

	/** Carry a bundle onto a job stack (left click) so it absorbs as much as fits. */
	private boolean stuffBundle(LocalPlayer p, Job job) {
		if (bundlesFull) return false;
		int bundle = InvUtil.find(p, s -> s.getItem() instanceof BundleItem && BundleItem.getFullnessDisplay(s) < 1.0F);
		if (bundle < 0) return false;
		int loot = InvUtil.find(p, s -> !(s.getItem() instanceof BundleItem) && !isShulker(s) && !job.matching(s).isEmpty());
		if (loot < 0) return false;
		int bundleSlot = InvUtil.menuSlot(p.inventoryMenu, p, bundle);
		int lootSlot = InvUtil.menuSlot(p.inventoryMenu, p, loot);
		float before = BundleItem.getFullnessDisplay(p.getInventory().getItem(bundle));
		InvUtil.click(p, bundleSlot, 0, ContainerInput.PICKUP);
		InvUtil.click(p, lootSlot, 0, ContainerInput.PICKUP);
		InvUtil.click(p, bundleSlot, 0, ContainerInput.PICKUP);
		float after = BundleItem.getFullnessDisplay(p.getInventory().getItem(bundle));
		if (after <= before) bundlesFull = true; // nothing fit; don't loop on it
		return true;
	}

	// ---- shulker boxes --------------------------------------------------------------------------

	static boolean isShulker(ItemStack s) {
		return Block.byItem(s.getItem()) instanceof ShulkerBoxBlock;
	}

	private static boolean shulkerWithSpace(ItemStack s) {
		if (!isShulker(s)) return false;
		ItemContainerContents contents = s.get(DataComponents.CONTAINER);
		return contents == null || contents.nonEmptyItemCopyStream().count() < 27;
	}

	private boolean tickShulker(Bot bot, Job job) {
		LocalPlayer p = bot.player();
		modeTicks++;
		bot.setStatus("filling shulker box");
		switch (shulkerPhase) {
			case PLACE -> {
				if (shulkerPos != null && bot.mc().level.getBlockState(shulkerPos).getBlock() instanceof ShulkerBoxBlock) {
					opener = new ContainerOpener(shulkerPos, ShulkerBoxMenu.class);
					shulkerPhase = ShulkerPhase.OPEN;
					return true;
				}
				if (shulkerPos == null) {
					shulkerPos = Placement.besideFeet(bot, StorageBehavior::shulkerWithSpace);
					if (shulkerPos == null) return abandonShulker();
				} else if (modeTicks > 20) {
					return abandonShulker(); // placement never showed up
				}
			}
			case OPEN -> {
				ContainerOpener.Status s = opener.tick(bot);
				if (s == ContainerOpener.Status.FAILED) shulkerPhase = ShulkerPhase.PICKUP;
				else if (s == ContainerOpener.Status.OPEN) shulkerPhase = ShulkerPhase.FILL;
			}
			case FILL -> {
				if (!(p.containerMenu instanceof ShulkerBoxMenu menu) || !moveOneInto(p, menu, job)) {
					if (!InvUtil.inventoryMenuActive(p)) p.closeContainer();
					pickup = new Harvest(shulkerPos, StorageBehavior::isShulker);
					shulkerPhase = ShulkerPhase.PICKUP;
				}
			}
			case PICKUP -> {
				if (pickup == null) pickup = new Harvest(shulkerPos, StorageBehavior::isShulker);
				if (pickup.tick(bot) == Harvest.Status.RUNNING) return true;
				mode = Mode.IDLE;
				pickup = null;
				shulkerPos = null;
			}
		}
		return true;
	}

	private boolean abandonShulker() {
		shulkerRetryAt = tick + RETRY_COOLDOWN;
		mode = Mode.IDLE;
		shulkerPos = null;
		return true;
	}

	private static boolean moveOneInto(LocalPlayer p, ShulkerBoxMenu menu, Job job) {
		for (int inv = 0; inv < InvUtil.MAIN_SIZE; inv++) {
			ItemStack s = p.getInventory().getItem(inv);
			if (s.isEmpty() || isShulker(s) || job.matching(s).isEmpty()) continue;
			int slot = InvUtil.menuSlot(menu, p, inv);
			if (slot < 0) continue;
			int before = s.getCount();
			InvUtil.quickMove(p, slot);
			return p.getInventory().getItem(inv).getCount() < before;
		}
		return false;
	}
}
