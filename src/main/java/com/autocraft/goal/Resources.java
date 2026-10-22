package com.autocraft.goal;

import com.autocraft.goal.Requirement.Dim;
import com.autocraft.goal.Requirement.Strategy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Named resources the bot knows how to find. Anything else falls back to "mine the block with the same id
 * as the item" (e.g. {@code minecraft:oak_log}, {@code sand}).
 */
public final class Resources {
	private static final Map<String, Supplier<Requirement>> KNOWN = new LinkedHashMap<>();

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
		alias("stone", "cobblestone");
		alias("raw_iron", "iron");
		alias("raw_gold", "gold");
		alias("raw_copper", "copper");
		alias("lapis_lazuli", "lapis");
		alias("nether_quartz", "quartz");
		alias("debris", "ancient_debris");
		alias("glowstone_dust", "glowstone");
	}

	private Resources() {}

	/** Requirement for {@code amount} of {@code name}, or null if nothing can produce it. */
	public static Requirement get(String name, int amount) {
		String key = name.toLowerCase().replace("minecraft:", "");
		Supplier<Requirement> known = KNOWN.get(key);
		if (known != null) return known.get().withAmount(amount);
		Identifier id = Identifier.tryParse(name.contains(":") ? name : "minecraft:" + key);
		if (id == null) return null;
		Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
		Block block = BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
		if (item == null || item == Items.AIR || block == null || block == Blocks.AIR) return null;
		return new Requirement(key, s -> s.is(item), Set.of(block), amount, Strategy.ANYWHERE, 0, Dim.OVERWORLD);
	}

	public static Set<String> names() {
		return KNOWN.keySet();
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
