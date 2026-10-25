package com.autocraft.craft;

import com.autocraft.control.InvUtil;
import com.autocraft.craft.RecipeBook.Ingredient;
import com.autocraft.craft.RecipeBook.Recipe;
import com.autocraft.goal.Requirement;
import com.autocraft.procedure.Procedure;
import com.autocraft.procedure.Procedures;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * Works out the next concrete action toward carrying some items, using every vanilla crafting and furnace
 * recipe, mining and mob loot, and special procedures: craft something, smelt something, gather (mine or hunt)
 * a raw material, run a procedure (honey, shearing, oxidizing copper, trial chambers...), or nothing (already
 * have it). Routes are compared by a rough effort cost (carried = free, mining a named resource = 8, other
 * natural blocks = 20, hunting = 30, other dimension +40, each craft +1, each smelt +4, procedures their own),
 * so e.g. iron ingots come from smelting raw iron, not from an iron block. Re-run after every action: the
 * inventory is the only state.
 */
public final class Planner {
	public sealed interface Step permits Craft, Smelt, Cut, Gather, Special, Unavailable, Done {}

	public record Craft(Recipe recipe) implements Step {}

	public record Smelt(Item input, int count) implements Step {}

	/** Cut {@code cuts} of {@code input} into {@code output} on a stonecutter. */
	public record Cut(Item input, Item output, int cuts) implements Step {}

	public record Gather(Requirement requirement) implements Step {}

	/** Run a procedure until {@code total} of {@code item} are carried (its needs are already carried). */
	public record Special(Procedure procedure, Item item, int total) implements Step {}

	/** Needs something the bot can't get any way it knows (loot only, the End). */
	public record Unavailable(String what) implements Step {}

	public record Done() implements Step {}

	private static final Done DONE = new Done();
	private static final double INF = 1e9;
	private static final int MAX_DEPTH = 8;
	private static final double CRAFT_COST = 1;
	private static final double SMELT_COST = 4;
	/** One click on a stonecutter, cheaper than laying out a crafting grid. */
	private static final double CUT_COST = 0.5;
	/** A stonecutter made just for this is shared by roughly this many items (it's kept for later too). */
	private static final double CUTTER_AMORTIZE = 16;

	/** Cheapest way to make or get more of an item. */
	private record Route(@Nullable Requirement gather, @Nullable Recipe recipe, RecipeBook.@Nullable Cut cut, @Nullable Procedure procedure,
			double cost) {}

	private final LocalPlayer p;
	private final Requirement.Dim dimension;
	private final boolean tableNearby;
	private final boolean furnaceNearby;
	private final boolean cutterNearby;
	private final Predicate<ItemStack> protect;
	private final RecipeBook book = RecipeBook.get();
	private final Map<String, Double> costMemo = new HashMap<>();

	/** @param protect job loot: never smelted beyond what's needed */
	public Planner(LocalPlayer p, Requirement.Dim dimension, boolean tableNearby, boolean furnaceNearby, boolean cutterNearby,
			Predicate<ItemStack> protect) {
		this.p = p;
		this.dimension = dimension;
		this.tableNearby = tableNearby;
		this.furnaceNearby = furnaceNearby;
		this.cutterNearby = cutterNearby;
		this.protect = protect;
	}

	/** Any carried stack matches. */
	public boolean hasAny(Predicate<ItemStack> pred) {
		return InvUtil.find(p, pred) >= 0;
	}

	/** Next step toward carrying {@code count} of {@code want} (worn-out tools don't count). */
	public Step next(Item want, int count) {
		return obtain(want, count, false, 0, new LinkedHashSet<>());
	}

	/** Next step toward the cheapest boat (usually the wood we're carrying). */
	public Step boat() {
		if (hasAny(s -> s.is(ItemTags.BOATS))) return DONE;
		Item best = null;
		double bestCost = INF;
		for (Holder<Item> h : BuiltInRegistries.ITEM.getTagOrEmpty(ItemTags.BOATS)) {
			double c = cost(h.value(), false, 0, new HashSet<>());
			if (c < bestCost) {
				bestCost = c;
				best = h.value();
			}
		}
		return best == null ? new Unavailable("boat") : next(best, 1);
	}

	/** Rough effort to get one more of {@code item} (0 when a usable one is carried); compares alternatives. */
	public double costOf(Item item) {
		return cost(item, false, 0, new HashSet<>());
	}

	// ---- planning -------------------------------------------------------------------------------

	private int carried(Item item) {
		return InvUtil.count(p, s -> s.is(item) && !InvUtil.nearlyBroken(s));
	}

	private Step obtain(Item want, int count, boolean flexible, int depth, Set<Item> visiting) {
		int have = carried(want);
		if (have >= count) return DONE;
		if (depth > MAX_DEPTH || !visiting.add(want)) return new Unavailable(name(want));
		try {
			int missing = count - have;
			Route route = bestRoute(want, flexible, depth, visiting);
			if (route == null) return new Unavailable(name(want));
			if (route.gather != null) {
				int total = InvUtil.count(p, route.gather.counts()) + missing; // requirement amounts are absolute
				Requirement g = Gatherable.requirementFor(want, total, dimension, flexible);
				return g == null ? new Unavailable(name(want)) : new Gather(g);
			}
			if (route.procedure != null) {
				for (Map.Entry<Item, Integer> need : route.procedure.needs(want, missing).entrySet()) {
					if (need.getValue() <= 0) continue;
					Step s = obtain(need.getKey(), need.getValue(), false, depth + 1, visiting);
					if (!(s instanceof Done)) return s;
				}
				return new Special(route.procedure, want, count);
			}
			if (route.cut != null) return cut(route.cut, missing, depth, visiting);
			if (route.recipe.isSmelting()) return smelt(route.recipe, missing, depth, visiting);
			return craft(route.recipe, missing, depth, visiting);
		} finally {
			visiting.remove(want);
		}
	}

	private Step craft(Recipe r, int missingOutput, int depth, Set<Item> visiting) {
		int batches = (missingOutput + r.count() - 1) / r.count();
		Set<Ingredient> seen = new HashSet<>();
		for (Ingredient ing : r.key().values()) {
			if (!seen.add(ing)) continue;
			Step s = ingredient(ing, r.amountOf(ing) * batches, depth, visiting);
			if (!(s instanceof Done)) return s;
		}
		if (!r.fits2x2() && !tableNearby && carried(Items.CRAFTING_TABLE) == 0) {
			Step t = obtain(Items.CRAFTING_TABLE, 1, false, depth + 1, visiting);
			if (!(t instanceof Done)) return t;
		}
		return new Craft(r);
	}

	private Step cut(RecipeBook.Cut c, int missingOutput, int depth, Set<Item> visiting) {
		int cuts = (missingOutput + c.count() - 1) / c.count();
		Step s = ingredient(c.input(), cuts, depth, visiting);
		if (!(s instanceof Done)) return s;
		if (!cutterNearby && carried(Items.STONECUTTER) == 0) {
			Step t = obtain(Items.STONECUTTER, 1, false, depth + 1, visiting);
			if (!(t instanceof Done)) return t;
		}
		return new Cut(pickCarried(c.input()), c.output(), cuts);
	}

	private Step smelt(Recipe r, int missingOutput, int depth, Set<Item> visiting) {
		Ingredient input = r.smeltInput();
		Step s = ingredient(input, missingOutput, depth, visiting);
		if (!(s instanceof Done)) return s;
		Item inputItem = pickCarried(input);
		// smelt every spare piece in one furnace session, but never more job loot than needed
		int carriedInput = InvUtil.count(p, input::test);
		int amount = protect.test(new ItemStack(inputItem)) ? missingOutput : Math.min(carriedInput, 64);
		int furnaces = carried(Items.FURNACE) + (furnaceNearby ? 1 : 0);
		if (furnaces == 0) {
			Step f = obtain(Items.FURNACE, 1, false, depth + 1, visiting);
			if (!(f instanceof Done)) return f;
		} else {
			// big batch: a few more furnaces cook it several times faster when the cobblestone is already here
			int extra = Smelter.furnacesFor(amount) - furnaces;
			int cobble = InvUtil.count(p, x -> x.is(ItemTags.STONE_CRAFTING_MATERIALS) && !protect.test(x));
			if (extra > 0 && cobble >= 8 * extra) {
				Step f = obtain(Items.FURNACE, carried(Items.FURNACE) + extra, false, depth + 1, visiting);
				if (f instanceof Craft) return f;
			}
		}
		if (Smelter.fuelValue(p) < amount) {
			int coal = InvUtil.count(p, x -> x.is(Items.COAL)) + (amount + 7) / 8;
			Requirement fuel = Gatherable.requirementFor(Items.COAL, coal, dimension, false);
			return fuel == null ? new Unavailable("fuel") : new Gather(fuel);
		}
		return new Smelt(inputItem, amount);
	}

	/** Make sure {@code needed} items matching the ingredient are carried (any mix of its options; job loot doesn't count). */
	private Step ingredient(Ingredient ing, int needed, int depth, Set<Item> visiting) {
		int have = InvUtil.count(p, s -> ing.test(s) && !protect.test(s));
		if (have >= needed) return DONE;
		Item choice = cheapestOption(ing, depth, visiting);
		if (choice == null) return new Unavailable(ing.toString());
		boolean flexible = ing.options().size() > 1;
		return obtain(choice, carried(choice) + needed - have, flexible, depth + 1, visiting);
	}

	private Item pickCarried(Ingredient ing) {
		for (Item option : ing.options()) {
			if (carried(option) > 0) return option;
		}
		return ing.options().getFirst();
	}

	/**
	 * Which option of an ingredient to make more of: the cheapest to make. What's already carried doesn't count
	 * here (it's in the count anyway) — two birch planks on hand must not mean "make more birch planks" when only
	 * oak logs are carried.
	 */
	private @Nullable Item cheapestOption(Ingredient ing, int depth, Set<Item> visiting) {
		Item best = null;
		double bestCost = INF;
		boolean flexible = ing.options().size() > 1;
		for (Item option : ing.options()) {
			double c = makeCost(option, flexible, depth + 1, visiting);
			if (c < bestCost) {
				bestCost = c;
				best = option;
			}
		}
		return best;
	}

	/** Cost of making or getting one more, whether or not some are carried. */
	private double makeCost(Item item, boolean flexible, int depth, Set<Item> visiting) {
		if (depth > MAX_DEPTH || !visiting.add(item)) return INF;
		try {
			Route r = bestRoute(item, flexible, depth, visiting);
			return r == null ? INF : r.cost;
		} finally {
			visiting.remove(item);
		}
	}

	// ---- route costs ----------------------------------------------------------------------------

	private @Nullable Route bestRoute(Item item, boolean flexible, int depth, Set<Item> visiting) {
		Route best = null;
		Requirement gather = Gatherable.requirementFor(item, carried(item) + 1, dimension, flexible);
		if (gather != null) best = new Route(gather, null, null, null, Gatherable.cost(gather, dimension));
		for (Recipe r : book.crafting(item)) {
			double c = CRAFT_COST + recipeInputs(r, depth, visiting);
			if (best == null || c < best.cost) best = new Route(null, r, null, null, c);
		}
		for (Recipe r : book.smelting(item)) {
			double c = SMELT_COST + optionCost(r.smeltInput(), depth, visiting);
			if (best == null || c < best.cost) best = new Route(null, r, null, null, c);
		}
		List<RecipeBook.Cut> cuts = book.cutting(item);
		if (!cuts.isEmpty()) {
			// stonecutter vs crafting table: better yields (stairs 1:1, not 6:4), but the cutter itself costs iron
			double station = cutterNearby || carried(Items.STONECUTTER) > 0 ? 0 : cost(Items.STONECUTTER, false, depth + 1, visiting) / CUTTER_AMORTIZE;
			for (RecipeBook.Cut cut : cuts) {
				double c = CUT_COST + station + optionCost(cut.input(), depth, visiting) / cut.count();
				if (best == null || c < best.cost) best = new Route(null, null, cut, null, c);
			}
		}
		Procedure proc = Procedures.forItem(item);
		if (proc != null) {
			double c = proc.cost(item);
			for (Item need : proc.needs(item, 1).keySet()) {
				c += cost(need, false, depth + 1, visiting) * 0.25; // tools and containers are reused across items
				if (c >= INF) break;
			}
			if (best == null || c < best.cost) best = new Route(null, null, null, proc, c);
		}
		return best == null || best.cost >= INF ? null : best;
	}

	private double cost(Item item, boolean flexible, int depth, Set<Item> visiting) {
		if (carried(item) > 0) return 0;
		String key = BuiltInRegistries.ITEM.getKey(item) + (flexible ? "*" : "");
		Double memo = costMemo.get(key);
		if (memo != null) return memo;
		if (depth > MAX_DEPTH || !visiting.add(item)) return INF;
		try {
			Route r = bestRoute(item, flexible, depth, visiting);
			double c = r == null ? INF : r.cost;
			if (c < INF) costMemo.put(key, c); // INF can be an artefact of the current recursion path
			return c;
		} finally {
			visiting.remove(item);
		}
	}

	private double recipeInputs(Recipe r, int depth, Set<Item> visiting) {
		double total = 0;
		Set<Ingredient> seen = new HashSet<>();
		for (Ingredient ing : r.key().values()) {
			if (!seen.add(ing)) continue;
			double unit = optionCost(ing, depth, visiting);
			if (unit >= INF) return INF;
			total += unit * r.amountOf(ing) / r.count();
		}
		return total;
	}

	private double optionCost(Ingredient ing, int depth, Set<Item> visiting) {
		double best = INF;
		boolean flexible = ing.options().size() > 1;
		for (Item option : ing.options()) {
			best = Math.min(best, cost(option, flexible, depth + 1, visiting));
			if (best == 0) break;
		}
		return best;
	}

	private static String name(Item item) {
		return BuiltInRegistries.ITEM.getKey(item).getPath();
	}
}
