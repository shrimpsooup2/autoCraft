package com.autocraft.control;

import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Inventory helpers. "Inventory index" = {@link Inventory#getItem} index: 0-8 hotbar, 9-35 main,
 * {@link Inventory#SLOT_OFFHAND} offhand. "Menu slot" = index into the open container menu's slot list.
 */
public final class InvUtil {
	public static final int MAIN_SIZE = 36;
	/** Cheap blocks the bot may place for pillaring/bridging and should keep a stack of. */
	private static final Set<Item> THROWAWAY = Set.of(
			Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.DIRT, Items.NETHERRACK, Items.STONE,
			Items.ANDESITE, Items.DIORITE, Items.GRANITE, Items.TUFF, Items.BLACKSTONE);
	/** Hotbar slot the bot swaps items into when they are not already on the hotbar. */
	private static final int SCRATCH_HOTBAR = 8;

	private InvUtil() {}

	public static boolean isThrowaway(ItemStack s) {
		return THROWAWAY.contains(s.getItem());
	}

	/**
	 * What to build pillars/bridges/frame corners with: throwaway blocks that {@code keep} doesn't protect
	 * (job loot), falling back to any throwaway block when nothing else is carried.
	 */
	public static Predicate<ItemStack> scaffold(Player p, Predicate<ItemStack> keep) {
		Predicate<ItemStack> spare = s -> isThrowaway(s) && !keep.test(s);
		return find(p, spare) >= 0 ? spare : InvUtil::isThrowaway;
	}

	public static ItemStack get(Player p, int invIndex) {
		return p.getInventory().getItem(invIndex);
	}

	/** First inventory index (hotbar first) whose stack matches, or -1. */
	public static int find(Player p, Predicate<ItemStack> pred) {
		Inventory inv = p.getInventory();
		for (int i = 0; i < MAIN_SIZE; i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && pred.test(s)) return i;
		}
		return -1;
	}

	/** Items matching in the main inventory and offhand. */
	public static int count(Player p, Predicate<ItemStack> pred) {
		Inventory inv = p.getInventory();
		int n = 0;
		for (int i = 0; i < MAIN_SIZE; i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && pred.test(s)) n += s.getCount();
		}
		ItemStack off = inv.getItem(Inventory.SLOT_OFFHAND);
		if (!off.isEmpty() && pred.test(off)) n += off.getCount();
		return n;
	}

	/** Like {@link #count} but also looks inside carried shulker boxes and bundles. */
	public static int countIncludingContainers(Player p, Predicate<ItemStack> pred) {
		Inventory inv = p.getInventory();
		int n = count(p, pred);
		for (int i = 0; i < MAIN_SIZE; i++) {
			ItemStack s = inv.getItem(i);
			ItemContainerContents box = s.get(DataComponents.CONTAINER);
			if (box != null) {
				n += box.itemCopies().filter(c -> !c.isEmpty() && pred.test(c)).mapToInt(ItemStack::getCount).sum();
			}
			BundleContents bundle = s.get(DataComponents.BUNDLE_CONTENTS);
			if (bundle != null) {
				n += bundle.itemCopies().filter(c -> !c.isEmpty() && pred.test(c)).mapToInt(ItemStack::getCount).sum();
			}
		}
		return n;
	}

	public static int freeSlots(Player p) {
		Inventory inv = p.getInventory();
		int n = 0;
		for (int i = 0; i < MAIN_SIZE; i++) {
			if (inv.getItem(i).isEmpty()) n++;
		}
		return n;
	}

	/** No container screen is open, so the player's own inventory menu accepts clicks. */
	public static boolean inventoryMenuActive(Player p) {
		return p.containerMenu == p.inventoryMenu;
	}

	/**
	 * Put a matching stack in the main hand. Hotbar items are selected directly; others are swapped into
	 * the scratch hotbar slot (only possible while no container is open). Returns whether the main hand
	 * now holds a matching item.
	 */
	public static boolean select(Player p, Predicate<ItemStack> pred) {
		Inventory inv = p.getInventory();
		ItemStack held = inv.getSelectedItem();
		if (!held.isEmpty() && pred.test(held)) return true;
		int idx = find(p, pred);
		if (idx < 0) return false;
		if (Inventory.isHotbarSlot(idx)) {
			inv.setSelectedSlot(idx);
			return true;
		}
		if (!inventoryMenuActive(p)) return false;
		int target = emptyHotbarSlot(p);
		if (target < 0) target = SCRATCH_HOTBAR;
		swapWithHotbar(p, idx, target);
		inv.setSelectedSlot(target);
		return true;
	}

	public static int emptyHotbarSlot(Player p) {
		for (int i = 0; i < Inventory.getSelectionSize(); i++) {
			if (p.getInventory().getItem(i).isEmpty()) return i;
		}
		return -1;
	}

	/** Swap inventory index {@code invIndex} with hotbar slot {@code hotbar} (0-8) or 40 for offhand. */
	public static void swapWithHotbar(Player p, int invIndex, int hotbar) {
		click(p, menuSlot(p.containerMenu, p, invIndex), hotbar, ContainerInput.SWAP);
	}

	public static void moveToOffhand(Player p, int invIndex) {
		swapWithHotbar(p, invIndex, Inventory.SLOT_OFFHAND);
	}

	/** Drop the whole stack at {@code invIndex}. */
	public static void dropStack(Player p, int invIndex) {
		click(p, menuSlot(p.containerMenu, p, invIndex), 1, ContainerInput.THROW);
	}

	/** Shift-click: move a stack between the player inventory and the open container. */
	public static void quickMove(Player p, int menuSlot) {
		click(p, menuSlot, 0, ContainerInput.QUICK_MOVE);
	}

	public static void click(Player p, int menuSlot, int button, ContainerInput type) {
		Minecraft.getInstance().gameMode.handleContainerInput(p.containerMenu.containerId, menuSlot, button, type, p);
	}

	/** Menu slot showing player inventory index {@code invIndex} in {@code menu}, or -1. */
	public static int menuSlot(AbstractContainerMenu menu, Player p, int invIndex) {
		for (int i = 0; i < menu.slots.size(); i++) {
			Slot s = menu.slots.get(i);
			if (s.container == p.getInventory() && s.getContainerSlot() == invIndex) return i;
		}
		return -1;
	}

	// ---- tools ----------------------------------------------------------------------------------

	/** Best mining speed available for {@code state} from inventory items that can harvest it (1 = bare hand). */
	public static float bestSpeed(Player p, BlockState state) {
		int idx = bestToolIndex(p, state);
		return idx < 0 ? 1.0F : get(p, idx).getDestroySpeed(state);
	}

	/** Inventory index of the fastest tool for {@code state} that still gets drops, or -1 if hand is as good. */
	public static int bestToolIndex(Player p, BlockState state) {
		Inventory inv = p.getInventory();
		boolean needsTool = state.requiresCorrectToolForDrops();
		int best = -1;
		float bestSpeed = 1.0F;
		for (int i = 0; i < MAIN_SIZE; i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty()) continue;
			if (needsTool && !s.isCorrectToolForDrops(state)) continue;
			if (nearlyBroken(s)) continue;
			float speed = s.getDestroySpeed(state);
			if (speed > bestSpeed) {
				bestSpeed = speed;
				best = i;
			}
		}
		return best;
	}

	public static boolean canHarvest(Player p, BlockState state) {
		if (!state.requiresCorrectToolForDrops()) return true;
		return find(p, s -> s.isCorrectToolForDrops(state) && !nearlyBroken(s)) >= 0;
	}

	public static void selectBestTool(Player p, BlockState state) {
		int idx = bestToolIndex(p, state);
		if (idx < 0) return;
		ItemStack tool = get(p, idx);
		select(p, s -> s == tool);
	}

	/** Keep a few uses in reserve so tools are never fully destroyed mid-job. */
	public static boolean nearlyBroken(ItemStack s) {
		return s.isDamageableItem() && s.getMaxDamage() - s.getDamageValue() <= 3;
	}
}
