package com.autocraft.goal;

import com.autocraft.craft.Gatherable;
import com.autocraft.craft.RecipeBook;
import com.autocraft.goal.Requirement.Dim;
import com.autocraft.goal.Requirement.Strategy;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;

/**
 * Named resources the bot knows how to find. Anything else falls back to "mine the block with the same id
 * as the item" (e.g. {@code minecraft:oak_log}, {@code sand}).
 */
public final class Resources {
	private static final Map<String, Supplier<Requirement>> KNOWN = new LinkedHashMap<>();
	/** Where a resource is found or most common; exploration heads for the nearest of these biomes. */
	private static final Map<String, Set<ResourceKey<Biome>>> BIOMES = new HashMap<>();

	static {
		// overworld ores: explore at the best Y for each
		ore("diamond", Items.DIAMOND, -58, Dim.OVERWORLD, Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE);
		ore("iron", Items.RAW_IRON, 16, Dim.OVERWORLD, Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE);
		ore("gold", Items.RAW_GOLD, -16, Dim.OVERWORLD, Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE);
		ore("coal", Items.COAL, 48, Dim.OVERWORLD, Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE);
		ore("copper", Items.RAW_COPPER, 48, Dim.OVERWORLD, Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE);
		ore("redstone", Items.REDSTONE, -58, Dim.OVERWORLD, Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE);
		ore("lapis", Items.LAPIS_LAZULI, 0, Dim.OVERWORLD, Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE);
		ore("emerald", Items.EMERALD, 100, Dim.OVERWORLD, Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE);
		ore("obsidian", Items.OBSIDIAN, -50, Dim.OVERWORLD, Blocks.OBSIDIAN);
		KNOWN.put("log", () -> new Requirement("log", s -> s.is(ItemTags.LOGS), overworldLogs(), 0, Strategy.SURFACE, 0, Dim.OVERWORLD));
		simple("cobblestone", Items.COBBLESTONE, Strategy.ANYWHERE, Dim.OVERWORLD, Blocks.STONE, Blocks.COBBLESTONE);
		simple("cobbled_deepslate", Items.COBBLED_DEEPSLATE, Strategy.UNDERGROUND, Dim.OVERWORLD, Blocks.DEEPSLATE, Blocks.COBBLED_DEEPSLATE);
		simple("dirt", Items.DIRT, Strategy.SURFACE, Dim.OVERWORLD, Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.COARSE_DIRT);
		simple("sand", Items.SAND, Strategy.SURFACE, Dim.OVERWORLD, Blocks.SAND);
		simple("gravel", Items.GRAVEL, Strategy.SURFACE, Dim.OVERWORLD, Blocks.GRAVEL);
		simple("flint", Items.FLINT, Strategy.SURFACE, Dim.OVERWORLD, Blocks.GRAVEL);
		simple("clay", Items.CLAY_BALL, Strategy.SURFACE, Dim.OVERWORLD, Blocks.CLAY);

		// nether
		ore("quartz", Items.QUARTZ, 40, Dim.NETHER, Blocks.NETHER_QUARTZ_ORE);
		ore("ancient_debris", Items.ANCIENT_DEBRIS, 15, Dim.NETHER, Blocks.ANCIENT_DEBRIS);
		ore("nether_gold", Items.GOLD_NUGGET, 40, Dim.NETHER, Blocks.NETHER_GOLD_ORE);
		simple("glowstone", Items.GLOWSTONE_DUST, Strategy.ANYWHERE, Dim.NETHER, Blocks.GLOWSTONE);
		simple("netherrack", Items.NETHERRACK, Strategy.ANYWHERE, Dim.NETHER, Blocks.NETHERRACK);
		simple("soul_sand", Items.SOUL_SAND, Strategy.ANYWHERE, Dim.NETHER, Blocks.SOUL_SAND);
		simple("soul_soil", Items.SOUL_SOIL, Strategy.ANYWHERE, Dim.NETHER, Blocks.SOUL_SOIL);
		simple("blackstone", Items.BLACKSTONE, Strategy.ANYWHERE, Dim.NETHER, Blocks.BLACKSTONE);
		simple("basalt", Items.BASALT, Strategy.ANYWHERE, Dim.NETHER, Blocks.BASALT);
		simple("magma", Items.MAGMA_BLOCK, Strategy.ANYWHERE, Dim.NETHER, Blocks.MAGMA_BLOCK);
		simple("nether_wart", Items.NETHER_WART, Strategy.ANYWHERE, Dim.NETHER, Blocks.NETHER_WART);
		simple("crimson_stem", Items.CRIMSON_STEM, Strategy.ANYWHERE, Dim.NETHER, Blocks.CRIMSON_STEM);
		simple("warped_stem", Items.WARPED_STEM, Strategy.ANYWHERE, Dim.NETHER, Blocks.WARPED_STEM);

		alias("wood", "log");
		alias("raw_iron", "iron");
		alias("raw_gold", "gold");
		alias("raw_copper", "copper");
		alias("lapis_lazuli", "lapis");
		alias("nether_quartz", "quartz");
		alias("debris", "ancient_debris");
		alias("glowstone_dust", "glowstone");
		alias("clay_ball", "clay");
		alias("gold_nugget", "nether_gold");
		alias("magma_block", "magma");

		biomes("sand", Biomes.DESERT, Biomes.BEACH);
		biomes("red_sand", Biomes.BADLANDS, Biomes.ERODED_BADLANDS, Biomes.WOODED_BADLANDS);
		biomes("clay", Biomes.SWAMP, Biomes.MANGROVE_SWAMP, Biomes.RIVER, Biomes.LUSH_CAVES);
		biomes("gold", Biomes.BADLANDS, Biomes.ERODED_BADLANDS, Biomes.WOODED_BADLANDS);
		biomes("emerald", Biomes.WINDSWEPT_HILLS, Biomes.WINDSWEPT_GRAVELLY_HILLS, Biomes.WINDSWEPT_FOREST, Biomes.MEADOW,
				Biomes.CHERRY_GROVE, Biomes.GROVE, Biomes.SNOWY_SLOPES, Biomes.JAGGED_PEAKS, Biomes.FROZEN_PEAKS, Biomes.STONY_PEAKS);
		biomes("cactus", Biomes.DESERT, Biomes.BADLANDS);
		biomes("mud", Biomes.MANGROVE_SWAMP);
		biomes("dark_oak_log", Biomes.DARK_FOREST);
		biomes("oak_log", Biomes.FOREST, Biomes.FLOWER_FOREST, Biomes.WINDSWEPT_FOREST, Biomes.PLAINS, Biomes.SWAMP, Biomes.WOODED_BADLANDS);
		biomes("jungle_log", Biomes.JUNGLE, Biomes.BAMBOO_JUNGLE, Biomes.SPARSE_JUNGLE);
		biomes("acacia_log", Biomes.SAVANNA, Biomes.SAVANNA_PLATEAU, Biomes.WINDSWEPT_SAVANNA);
		biomes("spruce_log", Biomes.TAIGA, Biomes.SNOWY_TAIGA, Biomes.OLD_GROWTH_PINE_TAIGA, Biomes.OLD_GROWTH_SPRUCE_TAIGA, Biomes.GROVE);
		biomes("birch_log", Biomes.BIRCH_FOREST, Biomes.OLD_GROWTH_BIRCH_FOREST);
		biomes("cherry_log", Biomes.CHERRY_GROVE);
		biomes("mangrove_log", Biomes.MANGROVE_SWAMP);
		biomes("pale_oak_log", Biomes.PALE_GARDEN);
		biomes("soul_sand", Biomes.SOUL_SAND_VALLEY);
		biomes("soul_soil", Biomes.SOUL_SAND_VALLEY);
		biomes("basalt", Biomes.BASALT_DELTAS);
		biomes("blackstone", Biomes.BASALT_DELTAS);
		biomes("magma", Biomes.BASALT_DELTAS);
		biomes("crimson_stem", Biomes.CRIMSON_FOREST);
		biomes("warped_stem", Biomes.WARPED_FOREST);
	}

	private Resources() {}

	/** A name the registry knows directly (ores, logs, aliases), as opposed to the any-block fallback. */
	public static boolean isNamed(String name) {
		return KNOWN.containsKey(name.toLowerCase().replace("minecraft:", ""));
	}

	/**
	 * What a job asks for: a mined resource if the name is one, else any item a crafting or smelting recipe
	 * makes (torches, glass, pistons, iron ingots...). Null if neither.
	 */
	public static @Nullable Requirement forJob(String name, int amount) {
		Requirement mined = get(name, amount);
		if (mined != null) return mined;
		Item item = item(name);
		if (item == null || !RecipeBook.get().isMade(item)) return null;
		return Requirement.crafted(BuiltInRegistries.ITEM.getKey(item).getPath(), item, amount);
	}

	/** Requirement for mining {@code amount} of {@code name}, or null if no block in the world drops it. */
	public static @Nullable Requirement get(String name, int amount) {
		String key = name.toLowerCase().replace("minecraft:", "");
		Supplier<Requirement> known = KNOWN.get(key);
		Requirement r;
		if (known != null) {
			r = known.get().withAmount(amount);
		} else {
			Item item = item(name);
			Block block = item == null ? null : Block.byItem(item);
			if (item == null || block == null || block == Blocks.AIR || block.defaultDestroyTime() < 0) return null;
			if (!Gatherable.isNatural(block, item)) return null; // made by recipes, or doesn't drop itself
			r = new Requirement(key, s -> s.is(item), Set.of(block), amount, Strategy.ANYWHERE, 0, Dim.OVERWORLD);
		}
		Set<ResourceKey<Biome>> biomes = BIOMES.get(r.name());
		return biomes == null ? r : r.withBiomes(biomes);
	}

	private static @Nullable Item item(String name) {
		String key = name.toLowerCase().replace("minecraft:", "");
		Identifier id = Identifier.tryParse(name.contains(":") ? name.toLowerCase() : "minecraft:" + key);
		if (id == null) return null;
		Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
		return item == Items.AIR ? null : item;
	}

	@SafeVarargs
	private static void biomes(String name, ResourceKey<Biome>... keys) {
		BIOMES.put(name, Set.of(keys));
	}

	/** Human readable list for {@code !ac resources}. */
	public static String describe() {
		return String.join(", ", KNOWN.keySet());
	}

	private static void ore(String name, Item drop, int y, Dim dim, Block... blocks) {
		Set<Block> set = Set.of(blocks);
		KNOWN.put(name, () -> new Requirement(name, s -> s.is(drop), set, 0, Strategy.UNDERGROUND, y, dim));
	}

	private static void simple(String name, Item drop, Strategy strategy, Dim dim, Block... blocks) {
		Set<Block> set = Set.of(blocks);
		int y = strategy == Strategy.UNDERGROUND ? -20 : 0;
		KNOWN.put(name, () -> new Requirement(name, s -> s.is(drop), set, 0, strategy, y, dim));
	}

	private static void alias(String alias, String target) {
		KNOWN.put(alias, () -> KNOWN.get(target).get());
	}

	/** Natural overworld log blocks resolved from the synced tag (only valid once in a world). */
	private static Set<Block> overworldLogs() {
		return BuiltInRegistries.BLOCK.stream()
				.filter(b -> b.defaultBlockState().is(BlockTags.OVERWORLD_NATURAL_LOGS))
				.collect(Collectors.toUnmodifiableSet());
	}
}
