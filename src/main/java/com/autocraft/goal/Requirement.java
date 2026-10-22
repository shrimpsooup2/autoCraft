package com.autocraft.goal;

import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * One thing to get: which items count toward it, which blocks drop it and where to look — or, for crafted and
 * smelted items, which item the recipe planner has to make.
 *
 * @param name       display / lookup name
 * @param counts     items that count toward {@code amount}
 * @param sources    blocks that drop the item when mined (empty for crafted items)
 * @param amount     how many items are wanted
 * @param strategy   where to explore when no source block is in range
 * @param preferredY Y level to explore at for {@link Strategy#UNDERGROUND}
 * @param dimension  dimension the sources are found in
 * @param biomes     biomes where the sources are found or are most common (empty = anywhere); exploration
 *                   heads for the nearest loaded one
 * @param product    the item to craft/smelt, or null when the requirement is mined
 */
public record Requirement(String name, Predicate<ItemStack> counts, Set<Block> sources, int amount, Strategy strategy, int preferredY,
		Dim dimension, Set<ResourceKey<Biome>> biomes, @Nullable Item product) {
	/** Mined, no biome preference. */
	public Requirement(String name, Predicate<ItemStack> counts, Set<Block> sources, int amount, Strategy strategy, int preferredY, Dim dimension) {
		this(name, counts, sources, amount, strategy, preferredY, dimension, Set.of(), null);
	}

	/** {@code amount} of an item made by crafting or smelting (raw materials are planned by the recipe planner). */
	public static Requirement crafted(String name, Item item, int amount) {
		return new Requirement(name, s -> s.is(item), Set.of(), amount, Strategy.ANYWHERE, 0, Dim.OVERWORLD, Set.of(), item);
	}

	public boolean isCrafted() {
		return product != null;
	}

	public enum Strategy {
		/** Walk the surface (logs, sand, clay). */
		SURFACE,
		/** Go to {@code preferredY}, then tunnel (ores). */
		UNDERGROUND,
		/** Whatever level the bot is on (stone, dirt). */
		ANYWHERE
	}

	public enum Dim {
		OVERWORLD, NETHER;

		public static Dim of(Level level) {
			return level.dimension() == Level.NETHER ? NETHER : OVERWORLD;
		}
	}

	public boolean isSource(BlockState state) {
		return sources.contains(state.getBlock());
	}

	public Requirement withAmount(int newAmount) {
		return new Requirement(name, counts, sources, newAmount, strategy, preferredY, dimension, biomes, product);
	}

	public Requirement withBiomes(Set<ResourceKey<Biome>> preferred) {
		return new Requirement(name, counts, sources, amount, strategy, preferredY, dimension, preferred, product);
	}

	@Override
	public String toString() {
		return amount + " " + name + (dimension == Dim.NETHER && product == null ? " (nether)" : "");
	}
}
