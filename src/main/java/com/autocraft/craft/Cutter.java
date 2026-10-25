package com.autocraft.craft;

import com.autocraft.Bot;
import com.autocraft.control.InvUtil;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.SelectableRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;

/**
 * Cuts on a stonecutter: put exactly the needed number of input items in, press the button of the wanted
 * result, shift-click the result out (that cuts the whole input). The recipe list and the result come from the
 * server, so each step waits for them.
 */
public final class Cutter {
	public enum Status { RUNNING, DONE, FAILED }

	private static final int TIMEOUT = 40;

	private final Item input;
	private final Item output;
	private final int cuts;
	private @Nullable Station station;
	private int phase;
	private int waitTicks;

	public Cutter(Item input, Item output, int cuts) {
		this.input = input;
		this.output = output;
		this.cuts = cuts;
	}

	public Item output() {
		return output;
	}

	/** Stonecutter we placed for this (pick it up when work here is over), or null. */
	public @Nullable BlockPos placedCutter() {
		return station == null ? null : station.placed();
	}

	public Status tick(Bot bot) {
		LocalPlayer p = bot.player();
		if (!(p.containerMenu instanceof StonecutterMenu menu)) {
			if (station == null) station = new Station(Blocks.STONECUTTER, Items.STONECUTTER, StonecutterMenu.class);
			return station.tick(bot) == Station.Status.FAILED ? Status.FAILED : Status.RUNNING;
		}
		switch (phase) {
			case 0 -> {
				if (!menu.getSlot(StonecutterMenu.INPUT_SLOT).getItem().isEmpty()) InvUtil.quickMove(p, StonecutterMenu.INPUT_SLOT);
				int inv = InvUtil.find(p, s -> s.is(input));
				if (inv < 0) return Status.FAILED;
				int source = InvUtil.menuSlot(menu, p, inv);
				int n = Math.min(cuts, p.getInventory().getItem(inv).getCount());
				InvUtil.click(p, source, 0, ContainerInput.PICKUP);
				for (int i = 0; i < n; i++) InvUtil.click(p, StonecutterMenu.INPUT_SLOT, 1, ContainerInput.PICKUP);
				if (!menu.getCarried().isEmpty()) InvUtil.click(p, source, 0, ContainerInput.PICKUP);
				next();
			}
			case 1 -> {
				int index = recipeIndex(bot, menu);
				if (index < 0) return waitOrFail(p, menu); // the recipe list follows the input from the server
				if (menu.getSelectedRecipeIndex() != index && menu.clickMenuButton(p, index)) {
					bot.mc().gameMode.handleInventoryButtonClick(menu.containerId, index);
				}
				next();
			}
			default -> {
				if (menu.getSlot(StonecutterMenu.RESULT_SLOT).getItem().is(output)) {
					InvUtil.quickMove(p, StonecutterMenu.RESULT_SLOT);
					if (!menu.getSlot(StonecutterMenu.INPUT_SLOT).getItem().isEmpty()) InvUtil.quickMove(p, StonecutterMenu.INPUT_SLOT);
					return Status.DONE;
				}
				return waitOrFail(p, menu);
			}
		}
		bot.setStatus("cutting " + output.getDefaultInstance().getHoverName().getString());
		return Status.RUNNING;
	}

	private void next() {
		phase++;
		waitTicks = 0;
	}

	private Status waitOrFail(LocalPlayer p, StonecutterMenu menu) {
		if (++waitTicks <= TIMEOUT) return Status.RUNNING;
		if (!menu.getSlot(StonecutterMenu.INPUT_SLOT).getItem().isEmpty()) InvUtil.quickMove(p, StonecutterMenu.INPUT_SLOT);
		return Status.FAILED;
	}

	private int recipeIndex(Bot bot, StonecutterMenu menu) {
		ContextMap context = SlotDisplayContext.fromLevel(bot.level());
		List<SelectableRecipe.SingleInputEntry<StonecutterRecipe>> entries = menu.getVisibleRecipes().entries();
		for (int i = 0; i < entries.size(); i++) {
			if (entries.get(i).recipe().optionDisplay().resolveForFirstStack(context).is(output)) return i;
		}
		return -1;
	}
}
