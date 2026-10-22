package com.autocraft.craft;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/** The handful of vanilla recipes the bot needs, hardcoded (26.3 data: data/minecraft/recipe/*.json). */
public final class Recipes {
	/** Ingredient categories; each knows how to recognise matching stacks. */
	public enum Ingredient {
		PLANKS(s -> s.is(ItemTags.PLANKS)),
		STICK(s -> s.is(Items.STICK)),
		STONE(s -> s.is(ItemTags.STONE_TOOL_MATERIALS)),
		IRON(s -> s.is(Items.IRON_INGOT)),
		GOLD(s -> s.is(Items.GOLD_INGOT)),
		DIAMOND(s -> s.is(Items.DIAMOND)),
		FLINT(s -> s.is(Items.FLINT)),
		LOG(s -> s.is(ItemTags.LOGS));

		public final Predicate<ItemStack> test;

		Ingredient(Predicate<ItemStack> test) {
			this.test = test;
		}
	}

	/**
	 * A shaped recipe. {@code pattern} rows use the characters of {@code key}; space = empty. Shapeless
	 * recipes are written as one row. {@code exact} narrows every ingredient to one item when the recipe
	 * needs a single wood type (planks from a specific log, boats).
	 */
	public record Recipe(Item output, int count, List<String> pattern, Map<Character, Ingredient> key, @Nullable Item exact) {
		public boolean fits2x2() {
			return pattern.size() <= 2 && pattern.stream().allMatch(row -> row.length() <= 2);
		}

		/** How many of {@code ingredient} one craft consumes. */
		public int amountOf(Ingredient ingredient) {
			int n = 0;
			for (String row : pattern) {
				for (char c : row.toCharArray()) {
					if (key.get(c) == ingredient) n++;
				}
			}
			return n;
		}

		public Predicate<ItemStack> matcher(Ingredient ingredient) {
			return exact == null ? ingredient.test : s -> s.is(exact);
		}
	}

	private static Recipe shaped(Item out, int count, List<String> pattern, Map<Character, Ingredient> key) {
		return new Recipe(out, count, pattern, key, null);
	}

	public static final Recipe STICK = shaped(Items.STICK, 4, List.of("P", "P"), Map.of('P', Ingredient.PLANKS));
	public static final Recipe CRAFTING_TABLE = shaped(Items.CRAFTING_TABLE, 1, List.of("PP", "PP"), Map.of('P', Ingredient.PLANKS));
	public static final Recipe FURNACE = shaped(Items.FURNACE, 1, List.of("CCC", "C C", "CCC"), Map.of('C', Ingredient.STONE));
	public static final Recipe SHIELD = shaped(Items.SHIELD, 1, List.of("PIP", "PPP", " P "), Map.of('P', Ingredient.PLANKS, 'I', Ingredient.IRON));
	public static final Recipe BUCKET = shaped(Items.BUCKET, 1, List.of("I I", " I "), Map.of('I', Ingredient.IRON));
	public static final Recipe FLINT_AND_STEEL = shaped(Items.FLINT_AND_STEEL, 1, List.of("IF"), Map.of('I', Ingredient.IRON, 'F', Ingredient.FLINT));
	public static final Recipe GOLDEN_BOOTS = shaped(Items.GOLDEN_BOOTS, 1, List.of("G G", "G G"), Map.of('G', Ingredient.GOLD));

	private static final List<Recipe> ALL = List.of(
			STICK, CRAFTING_TABLE, FURNACE, SHIELD, BUCKET, FLINT_AND_STEEL, GOLDEN_BOOTS,
			pickaxe(Items.WOODEN_PICKAXE, Ingredient.PLANKS), pickaxe(Items.STONE_PICKAXE, Ingredient.STONE),
			pickaxe(Items.IRON_PICKAXE, Ingredient.IRON), pickaxe(Items.DIAMOND_PICKAXE, Ingredient.DIAMOND),
			sword(Items.WOODEN_SWORD, Ingredient.PLANKS), sword(Items.STONE_SWORD, Ingredient.STONE),
			sword(Items.IRON_SWORD, Ingredient.IRON), sword(Items.DIAMOND_SWORD, Ingredient.DIAMOND));

	private Recipes() {}

	private static Recipe pickaxe(Item out, Ingredient head) {
		return shaped(out, 1, List.of("XXX", " S ", " S "), Map.of('X', head, 'S', Ingredient.STICK));
	}

	private static Recipe sword(Item out, Ingredient blade) {
		return shaped(out, 1, List.of("X", "X", "S"), Map.of('X', blade, 'S', Ingredient.STICK));
	}

	public static @Nullable Recipe forOutput(Item item) {
		for (Recipe r : ALL) {
			if (r.output == item) return r;
		}
		return null;
	}

	/** One log → 4 planks of the same wood. */
	public static @Nullable Recipe planksFrom(Item log) {
		Item planks = sibling(log, "_planks");
		return planks == null ? null : new Recipe(planks, 4, List.of("L"), Map.of('L', Ingredient.LOG), log);
	}

	/** Five planks of one wood → that wood's boat. */
	public static @Nullable Recipe boatFrom(Item planks) {
		Item boat = sibling(planks, "_boat");
		return boat == null ? null : new Recipe(boat, 1, List.of("P P", "PPP"), Map.of('P', Ingredient.PLANKS), planks);
	}

	/** oak_log / stripped_oak_wood / oak_planks → oak + suffix, if such an item exists. */
	private static @Nullable Item sibling(Item item, String suffix) {
		String path = BuiltInRegistries.ITEM.getKey(item).getPath().replace("stripped_", "");
		for (String end : new String[] {"_log", "_wood", "_stem", "_hyphae", "_planks"}) {
			if (path.endsWith(end)) {
				Identifier id = Identifier.withDefaultNamespace(path.substring(0, path.length() - end.length()) + suffix);
				Item found = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
				return found == Items.AIR ? null : found;
			}
		}
		return null;
	}
}
