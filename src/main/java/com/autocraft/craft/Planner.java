package com.autocraft.craft;

import com.autocraft.control.InvUtil;
import com.autocraft.craft.RecipeBook.Ingredient;
import com.autocraft.craft.RecipeBook.Recipe;
import com.autocraft.goal.Requirement;
import com.autocraft.loot.LootBook;
import com.autocraft.procedure.Procedure;
import com.autocraft.procedure.Procedures;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.resources.ResourceKey;
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
	public sealed interface Step permits Craft, Smelt, Cut, Smith, Gather, Special, Unavailable, Done {}

	public record Craft(Recipe recipe) implements Step {}

	public record Smelt(Item input, int count) implements Step {}

	/** Cut {@code cuts} of {@code input} into {@code output} on a stonecutter. */
	public record Cut(Item input, Item output, int cuts) implements Step {}

	/** Upgrade on a smithing table (diamond gear + netherite ingot + template → netherite gear). */
	public record Smith(RecipeBook.Smithing recipe) implements Step {}

	public record Gather(Requirement requirement) implements Step {}

	/** Run a procedure until {@code total} of {@code item} are carried (its needs are already carried). */
	public record Special(Procedure procedure, Item item, int total) implements Step {}

	/** Needs something the bot can't get any way it knows (loot only, the End). */
	public record Unavailable(String what) implements Step {}

	public record Done() implements Step {}

	private static final Done DONE = new Done();
	private static final double INF = 1e9;
	private static final int MAX_DEPTH = 8;
	/** How deep an explained plan may go (diamond → iron pickaxe → … → logs is about 10). */
	private static final int EXPLAIN_DEPTH = 16;
	private static final List<Item> PICKAXES = List.of(Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE);
	private static final double CRAFT_COST = 1;
	private static final double SMELT_COST = 4;
	/** One click on a stonecutter, cheaper than laying out a crafting grid. */
	private static final double CUT_COST = 0.5;
	/** A stonecutter made just for this is shared by roughly this many items (it's kept for later too). */
	private static final double CUTTER_AMORTIZE = 16;
	/** Likewise a crafting table. */
	private static final double TABLE_AMORTIZE = 16;
	/** Three items into a smithing table. */
	private static final double SMITH_COST = 1;

	/** Cheapest way to make or get more of an item. */
	private record Route(@Nullable Requirement gather, @Nullable Recipe recipe, RecipeBook.@Nullable Cut cut, RecipeBook.@Nullable Smithing smith,
			@Nullable Procedure procedure, double cost) {}

	/** A memoized cost and every item it was worked out through, carried ones included (see {@link #cost}). */
	private record Memo(double cost, Set<Item> through) {}

	private final LocalPlayer p;
	private final Requirement.Dim dimension;
	private final boolean tableNearby;
	private final boolean furnaceNearby;
	private final boolean cutterNearby;
	private final boolean smithingNearby;
	private final Predicate<ItemStack> protect;
	/** Biomes in the loaded area (null: unknown): gathering what grows only elsewhere is a trip (see Gatherable.cost). */
	private final @Nullable Set<ResourceKey<Biome>> biomesAround;
	private final RecipeBook book = RecipeBook.get();
	private final Map<String, Memo> costMemo = new HashMap<>();
	/** The items each cost in progress has been worked out through so far, innermost on top. */
	private final java.util.ArrayDeque<Set<Item>> consulting = new java.util.ArrayDeque<>();
	private boolean borrowJobLoot;
	/** Dev only: receives every obtain/chooseTool decision when set. */
	public static java.util.function.@Nullable Consumer<String> trace;

	/**
	 * @param protect job loot: not used as an ingredient (unless {@link #borrowingJobLoot}), never smelted beyond what's needed
	 * @param biomesAround biomes in the loaded area, or null when unknown
	 */
	public Planner(LocalPlayer p, Requirement.Dim dimension, boolean tableNearby, boolean furnaceNearby, boolean cutterNearby,
			boolean smithingNearby, Predicate<ItemStack> protect, @Nullable Set<ResourceKey<Biome>> biomesAround) {
		this.p = p;
		this.dimension = dimension;
		this.tableNearby = tableNearby;
		this.furnaceNearby = furnaceNearby;
		this.cutterNearby = cutterNearby;
		this.smithingNearby = smithingNearby;
		this.protect = protect;
		this.biomesAround = biomesAround;
	}

	/**
	 * Job loot may be used as an ingredient: for a tool the job can't go on without, 3 of the 40 cobblestone mined
	 * so far make a stone pickaxe and the job mines 3 more — instead of a trip for logs, or no pickaxe at all. The
	 * total work is the same either way (protected loot would have to be gathered again for the tool).
	 */
	public Planner borrowingJobLoot() {
		borrowJobLoot = true;
		return this;
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
			// planks of this kind on hand (mixed wreck planks: 5 of one kind): craft that one
			if (next(h.value(), 1) instanceof Craft craft) return craft;
			double c = cost(h.value(), false, 0, new HashSet<>());
			if (c < bestCost) {
				bestCost = c;
				best = h.value();
			}
		}
		return best == null ? new Unavailable("boat") : next(best, 1);
	}

	/**
	 * Next step toward the cheapest bed: three wool of one color and three planks, so usually white from shorn or hunted
	 * sheep, or whatever color three of are carried.
	 */
	public Step bed() {
		if (hasAny(s -> s.is(ItemTags.BEDS))) return DONE;
		Item best = null;
		double bestCost = INF;
		for (Holder<Item> h : BuiltInRegistries.ITEM.getTagOrEmpty(ItemTags.BEDS)) {
			// the wool for this one is on hand: craft it (not a step toward another color's ingredients)
			if (next(h.value(), 1) instanceof Craft craft && craft.recipe().output() == h.value()) return craft;
			double c = cost(h.value(), false, 0, new HashSet<>());
			if (c < bestCost) {
				bestCost = c;
				best = h.value();
			}
		}
		return best == null ? new Unavailable("bed") : next(best, 1);
	}

	// ---- tools for gathering --------------------------------------------------------------------

	/** A carried tool (or a bare hand) gets drops from at least one of the requirement's blocks. */
	public boolean canHarvest(Requirement r) {
		for (Block b : r.sources()) {
			if (InvUtil.canHarvest(p, b.defaultBlockState())) return true;
		}
		return false;
	}

	/**
	 * Pickaxe tiers that get drops from the requirement's blocks, cheapest to make right now first; empty: no tool
	 * needed. A tier made from what the requirement collects (a diamond pickaxe for diamonds, none carried) costs
	 * what it really does — nothing it can be made from yet — and goes last.
	 */
	public List<Item> pickaxeTiers(Requirement r) {
		for (Block b : r.sources()) {
			if (!b.defaultBlockState().requiresCorrectToolForDrops()) return List.of();
		}
		List<Item> out = new ArrayList<>();
		for (Item pick : PICKAXES) {
			ItemStack stack = new ItemStack(pick);
			if (r.sources().stream().anyMatch(b -> stack.isCorrectToolForDrops(b.defaultBlockState()))) out.add(pick);
		}
		Set<Item> mined = minedBy(r);
		Map<Item, Double> costs = new HashMap<>();
		for (Item pick : out) costs.put(pick, toolCost(pick, mined));
		out.sort(Comparator.comparingDouble(costs::get)); // stable: lower tier first on ties
		return out;
	}

	/** What gathering {@code r} yields that isn't carried yet: a pickaxe can't be made from those to get them. */
	private Set<Item> minedBy(Requirement r) {
		Set<Item> out = new HashSet<>();
		for (Item i : BuiltInRegistries.ITEM) {
			if (i != Items.AIR && carried(i) == 0 && r.counts().test(i.getDefaultInstance())) out.add(i);
		}
		return out;
	}

	/** Cost of making {@code pick} without any of {@code mined} (bypasses the memo: that was costed with them). */
	private double toolCost(Item pick, Set<Item> mined) {
		if (carried(pick) > 0) return 0;
		return mined.isEmpty() ? cost(pick, false, 0, new HashSet<>()) : makeCost(pick, false, 0, new HashSet<>(mined));
	}

	/**
	 * The next step toward gathering {@code gather}: the gather itself when a carried tool gets its drops, otherwise
	 * the first step toward a pickaxe that does — the tier that's cheapest to make right now, but never one whose
	 * making needs this very gathering. (Carrying a couple of raw iron made an iron pickaxe look cheapest for mining
	 * iron ore; its first step was "gather iron", which needs the pickaxe, and the bot stood still.) Unavailable when
	 * no pickaxe can be had.
	 */
	public Step toolFirst(Gather gather) {
		ToolChoice c = chooseTool(gather.requirement(), new HashSet<>(), 0);
		return c == null ? gather : c.step;
	}

	/**
	 * The pickaxe to make before gathering {@code r}, or null when a carried tool (or none) will do. {@code pending}:
	 * the blocks of the gatherings this tool is for — a tier whose making needs any of them is a cycle (compared by
	 * blocks, since "raw_iron" and "iron" mine the same ore).
	 */
	private @Nullable ToolChoice chooseTool(Requirement r, Set<Block> pending, int depth) {
		List<Item> tiers = pickaxeTiers(r);
		if (tiers.isEmpty() || canHarvest(r)) return null;
		Unavailable none = new Unavailable("a pickaxe for " + r.name());
		if (depth > PICKAXES.size() || !Collections.disjoint(pending, r.sources())) return new ToolChoice(null, none);
		pending.addAll(r.sources());
		Set<Item> mined = minedBy(r);
		try {
			for (Item tool : tiers) {
				// its cheapest making (the way next() goes) needs what it's meant to mine: diamonds for a diamond pickaxe
				double without = toolCost(tool, mined);
				if (without >= INF || without > cost(tool, false, 0, new HashSet<>()) + 1e-6) continue;
				Step s = next(tool, 1);
				if (trace != null) trace.accept("tool[" + depth + "] for " + r.name() + " " + r.sources() + ": " + name(tool) + " -> " + s + " pending=" + pending);
				if (s instanceof Unavailable || s instanceof Done) continue;
				if (!(s instanceof Gather g)) return new ToolChoice(tool, s); // craft/smelt/cut toward it: actionable now
				if (!Collections.disjoint(pending, g.requirement().sources())) continue; // needs what it's meant to mine
				ToolChoice deeper = chooseTool(g.requirement(), pending, depth + 1);
				if (deeper == null) return new ToolChoice(tool, s);
				if (!(deeper.step instanceof Unavailable)) return new ToolChoice(tool, deeper.step);
			}
			return new ToolChoice(null, none);
		} finally {
			pending.removeAll(r.sources());
		}
	}

	/** {@code tool}: the pickaxe being made (null: none can be); {@code step}: what to do next toward it. */
	private record ToolChoice(@Nullable Item tool, Step step) {}

	// ---- explaining a plan ----------------------------------------------------------------------

	/**
	 * One step of a plan as the bot carries it out: get {@code amount} of {@code name} (icon {@code icon}, item id
	 * {@code key}), {@code have} of it carried, by {@code how} ("carried", "mine iron ore", "craft", "smelt"...);
	 * {@code parts} come first.
	 */
	public record PlanNode(Item icon, String key, String name, int amount, int have, String how, List<PlanNode> parts) {}

	/**
	 * The whole plan for a job requirement, followed all the way down with the same choices {@link #next} makes:
	 * mined or crafted, which recipe, the pickaxe to make before mining, crafting table, furnace and fuel. Each part
	 * is costed against what's carried now; something shown once (a wooden pickaxe two tools need) is "see above"
	 * after that.
	 */
	public PlanNode explain(Requirement r) {
		Set<Item> shown = new HashSet<>();
		if (r.isCrafted()) return explain(r.product(), r.amount(), false, 0, new LinkedHashSet<>(), shown);
		int have = InvUtil.count(p, r.counts());
		Item icon = iconOf(r);
		return gatherNode(r, icon, r.name(), display(r.name()), r.amount(), have, 0, new LinkedHashSet<>(), shown);
	}

	private PlanNode explain(Item want, int count, boolean flexible, int depth, Set<Item> path, Set<Item> shown) {
		String key = name(want), name = want.getDefaultInstance().getHoverName().getString();
		int have = carried(want);
		if (have >= count) return new PlanNode(want, key, name, count, have, "carried", List.of());
		if (!shown.add(want)) return new PlanNode(want, key, name, count, have, "see above", List.of());
		if (depth > EXPLAIN_DEPTH || !path.add(want)) return new PlanNode(want, key, name, count, have, "can't be had", List.of());
		try {
			Route route = bestRoute(want, flexible, 0, new HashSet<>(path)); // like obtain: the item itself is on the path
			if (route == null) return new PlanNode(want, key, name, count, have, "can't be had", List.of());
			int missing = count - have;
			List<PlanNode> parts = new ArrayList<>();
			if (route.gather != null) {
				Requirement g = Gatherable.requirementFor(want, count, dimension, flexible);
				if (g == null) return new PlanNode(want, key, name, count, have, "can't be had", List.of());
				boolean anyWood = flexible && want.getDefaultInstance().is(ItemTags.LOGS);
				return gatherNode(g, want, anyWood ? g.name() : key, anyWood ? "Logs (any kind)" : name, count, have, depth, path, shown);
			}
			String how;
			if (route.procedure != null) {
				how = route.procedure.label().toLowerCase(Locale.ROOT);
				for (Map.Entry<Item, Integer> need : route.procedure.needs(want, missing).entrySet()) {
					if (need.getValue() > 0) parts.add(explain(need.getKey(), need.getValue(), false, depth + 1, path, shown));
				}
			} else if (route.cut != null) {
				how = "stonecutter";
				int cuts = (missing + route.cut.count() - 1) / route.cut.count();
				parts.add(explainIngredient(route.cut.input(), cuts, depth, path, shown));
				if (!cutterNearby && carried(Items.STONECUTTER) == 0) parts.add(explain(Items.STONECUTTER, 1, false, depth + 1, path, shown));
			} else if (route.smith != null) {
				how = "smithing table";
				parts.add(explainIngredient(route.smith.template(), missing, depth, path, shown));
				parts.add(explainIngredient(route.smith.base(), missing, depth, path, shown));
				parts.add(explainIngredient(route.smith.addition(), missing, depth, path, shown));
				if (!smithingNearby && carried(Items.SMITHING_TABLE) == 0) parts.add(explain(Items.SMITHING_TABLE, 1, false, depth + 1, path, shown));
			} else if (route.recipe.isSmelting()) {
				how = "smelt";
				parts.add(explainIngredient(route.recipe.smeltInput(), missing, depth, path, shown));
				if (!furnaceNearby && carried(Items.FURNACE) == 0) parts.add(explain(Items.FURNACE, 1, false, depth + 1, path, shown));
				if (Smelter.fuelValue(p) < missing) {
					parts.add(explain(Items.COAL, carried(Items.COAL) + (missing + 7) / 8, false, depth + 1, path, shown));
				}
			} else {
				Recipe r = route.recipe;
				how = "craft";
				int batches = (missing + r.count() - 1) / r.count();
				for (Ingredient ing : new LinkedHashSet<>(r.key().values())) parts.add(explainIngredient(ing, r.amountOf(ing) * batches, depth, path, shown));
				if (!r.fits2x2() && !tableNearby && carried(Items.CRAFTING_TABLE) == 0) {
					parts.add(explain(Items.CRAFTING_TABLE, 1, false, depth + 1, path, shown));
				}
			}
			return new PlanNode(want, key, name, count, have, how, parts);
		} finally {
			path.remove(want);
		}
	}

	/** The option of an ingredient the planner would use (what's carried, else the cheapest to make). */
	private PlanNode explainIngredient(Ingredient ing, int needed, int depth, Set<Item> path, Set<Item> shown) {
		int have = InvUtil.count(p, s -> ing.test(s) && (borrowJobLoot || !protect.test(s)));
		Item choice = have >= needed ? pickCarried(ing) : cheapestOption(ing, 0, new HashSet<>(path));
		if (choice == null) choice = ing.options().getFirst();
		boolean flexible = ing.options().size() > 1;
		PlanNode n = explain(choice, Math.max(needed, carried(choice) + needed - have), flexible, depth + 1, path, shown);
		if (flexible && have < needed && ing.options().stream().allMatch(o -> o.getDefaultInstance().is(ItemTags.PLANKS))) {
			// any planks do: the bot makes them from whatever logs it finds
			return new PlanNode(n.icon, n.key, "Planks (any wood)", n.amount, have, n.how, n.parts);
		}
		return n;
	}

	/** Mining or hunting {@code g}: the pickaxe (or shears) to make first, then the gathering itself. */
	private PlanNode gatherNode(Requirement g, Item icon, String key, String name, int count, int have, int depth, Set<Item> path, Set<Item> shown) {
		if (have >= count) return new PlanNode(icon, key, name, count, have, "carried", List.of());
		List<PlanNode> parts = new ArrayList<>();
		ToolChoice tool = chooseTool(g, new HashSet<>(), 0);
		if (tool != null && tool.tool != null) parts.add(explain(tool.tool, 1, false, depth + 1, path, shown));
		if (g.tool() == LootBook.ToolUse.SHEARS && !hasAny(s -> s.is(Items.SHEARS))) parts.add(explain(Items.SHEARS, 1, false, depth + 1, path, shown));
		String how;
		if (g.isHunted()) {
			how = "hunt " + g.mobs().stream().map(t -> t.getDescription().getString().toLowerCase(Locale.ROOT)).sorted().limit(2).collect(Collectors.joining(" / "));
		} else if (!g.sources().isEmpty() && g.sources().stream().allMatch(b -> b.defaultBlockState().is(BlockTags.LOGS))) {
			how = "chop trees";
		} else {
			how = "mine " + g.sources().stream().map(b -> b.getName().getString().toLowerCase(Locale.ROOT)).sorted().limit(2).collect(Collectors.joining(" / "))
					+ (g.sources().size() > 2 ? "…" : "");
		}
		if (g.dimension() == Requirement.Dim.NETHER) how += " (Nether)";
		if (tool != null && tool.tool == null) how += " — no pickaxe can be had";
		return new PlanNode(icon, key, name, count, have, how, parts);
	}

	private static Item iconOf(Requirement r) {
		Item named = BuiltInRegistries.ITEM.getOptional(Identifier.withDefaultNamespace(r.name())).orElse(Items.AIR);
		if (named != Items.AIR && r.counts().test(named.getDefaultInstance())) return named;
		for (Block b : r.sources()) {
			if (b.asItem() != Items.AIR) return b.asItem();
		}
		return Items.BARRIER;
	}

	private static String display(String id) {
		String s = id.replace('_', ' ');
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	// ---- planning -------------------------------------------------------------------------------

	/**
	 * Usable ones carried (worn-out tools don't count). A tool in the offhand doesn't either: mining and fighting use
	 * the main hand and nothing swaps it back, so counting it would have the planner "own" a pickaxe nothing can use.
	 */
	private int carried(Item item) {
		Predicate<ItemStack> usable = s -> s.is(item) && !InvUtil.nearlyBroken(s);
		return item.components().has(DataComponents.MAX_DAMAGE) ? InvUtil.countMain(p, usable) : InvUtil.count(p, usable);
	}

	private Step obtain(Item want, int count, boolean flexible, int depth, Set<Item> visiting) {
		Step s = obtain0(want, count, flexible, depth, visiting);
		if (trace != null) trace.accept("  ".repeat(depth) + "obtain " + name(want) + " x" + count + (flexible ? "*" : "") + " -> " + s);
		return s;
	}

	private Step obtain0(Item want, int count, boolean flexible, int depth, Set<Item> visiting) {
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
			if (route.smith != null) return smith(route.smith, missing, depth, visiting);
			if (route.recipe.isSmelting()) return smelt(route.recipe, missing, depth, visiting);
			return craft(route.recipe, missing, depth, visiting);
		} finally {
			visiting.remove(want);
		}
	}

	private Step craft(Recipe r, int missingOutput, int depth, Set<Item> visiting) {
		int batches = (missingOutput + r.count() - 1) / r.count();
		Set<Ingredient> seen = new HashSet<>();
		Step later = null;
		for (Ingredient ing : r.key().values()) {
			if (!seen.add(ing)) continue;
			Step s = ingredient(ing, r.amountOf(ing) * batches, depth, visiting);
			if (s instanceof Done) continue;
			if (!elsewhere(s)) return s;
			if (later == null) later = s;
		}
		if (later != null) return later;
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

	/**
	 * One upgrade per item: the base (diamond gear, mined at home; its pickaxe is what mines ancient debris
	 * anyway), the addition, the template and a smithing table — whichever can be had on this side of the portal
	 * first, so the bot crosses once.
	 */
	private Step smith(RecipeBook.Smithing r, int missingOutput, int depth, Set<Item> visiting) {
		Step later = null;
		for (Ingredient ing : List.of(r.base(), r.addition(), r.template())) {
			Step s = ingredient(ing, missingOutput, depth, visiting);
			if (s instanceof Done) continue;
			if (!elsewhere(s)) return s;
			if (later == null) later = s;
		}
		if (!smithingNearby && carried(Items.SMITHING_TABLE) == 0) {
			Step t = obtain(Items.SMITHING_TABLE, 1, false, depth + 1, visiting);
			if (!(t instanceof Done) && (later == null || !elsewhere(t))) return t;
		}
		return later != null ? later : new Smith(r);
	}

	/** A step that can't be taken in this dimension: gathering, or a procedure (a bastion chest), on the other side. */
	private boolean elsewhere(Step s) {
		return switch (s) {
			case Gather g -> g.requirement().dimension() != dimension;
			case Special sp -> {
				Requirement.Dim d = sp.procedure().dimension(sp.item());
				yield d != null && d != dimension;
			}
			default -> false;
		};
	}

	private Step smelt(Recipe r, int missingOutput, int depth, Set<Item> visiting) {
		Ingredient input = r.smeltInput();
		Step s = ingredient(input, missingOutput, depth, visiting);
		if (!(s instanceof Done)) return s;
		Item inputItem = pickCarried(input);
		// smelt every spare piece of ore in one furnace session, but never more job loot than needed, and of cheap
		// blocks only what's needed (64 cobblestone went in for the 7 stone 4 stairs and a stonecutter take)
		int carriedInput = InvUtil.count(p, input::test);
		ItemStack inputStack = new ItemStack(inputItem);
		int amount = protect.test(inputStack) || InvUtil.isThrowaway(inputStack) ? missingOutput : Math.min(carriedInput, 64);
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

	/** Make sure {@code needed} items matching the ingredient are carried (any mix of its options; job loot only when borrowing). */
	private Step ingredient(Ingredient ing, int needed, int depth, Set<Item> visiting) {
		int have = InvUtil.count(p, s -> ing.test(s) && (borrowJobLoot || !protect.test(s)));
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
		if (gather != null) best = new Route(gather, null, null, null, null, Gatherable.cost(gather, dimension, biomesAround));
		for (Recipe r : book.crafting(item)) {
			// a 3x3 recipe needs a table: a share of one when none is at hand, and never one made from the item itself
			// (an axe for stripped logs for the planks of the table it's crafted on)
			double table = r.fits2x2() || tableNearby || carried(Items.CRAFTING_TABLE) > 0 ? 0
					: cost(Items.CRAFTING_TABLE, false, depth + 1, visiting);
			double c = table >= INF ? INF : CRAFT_COST + table / TABLE_AMORTIZE + recipeInputs(r, depth, visiting);
			if (best == null || c < best.cost) best = new Route(null, r, null, null, null, c);
		}
		for (Recipe r : book.smelting(item)) {
			double c = SMELT_COST + optionCost(r.smeltInput(), depth, visiting);
			if (best == null || c < best.cost) best = new Route(null, r, null, null, null, c);
		}
		List<RecipeBook.Cut> cuts = book.cutting(item);
		if (!cuts.isEmpty()) {
			// stonecutter vs crafting table: better yields (stairs 1:1, not 6:4), but the cutter itself costs iron
			double cutter = cutterNearby || carried(Items.STONECUTTER) > 0 ? 0 : cost(Items.STONECUTTER, false, depth + 1, visiting);
			double station = cutter >= INF ? INF : cutter / CUTTER_AMORTIZE;
			for (RecipeBook.Cut cut : cuts) {
				double c = CUT_COST + station + optionCost(cut.input(), depth, visiting) / cut.count();
				if (best == null || c < best.cost) best = new Route(null, null, cut, null, null, c);
			}
		}
		for (RecipeBook.Smithing s : book.smithing(item)) {
			double station = smithingNearby || carried(Items.SMITHING_TABLE) > 0 ? 0 : cost(Items.SMITHING_TABLE, false, depth + 1, visiting);
			double c = SMITH_COST + station + optionCost(s.template(), depth, visiting) + optionCost(s.base(), depth, visiting)
					+ optionCost(s.addition(), depth, visiting);
			if (best == null || c < best.cost) best = new Route(null, null, null, s, null, c);
		}
		Procedure proc = Procedures.forItem(item);
		if (proc != null) {
			double c = proc.cost(item);
			Item used = proc.consumed(item);
			for (Item need : proc.needs(item, 1).keySet()) {
				// tools and containers are reused across items; what it's made from is used up (at a quarter, a stripped
				// log looked cheaper than the log, and planks for a crafting table waited on an axe that needed one)
				c += cost(need, false, depth + 1, visiting) * (need == used ? 1 : 0.25);
				if (c >= INF) break;
			}
			if (best == null || c < best.cost) best = new Route(null, null, null, null, proc, c);
		}
		return best == null || best.cost >= INF ? null : best;
	}

	/**
	 * An item being made can't go into itself: carried cobblestone made "smelt it to stone, cut that back" look free,
	 * and the plan for a stone pickaxe then dead-ended in "can't get cobblestone". The memo keeps what each cost was
	 * worked out through (carried items included), and a memoized cost that went through something being made right
	 * now is worked out afresh: one carried cobblestone made stone cost a smelt, after which "cut stone back into
	 * cobblestone" beat mining it — a loop, and the bot stood still for half an hour.
	 */
	private double cost(Item item, boolean flexible, int depth, Set<Item> visiting) {
		if (visiting.contains(item)) return INF;
		consulted(item);
		if (carried(item) > 0) return 0;
		String key = BuiltInRegistries.ITEM.getKey(item) + (flexible ? "*" : "");
		Memo memo = costMemo.get(key);
		if (memo != null && Collections.disjoint(memo.through(), visiting)) {
			consulted(memo.through());
			return memo.cost();
		}
		if (depth > MAX_DEPTH || !visiting.add(item)) return INF;
		Set<Item> through = new HashSet<>();
		consulting.push(through);
		try {
			Route r = bestRoute(item, flexible, depth, visiting);
			double c = r == null ? INF : r.cost;
			if (c < INF && memo == null) costMemo.put(key, new Memo(c, through)); // INF can be an artefact of the current recursion path
			return c;
		} finally {
			consulting.pop();
			consulted(through);
			visiting.remove(item);
		}
	}

	private void consulted(Item item) {
		Set<Item> top = consulting.peek();
		if (top != null) top.add(item);
	}

	private void consulted(Set<Item> items) {
		Set<Item> top = consulting.peek();
		if (top != null) top.addAll(items);
	}

	private double recipeInputs(Recipe r, int depth, Set<Item> visiting) {
		double total = 0;
		Set<Ingredient> seen = new HashSet<>();
		for (Ingredient ing : r.key().values()) {
			if (!seen.add(ing)) continue;
			// a recipe that multiplies its own input (smithing template duplication): free only when one is carried
			double unit = ing.options().contains(r.output()) ? (carried(r.output()) > 0 ? 0 : INF) : optionCost(ing, depth, visiting);
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
