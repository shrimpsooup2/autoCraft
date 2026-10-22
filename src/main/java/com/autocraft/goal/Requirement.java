package com.autocraft.goal;

import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One thing to gather: which items count toward it, which blocks drop it and where to look.
 *
 * @param name       display / lookup name
 * @param counts     items that count toward {@code amount}
 * @param sources    blocks that drop the item when mined
 * @param amount     how many items are wanted
 * @param strategy   where to explore when no source block is in range
 * @param preferredY Y level to explore at for {@link Strategy#UNDERGROUND}
 * @param dimension  dimension the sources are found in
 * @param biomes     biomes where the sources are found or are most common (empty = anywhere); exploration
 *                   heads for the nearest loaded one
 */
public record Requirement(String name, Predicate<ItemStack> counts, Set<Block> sources, int amount, Strategy strategy, int preferredY,
		Dim dimension, Set<ResourceKey<Biome>> biomes) {
	/** No biome preference. */
	public Requirement(String name, Predicate<ItemStack> counts, Set<Block> sources, int amount, Strategy strategy, int preferredY, Dim dimension) {
		this(name, counts, sources, amount, strategy, preferredY, dimension, Set.of());
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
		return new Requirement(name, counts, sources, newAmount, strategy, preferredY, dimension, biomes);
	}

	public Requirement withBiomes(Set<ResourceKey<Biome>> preferred) {
		return new Requirement(name, counts, sources, amount, strategy, preferredY, dimension, preferred);
	}

	@Override
	public String toString() {
		return amount + " " + name + (dimension == Dim.NETHER ? " (nether)" : "");
	}
}
