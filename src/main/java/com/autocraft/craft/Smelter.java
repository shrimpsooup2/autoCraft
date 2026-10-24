package com.autocraft.craft;

import com.autocraft.Bot;
import com.autocraft.control.ContainerOpener;
import com.autocraft.control.InvUtil;
import com.autocraft.control.Placement;
import com.autocraft.path.Goal;
import com.autocraft.path.Navigator;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;

/**
 * Smelts {@code count} items, split over several furnaces when the batch is big (the carried furnaces are put
 * down side by side): load each with its share and just enough fuel, wait, collect, take leftover fuel back.
 * Furnaces holding someone else's items are left alone. Furnaces keep cooking server-side during fights.
 */
public final class Smelter {
	public enum Status { RUNNING, DONE, FAILED }

	private enum Phase { SETUP, LOAD, WAIT, COLLECT }

	private static final int INPUT = AbstractFurnaceMenu.INGREDIENT_SLOT;
	private static final int FUEL = AbstractFurnaceMenu.FUEL_SLOT;
	private static final int OUTPUT = AbstractFurnaceMenu.RESULT_SLOT;
	private static final int TICKS_PER_ITEM = 200;
	/** Use another furnace for every this many items (up to {@link #MAX_FURNACES}). */
	private static final int ITEMS_PER_FURNACE = 8;
	public static final int MAX_FURNACES = 4;
	private static final int COLLECT_EVERY = 20 * 8;
	private static final Predicate<ItemStack> COAL = s -> s.is(ItemTags.COALS);
	private static final Predicate<ItemStack> WOOD = s -> s.is(ItemTags.LOGS) || s.is(ItemTags.PLANKS);

	private final Item input;
	private final int count;
	private final List<BlockPos> furnaces = new ArrayList<>();
	private final List<BlockPos> placed = new ArrayList<>();
	private final List<BlockPos> finished = new ArrayList<>();
	private Phase phase = Phase.SETUP;
	private int index;
	private int loaded;
	private @Nullable ContainerOpener opener;
	private @Nullable BlockPos digging;
	private int ticks;
	private int waitTicks;
	private int menuTicks;

	public Smelter(Item input, int count) {
		this.input = input;
		this.count = count;
	}

	/** Items worth of smelting the carried fuel can do (coal 8, wood 1.5). */
	public static int fuelValue(Player p) {
		return InvUtil.count(p, COAL) * 8 + InvUtil.count(p, WOOD) * 3 / 2;
	}

	/** How many furnaces a batch of {@code count} is spread over. */
	public static int furnacesFor(int count) {
		return Math.max(1, Math.min(MAX_FURNACES, count / ITEMS_PER_FURNACE));
	}

	/** Furnaces we put down for this batch (picked up afterwards). */
	public List<BlockPos> placedFurnaces() {
		return placed;
	}

	public Status tick(Bot bot) {
		LocalPlayer p = bot.player();
		if (++ticks > count * (TICKS_PER_ITEM / furnacesFor(count) + 20) + 1200) return close(p, Status.FAILED);
		return switch (phase) {
			case SETUP -> setup(bot, p);
			case LOAD -> visit(bot, p, true);
			case WAIT -> {
				bot.setStatus("smelting " + count + " " + input.getDefaultInstance().getHoverName().getString() + " in " + furnaces.size() + " furnace"
						+ (furnaces.size() == 1 ? "" : "s"));
				if (++waitTicks >= COLLECT_EVERY) {
					waitTicks = 0;
					index = 0;
					phase = Phase.COLLECT;
				}
				yield Status.RUNNING;
			}
			case COLLECT -> visit(bot, p, false);
		};
	}

	/** Pick free furnaces nearby (or at the base) and put down carried ones until there are enough. */
	private Status setup(Bot bot, LocalPlayer p) {
		int wanted = furnacesFor(count);
		if (furnaces.isEmpty() && placed.isEmpty()) {
			BlockPos existing = Stations.nearby(bot, Blocks.FURNACE);
			if (existing != null) furnaces.add(existing);
			// near the base (or with no furnaces of our own) use all its free furnaces for a big batch
			boolean nearBase = bot.base() != null && bot.base().distSqr(p.blockPosition()) <= 24 * 24;
			if (nearBase || InvUtil.count(p, s -> s.is(Items.FURNACE)) == 0) {
				for (BlockPos b : Stations.atBase(bot, Blocks.FURNACE)) {
					if (furnaces.size() < wanted && !furnaces.contains(b)) furnaces.add(b);
				}
			}
		}
		if (furnaces.size() < wanted && InvUtil.count(p, s -> s.is(Items.FURNACE)) > 0) {
			if (!InvUtil.inventoryMenuActive(p)) p.closeContainer();
			bot.nav().stop();
			if (digging != null) {
				if (!bot.controls().mine(digging)) return Status.RUNNING;
				digging = null;
			}
			BlockPos at = Placement.besideFeet(bot, s -> s.is(Items.FURNACE), false);
			if (at != null) {
				furnaces.add(at);
				placed.add(at);
				Stations.notePlaced(bot, at);
				return Status.RUNNING;
			}
			if (furnaces.isEmpty()) {
				digging = Placement.roomToDig(bot);
				return digging == null ? Status.FAILED : Status.RUNNING;
			}
		}
		if (furnaces.isEmpty()) return Status.FAILED;
		index = 0;
		phase = Phase.LOAD;
		return Status.RUNNING;
	}

	/** Walk to and open furnace {@code index}; load it (first round) or collect from it (later rounds). */
	private Status visit(Bot bot, LocalPlayer p, boolean load) {
		if (index >= furnaces.size()) {
			if (!InvUtil.inventoryMenuActive(p)) p.closeContainer();
			if (furnaces.isEmpty()) return Status.FAILED;
			if (!load && finished.size() >= furnaces.size()) return Status.DONE;
			phase = Phase.WAIT;
			return Status.RUNNING;
		}
		BlockPos pos = furnaces.get(index);
		if (finished.contains(pos)) {
			index++;
			return Status.RUNNING;
		}
		if (!bot.level().getBlockState(pos).is(Blocks.FURNACE)) { // broken meanwhile
			furnaces.remove(index);
			return Status.RUNNING;
		}
		if (!(p.containerMenu instanceof AbstractFurnaceMenu menu) || opener == null) {
			if (!p.isWithinBlockInteractionRange(pos, -0.5)) {
				if (!InvUtil.inventoryMenuActive(p)) p.closeContainer();
				bot.nav().setGoal(new Goal.Adjacent(pos));
				if (bot.nav().tick() == Navigator.Status.FAILED) furnaces.remove(index);
				return Status.RUNNING;
			}
			bot.nav().stop();
			if (opener == null) {
				if (!InvUtil.inventoryMenuActive(p)) p.closeContainer();
				opener = new ContainerOpener(pos, AbstractFurnaceMenu.class);
				menuTicks = 0;
			}
			if (opener.tick(bot) == ContainerOpener.Status.FAILED) {
				opener = null;
				furnaces.remove(index);
			}
			return Status.RUNNING;
		}
		if (++menuTicks < 3) return Status.RUNNING; // let the server send the slot contents
		boolean ok = load ? load(p, menu, pos) : collect(p, menu, pos);
		p.closeContainer();
		opener = null;
		if (!ok) {
			Stations.reject(pos);
			furnaces.remove(index);
		} else {
			index++;
		}
		return Status.RUNNING;
	}

	/** Put this furnace's share in with fuel. False when it's busy with something else. */
	private boolean load(LocalPlayer p, AbstractFurnaceMenu menu, BlockPos pos) {
		ItemStack in = menu.getSlot(INPUT).getItem();
		ItemStack out = menu.getSlot(OUTPUT).getItem();
		if (!in.isEmpty() && !in.is(input)) return false;
		if (!out.isEmpty()) InvUtil.quickMove(p, OUTPUT);
		if (!menu.getSlot(OUTPUT).getItem().isEmpty()) return false; // someone else's output we can't take
		int furnacesLeft = furnaces.size() - index;
		int share = (count - loaded + furnacesLeft - 1) / furnacesLeft;
		int carried = InvUtil.count(p, s -> s.is(input));
		int amount = Math.min(share, Math.min(carried, input.getDefaultMaxStackSize() - in.getCount()));
		if (amount > 0 && put(p, menu, INPUT, s -> s.is(input), amount)) loaded += amount;
		int cooking = menu.getSlot(INPUT).getItem().getCount();
		if (cooking > 0 && menu.getSlot(FUEL).getItem().isEmpty() && !menu.isLit()) {
			boolean fueled = put(p, menu, FUEL, COAL, (cooking + 7) / 8) || put(p, menu, FUEL, WOOD, (cooking * 2 + 2) / 3);
			if (!fueled) return true; // no fuel: the planner fetches some; this furnace waits
		}
		return true;
	}

	/** Take smelted items out; a furnace is finished when its input and output are empty. */
	private boolean collect(LocalPlayer p, AbstractFurnaceMenu menu, BlockPos pos) {
		if (!menu.getSlot(OUTPUT).getItem().isEmpty()) InvUtil.quickMove(p, OUTPUT);
		ItemStack in = menu.getSlot(INPUT).getItem();
		if (!in.isEmpty() && !in.is(input)) return false;
		if (in.isEmpty() && menu.getSlot(OUTPUT).getItem().isEmpty()) {
			if (!menu.getSlot(FUEL).getItem().isEmpty()) InvUtil.quickMove(p, FUEL);
			finished.add(pos);
		} else if (menu.getSlot(FUEL).getItem().isEmpty() && !menu.isLit()) {
			int cooking = in.getCount();
			if (!put(p, menu, FUEL, COAL, (cooking + 7) / 8)) put(p, menu, FUEL, WOOD, (cooking * 2 + 2) / 3);
		}
		return true;
	}

	private static Status close(LocalPlayer p, Status s) {
		if (!InvUtil.inventoryMenuActive(p)) p.closeContainer();
		return s;
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
