package com.autocraft.goal;

import com.autocraft.goal.Requirement.Strategy;
import java.util.Arrays;
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
		ore("diamond", Items.DIAMOND, -58, Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE);
		ore("iron", Items.RAW_IRON, 16, Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE);
		ore("gold", Items.RAW_GOLD, -16, Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE);
		ore("coal", Items.COAL, 48, Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE);
		ore("copper", Items.RAW_COPPER, 48, Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE);
		ore("redstone", Items.REDSTONE, -58, Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE);
		ore("lapis", Items.LAPIS_LAZULI, 0, Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE);
		ore("emerald", Items.EMERALD, 100, Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE);
		ore("obsidian", Items.OBSIDIAN, -50, Blocks.OBSIDIAN);
		KNOWN.put("log", () -> new Requirement("log", s -> s.is(ItemTags.LOGS), blocksInTag(), 0, Strategy.SURFACE, 0));
		simple("cobblestone", Items.COBBLESTONE, Strategy.ANYWHERE, Blocks.STONE, Blocks.COBBLESTONE);
		simple("cobbled_deepslate", Items.COBBLED_DEEPSLATE, Strategy.UNDERGROUND, Blocks.DEEPSLATE, Blocks.COBBLED_DEEPSLATE);
		simple("dirt", Items.DIRT, Strategy.SURFACE, Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.COARSE_DIRT);
		simple("sand", Items.SAND, Strategy.SURFACE, Blocks.SAND);
		simple("gravel", Items.GRAVEL, Strategy.SURFACE, Blocks.GRAVEL);
		simple("flint", Items.FLINT, Strategy.SURFACE, Blocks.GRAVEL);
		simple("clay", Items.CLAY_BALL, Strategy.SURFACE, Blocks.CLAY);
		alias("wood", "log");
		alias("stone", "cobblestone");
		alias("raw_iron", "iron");
		alias("raw_gold", "gold");
		alias("raw_copper", "copper");
		alias("lapis_lazuli", "lapis");
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
		return new Requirement(key, s -> s.is(item), Set.of(block), amount, Strategy.ANYWHERE, 0);
	}

	public static Set<String> names() {
		return KNOWN.keySet();
	}

	private static void ore(String name, Item drop, int y, Block... blocks) {
		Set<Block> set = Set.of(blocks);
		KNOWN.put(name, () -> new Requirement(name, s -> s.is(drop), set, 0, Strategy.UNDERGROUND, y));
	}

	private static void simple(String name, Item drop, Strategy strategy, Block... blocks) {
		Set<Block> set = Set.of(blocks);
		int y = strategy == Strategy.UNDERGROUND ? -20 : 0;
		KNOWN.put(name, () -> new Requirement(name, s -> s.is(drop), set, 0, strategy, y));
	}

	private static void alias(String alias, String target) {
		KNOWN.put(alias, () -> KNOWN.get(target).get());
	}

	/** Log blocks resolved from the synced tag (only valid once in a world). */
	private static Set<Block> blocksInTag() {
		return BuiltInRegistries.BLOCK.stream()
				.filter(b -> b.defaultBlockState().is(BlockTags.LOGS))
				.collect(Collectors.toUnmodifiableSet());
	}

	/** Human readable list for {@code !ac resources}. */
	public static String describe() {
		return String.join(", ", Arrays.asList(KNOWN.keySet().toArray(String[]::new)));
	}
}
