package com.autocraft.goal;

import com.autocraft.loot.LootBook;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * One thing to get: which items count toward it, which blocks drop it (or which mobs) and where to look — or,
 * for crafted, smelted and procedure-made items, which item the recipe planner has to make.
 *
 * @param name       display / lookup name
 * @param counts     items that count toward {@code amount}
 * @param sources    blocks that drop the item when mined (empty for crafted and hunted items)
 * @param amount     how many items are wanted
 * @param strategy   where to explore when no source block is in range
 * @param preferredY Y level to explore at for {@link Strategy#UNDERGROUND}
 * @param dimension  dimension the sources are found in
 * @param biomes     biomes where the sources are found or are most common (empty = anywhere); exploration
 *                   heads for the nearest loaded one
 * @param product    the item to make, or null when the requirement is mined or hunted
 * @param tool       what to mine the sources with (shears for leaves, anything but shears for string from cobwebs)
 * @param mobs       mobs whose drops count (hunted instead of mining when non-empty)
 */
public record Requirement(String name, Predicate<ItemStack> counts, Set<Block> sources, int amount, Strategy strategy, int preferredY,
		Dim dimension, Set<ResourceKey<Biome>> biomes, @Nullable Item product, LootBook.ToolUse tool, Set<EntityType<?>> mobs) {
	/** Mined with the best tool, no biome preference. */
	public Requirement(String name, Predicate<ItemStack> counts, Set<Block> sources, int amount, Strategy strategy, int preferredY, Dim dimension) {
		this(name, counts, sources, amount, strategy, preferredY, dimension, Set.of(), null, LootBook.ToolUse.ANY, Set.of());
	}

	/** {@code amount} of an item made by crafting, smelting, hunting or a special procedure (planned by the recipe planner). */
	public static Requirement crafted(String name, Item item, int amount) {
		return new Requirement(name, s -> s.is(item), Set.of(), amount, Strategy.ANYWHERE, 0, Dim.OVERWORLD, Set.of(), item, LootBook.ToolUse.ANY,
				Set.of());
	}

	/** Drops of {@code mobs}, looked for where the hints say. */
	public static Requirement hunted(String name, Item item, int amount, Set<EntityType<?>> mobs, Dim dimension, Set<ResourceKey<Biome>> biomes) {
		return new Requirement(name, s -> s.is(item), Set.of(), amount, Strategy.SURFACE, 0, dimension, biomes, null, LootBook.ToolUse.ANY, mobs);
	}

	public boolean isCrafted() {
		return product != null;
	}

	public boolean isHunted() {
		return !mobs.isEmpty();
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
		return new Requirement(name, counts, sources, newAmount, strategy, preferredY, dimension, biomes, product, tool, mobs);
	}

	public Requirement withBiomes(Set<ResourceKey<Biome>> preferred) {
		return new Requirement(name, counts, sources, amount, strategy, preferredY, dimension, preferred, product, tool, mobs);
	}

	@Override
	public String toString() {
		return amount + " " + name + (dimension == Dim.NETHER && product == null ? " (nether)" : "");
	}
}
