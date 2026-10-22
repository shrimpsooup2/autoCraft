package com.autocraft.craft;

import com.autocraft.Bot;
import com.autocraft.control.InvUtil;
import java.util.function.Predicate;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;

/**
 * Smelts {@code count} items in a furnace: load input and just enough fuel, collect output as it appears,
 * take leftover fuel back. The furnace keeps cooking server-side if a fight interrupts us.
 */
public final class Smelter {
	public enum Status { RUNNING, DONE, FAILED }

	private static final int INPUT = AbstractFurnaceMenu.INGREDIENT_SLOT;
	private static final int FUEL = AbstractFurnaceMenu.FUEL_SLOT;
	private static final int OUTPUT = AbstractFurnaceMenu.RESULT_SLOT;
	private static final int TICKS_PER_ITEM = 200;
	private static final Predicate<ItemStack> COAL = s -> s.is(ItemTags.COALS);
	private static final Predicate<ItemStack> WOOD = s -> s.is(ItemTags.LOGS) || s.is(ItemTags.PLANKS);

	private final Item input;
	private final int count;
	private final Station station = new Station(Blocks.FURNACE, Items.FURNACE, FurnaceMenu.class);
	private boolean loaded;
	private int ticks;

	public Smelter(Item input, int count) {
		this.input = input;
		this.count = count;
	}

	/** Items worth of smelting the carried fuel can do (coal 8, wood 1.5). */
	public static int fuelValue(Player p) {
		return InvUtil.count(p, COAL) * 8 + InvUtil.count(p, WOOD) * 3 / 2;
	}

	public @Nullable BlockPos placedFurnace() {
		return station.placed();
	}

	public Status tick(Bot bot) {
		LocalPlayer p = bot.player();
		if (!(p.containerMenu instanceof AbstractFurnaceMenu menu)) {
			return switch (station.tick(bot)) {
				case FAILED -> Status.FAILED;
				case OPEN, WORKING -> Status.RUNNING;
			};
		}
		if (++ticks > count * (TICKS_PER_ITEM + 20) + 400) {
			p.closeContainer();
			return Status.FAILED;
		}
		if (!menu.getSlot(OUTPUT).getItem().isEmpty()) InvUtil.quickMove(p, OUTPUT);
		ItemStack in = menu.getSlot(INPUT).getItem();
		if (!loaded) {
			if (in.isEmpty() && !put(p, menu, INPUT, s -> s.is(input), count)) {
				p.closeContainer();
				return Status.FAILED;
			}
			loaded = true;
			return Status.RUNNING;
		}
		if (in.isEmpty() && menu.getSlot(OUTPUT).getItem().isEmpty()) {
			if (!menu.getSlot(FUEL).getItem().isEmpty()) InvUtil.quickMove(p, FUEL);
			p.closeContainer();
			return Status.DONE;
		}
		if (menu.getSlot(FUEL).getItem().isEmpty() && !menu.isLit()) {
			int remaining = in.getCount();
			boolean fueled = put(p, menu, FUEL, COAL, (remaining + 7) / 8) || put(p, menu, FUEL, WOOD, (remaining * 2 + 2) / 3);
			if (!fueled) {
				p.closeContainer();
				return Status.FAILED;
			}
		}
		bot.setStatus("smelting " + in.getCount() + " " + in.getHoverName().getString());
		return Status.RUNNING;
	}

	/** Move exactly {@code amount} matching items (one stack's worth at most) into a furnace slot. */
	private static boolean put(LocalPlayer p, AbstractFurnaceMenu menu, int slot, Predicate<ItemStack> what, int amount) {
		int inv = InvUtil.find(p, what);
		if (inv < 0) return false;
		int source = InvUtil.menuSlot(menu, p, inv);
		InvUtil.click(p, source, 0, ContainerInput.PICKUP);
		int n = Math.min(amount, menu.getCarried().getCount());
		for (int i = 0; i < n; i++) InvUtil.click(p, slot, 1, ContainerInput.PICKUP);
		if (!menu.getCarried().isEmpty()) InvUtil.click(p, source, 0, ContainerInput.PICKUP);
		return true;
	}
}
