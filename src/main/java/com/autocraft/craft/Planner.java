package com.autocraft.craft;

import com.autocraft.control.InvUtil;
import com.autocraft.craft.Recipes.Ingredient;
import com.autocraft.craft.Recipes.Recipe;
import com.autocraft.goal.Requirement;
import com.autocraft.goal.Resources;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Works out the next concrete action toward having an item: craft something, smelt something, gather a raw
 * material, or nothing (already have it). Re-run after every action — inventory is the only state.
 */
public final class Planner {
	public sealed interface Step permits Craft, Smelt, Gather, Done {}

	public record Craft(Recipe recipe) implements Step {}

	public record Smelt(Item input, int count) implements Step {}

	public record Gather(Requirement requirement) implements Step {}

	public record Done() implements Step {}

	private static final Done DONE = new Done();
	/** Gather a little extra of cheap materials so one trip covers the whole tool chain. */
	private static final int MIN_LOGS = 6;
	private static final int MIN_STONE = 16;

	private final LocalPlayer p;
	private final Requirement.Dim dimension;
	private final boolean tableNearby;
	private final boolean furnaceNearby;

	public Planner(LocalPlayer p, Requirement.Dim dimension, boolean tableNearby, boolean furnaceNearby) {
		this.p = p;
		this.dimension = dimension;
		this.tableNearby = tableNearby;
		this.furnaceNearby = furnaceNearby;
	}

	/** Next step toward carrying {@code count} of {@code want}. */
	public Step next(Item want, int count) {
		int missing = count - InvUtil.count(p, s -> s.is(want));
		int missing = count - InvUtil.count(p, s -> s.is(want) && !InvUtil.nearlyBroken(s));
		if (want == Items.IRON_INGOT) return ingot(Items.RAW_IRON, "iron", missing);
		if (want == Items.GOLD_INGOT) return ingot(Items.RAW_GOLD, "gold", missing);
		Recipe r = Recipes.forOutput(want);
		if (r == null) throw new IllegalArgumentException("no recipe for " + want);
		return craft(r, missing);
	}

	/** Next step toward a boat of whatever wood we have most planks/logs of. */
	public Step boat() {
		if (InvUtil.find(p, s -> s.is(ItemTags.BOATS)) >= 0) return DONE;
		for (int i = 0; i < InvUtil.MAIN_SIZE; i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (s.is(ItemTags.PLANKS) && InvUtil.count(p, x -> x.is(s.getItem())) >= 5) {
				Recipe boat = Recipes.boatFrom(s.getItem());
				if (boat != null) return withTable(boat);
			}
		}
		int log = InvUtil.find(p, Ingredient.LOG.test);
		if (log >= 0) {
			Recipe planks = Recipes.planksFrom(p.getInventory().getItem(log).getItem());
			if (planks != null) return new Craft(planks);
		}
		return new Gather(Resources.get("log", 2));
	}

	private Step craft(Recipe r, int missingOutput) {
		int batches = (missingOutput + r.count() - 1) / r.count();
		Set<Ingredient> seen = new HashSet<>();
		for (Ingredient ing : r.key().values()) {
			if (!seen.add(ing)) continue;
			Step s = ingredient(ing, r.amountOf(ing) * batches);
			if (!(s instanceof Done)) return s;
		}
		return withTable(r);
	}

	private Step withTable(Recipe r) {
		if (!r.fits2x2() && !tableNearby && InvUtil.count(p, s -> s.is(Items.CRAFTING_TABLE)) == 0) {
			Step t = next(Items.CRAFTING_TABLE, 1);
			if (!(t instanceof Done)) return t;
		}
		return new Craft(r);
	}

	private Step ingredient(Ingredient ing, int needed) {
		if (InvUtil.count(p, ing.test) >= needed) return DONE;
		return switch (ing) {
			case PLANKS -> planks(needed);
			case STICK -> next(Items.STICK, needed);
			case IRON -> next(Items.IRON_INGOT, needed);
			case GOLD -> next(Items.GOLD_INGOT, needed);
			case STONE -> new Gather(stoneMaterial(Math.max(needed, MIN_STONE)));
			case DIAMOND -> new Gather(Resources.get("diamond", needed));
			case FLINT -> new Gather(Resources.get("flint", needed));
			case LOG -> new Gather(Resources.get("log", Math.max(needed, MIN_LOGS)));
		};
	}

	private Step planks(int needed) {
		int have = InvUtil.count(p, Ingredient.PLANKS.test);
		if (have >= needed) return DONE;
		int log = InvUtil.find(p, Ingredient.LOG.test);
		if (log >= 0) {
			Recipe r = Recipes.planksFrom(p.getInventory().getItem(log).getItem());
			if (r != null) return new Craft(r);
		}
		int logs = (needed - have + 3) / 4;
		return new Gather(Resources.get("log", Math.max(logs, MIN_LOGS)));
	}

	private Step ingot(Item raw, String resource, int missing) {
		if (InvUtil.count(p, s -> s.is(raw)) < missing) return new Gather(Resources.get(resource, missing));
		if (!furnaceNearby && InvUtil.count(p, s -> s.is(Items.FURNACE)) == 0) {
			Step f = next(Items.FURNACE, 1);
			if (!(f instanceof Done)) return f;
		}
		if (Smelter.fuelValue(p) < missing) {
			return new Gather(Resources.get("coal", (missing + 7) / 8));
		}
		return new Smelt(raw, missing);
	}

	/** Cobblestone, cobbled deepslate or blackstone: anything stone tools and furnaces accept. */
	private Requirement stoneMaterial(int amount) {
		Requirement cobble = Resources.get("cobblestone", amount);
		return new Requirement("cobblestone", Ingredient.STONE.test,
				Set.of(Blocks.STONE, Blocks.COBBLESTONE, Blocks.DEEPSLATE, Blocks.COBBLED_DEEPSLATE, Blocks.BLACKSTONE),
				amount, cobble.strategy(), cobble.preferredY(), dimension);
	}
}
