package com.autocraft.craft;

import com.autocraft.goal.Requirement;
import com.autocraft.goal.Requirement.Dim;
import com.autocraft.goal.Resources;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;

/**
 * Which items the bot can get by mining, and as which requirement. Named resources (ores, logs, sand...)
 * come first; otherwise a block that drops itself, isn't made by any recipe (so it occurs naturally) and
 * isn't on the "needs silk touch / drops something else" list.
 */
public final class Gatherable {
	/** Mining these gives something else (or nothing) without silk touch. */
	private static final Set<Block> NOT_SELF_DROPPING = Set.of(
			Blocks.GLASS, Blocks.GLASS_PANE, Blocks.ICE, Blocks.PACKED_ICE, Blocks.BLUE_ICE, Blocks.STONE, Blocks.DEEPSLATE,
			Blocks.GRASS_BLOCK, Blocks.MYCELIUM, Blocks.PODZOL, Blocks.GLOWSTONE, Blocks.SEA_LANTERN, Blocks.BOOKSHELF,
			Blocks.CLAY, Blocks.SNOW_BLOCK, Blocks.SNOW, Blocks.MELON, Blocks.TURTLE_EGG, Blocks.BEE_NEST, Blocks.BEEHIVE,
			Blocks.SPAWNER, Blocks.BUDDING_AMETHYST, Blocks.AMETHYST_CLUSTER, Blocks.SHORT_GRASS, Blocks.TALL_GRASS, Blocks.FERN,
			Blocks.LARGE_FERN, Blocks.DEAD_BUSH, Blocks.SEAGRASS, Blocks.COBWEB, Blocks.GILDED_BLACKSTONE, Blocks.SCULK_SENSOR,
			Blocks.REINFORCED_DEEPSLATE, Blocks.FARMLAND, Blocks.DIRT_PATH, Blocks.INFESTED_STONE, Blocks.INFESTED_DEEPSLATE);
	/** Gather a little extra of cheap staples so one trip covers several recipes. */
	private static final Map<Item, Integer> MIN_BATCH = Map.of(
			Items.COBBLESTONE, 16, Items.RAW_IRON, 5, Items.RAW_GOLD, 5, Items.RAW_COPPER, 5, Items.COAL, 4, Items.SAND, 8);
	private static final int MIN_LOGS = 6;
	/** Rough relative effort, used by the planner to compare gathering with crafting/smelting. */
	static final double NAMED_COST = 8;
	static final double GENERIC_COST = 20;
	static final double OTHER_DIMENSION_PENALTY = 40;

	private Gatherable() {}

	/**
	 * Requirement for having {@code total} of {@code item} in the inventory by mining, or null.
	 *
	 * @param flexible any item of the same family would do (e.g. any log for "some planks")
	 */
	public static @Nullable Requirement requirementFor(Item item, int total, Dim here, boolean flexible) {
		if (flexible && item.getDefaultInstance().is(ItemTags.LOGS)) {
			return here == Dim.NETHER
					? new Requirement("stem", s -> s.is(ItemTags.LOGS), Set.of(Blocks.CRIMSON_STEM, Blocks.WARPED_STEM),
							Math.max(total, MIN_LOGS), Requirement.Strategy.ANYWHERE, 0, Dim.NETHER)
					: Resources.get("log", Math.max(total, MIN_LOGS));
		}
		String path = BuiltInRegistries.ITEM.getKey(item).getPath();
		if (Resources.isNamed(path)) {
			Requirement named = Resources.get(path, total);
			return named.withAmount(Math.max(total, MIN_BATCH.getOrDefault(item, 0)));
		}
		Block block = Block.byItem(item);
		if (block == Blocks.AIR || !isNatural(block, item)) return null;
		return Resources.get(path, Math.max(total, MIN_BATCH.getOrDefault(item, 0)));
	}

	/** A block found in the world that drops itself when mined. */
	public static boolean isNatural(Block block, Item item) {
		if (NOT_SELF_DROPPING.contains(block) || block.defaultBlockState().is(BlockTags.LEAVES)) return false;
		if (BuiltInRegistries.BLOCK.getKey(block).getPath().contains("_ore")) return false; // ores drop raw items: named resources
		return !RecipeBook.get().isMade(item);
	}

	static double cost(Requirement r, Dim here) {
		double base = Resources.isNamed(r.name()) || r.name().equals("stem") ? NAMED_COST : GENERIC_COST;
		return r.dimension() == here ? base : base + OTHER_DIMENSION_PENALTY;
	}

	static boolean isFlexibleGroup(ItemStack s) {
		return s.is(ItemTags.LOGS) || s.is(ItemTags.PLANKS);
	}
}
