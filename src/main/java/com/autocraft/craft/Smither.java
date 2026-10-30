package com.autocraft.craft;

import com.autocraft.Bot;
import com.autocraft.control.InvUtil;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;

/**
 * Upgrades on a smithing table: one template, one base item and one addition into their slots, then the result
 * (computed by the server) shift-clicked out. Whatever was put in comes back out if the result never shows.
 */
public final class Smither {
	public enum Status { RUNNING, DONE, FAILED }

	private static final int TIMEOUT = 40;

	private final RecipeBook.Smithing recipe;
	private @Nullable Station station;
	private boolean loaded;
	private int waitTicks;

	public Smither(RecipeBook.Smithing recipe) {
		this.recipe = recipe;
	}

	public RecipeBook.Smithing recipe() {
		return recipe;
	}

	public Status tick(Bot bot) {
		LocalPlayer p = bot.player();
		if (!(p.containerMenu instanceof SmithingMenu menu)) {
			if (station == null) station = new Station(Blocks.SMITHING_TABLE, Items.SMITHING_TABLE, SmithingMenu.class);
			return station.tick(bot) == Station.Status.FAILED ? Status.FAILED : Status.RUNNING;
		}
		bot.setStatus("smithing " + recipe.output().getDefaultInstance().getHoverName().getString());
		if (!loaded) {
			loaded = true;
			if (!put(p, menu, SmithingMenu.TEMPLATE_SLOT, recipe.template()) || !put(p, menu, SmithingMenu.BASE_SLOT, recipe.base())
					|| !put(p, menu, SmithingMenu.ADDITIONAL_SLOT, recipe.addition())) {
				return giveBack(p, menu);
			}
			return Status.RUNNING;
		}
		if (menu.getSlot(SmithingMenu.RESULT_SLOT).getItem().is(recipe.output())) {
			InvUtil.quickMove(p, SmithingMenu.RESULT_SLOT);
			return Status.DONE;
		}
		return ++waitTicks <= TIMEOUT ? Status.RUNNING : giveBack(p, menu);
	}

	/** One matching item from the inventory into an empty slot (what's already there stays if it matches). */
	private static boolean put(LocalPlayer p, SmithingMenu menu, int slot, RecipeBook.Ingredient ing) {
		if (!menu.getSlot(slot).getItem().isEmpty()) {
			if (ing.test(menu.getSlot(slot).getItem())) return true;
			InvUtil.quickMove(p, slot);
		}
		int inv = InvUtil.find(p, ing::test);
		if (inv < 0) return false;
		int source = InvUtil.menuSlot(menu, p, inv);
		InvUtil.click(p, source, 0, ContainerInput.PICKUP);
		InvUtil.click(p, slot, 1, ContainerInput.PICKUP);
		if (!menu.getCarried().isEmpty()) InvUtil.click(p, source, 0, ContainerInput.PICKUP);
		return true;
	}

	private static Status giveBack(LocalPlayer p, SmithingMenu menu) {
		for (int slot : new int[] {SmithingMenu.TEMPLATE_SLOT, SmithingMenu.BASE_SLOT, SmithingMenu.ADDITIONAL_SLOT}) {
			if (!menu.getSlot(slot).getItem().isEmpty()) InvUtil.quickMove(p, slot);
		}
		return Status.FAILED;
	}
}
