package com.autocraft.schematic;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * A structure to build: block states at positions relative to its minimum corner. Air cells are included
 * where the space must be cleared; cells not listed are "don't care" (left as they are).
 *
 * @param farm     set when the structure produces a resource and how to harvest it
 * @param baseSpot where to set the storage base after building (starter base), relative
 * @param builtIn  shipped with the mod (allowed to swap oak for the wood we have)
 */
public record Schematic(String name, Vec3i size, List<Cell> cells, @Nullable Farm farm, @Nullable BlockPos baseSpot, boolean builtIn) {
	public record Cell(BlockPos pos, BlockState state) {}

	/**
	 * What a farm yields and how to harvest it.
	 *
	 * @param provides resource name, or {@link #HOSTILE_MOBS} for mob farms (drops of the common hostile mobs)
	 * @param output   harvest cell for {@link Mode#COBBLE}, landing cell for {@link Mode#MOBS}, relative
	 * @param stand    where to stand while harvesting {@link Mode#COBBLE} / waiting at {@link Mode#MOBS}, relative
	 */
	public record Farm(String provides, Mode mode, @Nullable BlockPos output, @Nullable BlockPos stand) {
		public static final String HOSTILE_MOBS = "hostile_mobs";

		public enum Mode {
			/** Mine the cobblestone that keeps forming at {@code output}. */
			COBBLE,
			/** Break sugar cane/bamboo above the bottom block. */
			CANE,
			/** Chop the trees that grow from the saplings, replant. */
			TREE,
			/** Break fully grown crops, replant. */
			CROP,
			/** Automatic farm: take the product out of its chests/barrels/hoppers. */
			CONTAINERS,
			/** Mob farm: wait at {@code stand}, finish off what survives the fall at {@code output}, pick up the drops. */
			MOBS
		}
	}

	public int nonAirCount() {
		int n = 0;
		for (Cell c : cells) {
			if (!c.state.isAir()) n++;
		}
		return n;
	}
}
