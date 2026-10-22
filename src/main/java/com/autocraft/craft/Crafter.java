package com.autocraft.craft;

import com.autocraft.Bot;
import com.autocraft.control.InvUtil;
import com.autocraft.craft.RecipeBook.Recipe;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;

/**
 * Crafts one batch of a recipe by clicking ingredients into the grid: the player's own 2x2 grid when it
 * fits (no screen needed), otherwise a crafting table. The result slot is server-authoritative, so we
 * wait for it before shift-clicking it out.
 */
public final class Crafter {
	public enum Status { RUNNING, DONE, FAILED }

	private static final int RESULT_SLOT = 0;
	private static final int RESULT_TIMEOUT = 40;

	private final Recipe recipe;
	private @Nullable Station station;
	private boolean filled;
	private int waitTicks;

	public Crafter(Recipe recipe) {
		this.recipe = recipe;
	}

	public Recipe recipe() {
		return recipe;
	}

	/** Crafting table we placed for this craft (pick it up when crafting is over), or null. */
	public @Nullable BlockPos placedTable() {
		return station == null ? null : station.placed();
	}

	public Status tick(Bot bot) {
		LocalPlayer p = bot.player();
		AbstractContainerMenu menu = p.containerMenu;
		int width;
		if (menu instanceof CraftingMenu) {
			width = 3;
		} else if (recipe.fits2x2() && menu == p.inventoryMenu) {
			width = 2;
		} else if (recipe.fits2x2()) {
			p.closeContainer(); // some unrelated screen; the 2x2 grid needs the plain inventory
			return Status.RUNNING;
		} else {
			if (station == null) station = new Station(Blocks.CRAFTING_TABLE, Items.CRAFTING_TABLE, CraftingMenu.class);
			Station.Status s = station.tick(bot);
			if (s == Station.Status.FAILED) return Status.FAILED;
			if (s == Station.Status.WORKING) return Status.RUNNING;
			return Status.RUNNING; // menu just opened: fill on the next tick
		}

		if (!filled) {
			clearGrid(p, menu, width);
			if (!fill(p, menu, width)) {
				clearGrid(p, menu, width);
				return Status.FAILED;
			}
			filled = true;
			waitTicks = 0;
			return Status.RUNNING;
		}
		ItemStack result = menu.getSlot(RESULT_SLOT).getItem();
		if (!result.isEmpty() && result.is(recipe.output())) {
			InvUtil.quickMove(p, RESULT_SLOT);
			clearGrid(p, menu, width);
			return Status.DONE;
		}
		if (++waitTicks > RESULT_TIMEOUT) {
			clearGrid(p, menu, width);
			return Status.FAILED;
		}
		return Status.RUNNING;
	}

	/** Pick up a stack, right-click one item into the grid cell, put the rest back — for every cell. */
	private boolean fill(LocalPlayer p, AbstractContainerMenu menu, int width) {
		for (int row = 0; row < recipe.pattern().size(); row++) {
			String line = recipe.pattern().get(row);
			for (int col = 0; col < line.length(); col++) {
				char c = line.charAt(col);
				if (c == ' ') continue;
				int inv = InvUtil.find(p, recipe.matcher(recipe.key().get(c)));
				if (inv < 0) return false;
				int source = InvUtil.menuSlot(menu, p, inv);
				int grid = 1 + row * width + col;
				InvUtil.click(p, source, 0, ContainerInput.PICKUP);
				InvUtil.click(p, grid, 1, ContainerInput.PICKUP);
				if (!menu.getCarried().isEmpty()) InvUtil.click(p, source, 0, ContainerInput.PICKUP);
			}
		}
		return true;
	}

	private static void clearGrid(LocalPlayer p, AbstractContainerMenu menu, int width) {
		for (int i = 1; i <= width * width; i++) {
			if (!menu.getSlot(i).getItem().isEmpty()) InvUtil.quickMove(p, i);
		}
	}
}
