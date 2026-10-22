package com.autocraft.path;

import java.util.List;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/**
 * One planned move: from the previous step's feet position to {@code dest}.
 *
 * @param dest   feet position after the move
 * @param type   how the executor performs it
 * @param breaks blocks to mine first, in order
 * @param place  block to place before moving (bridge floor / pillar block), or null
 */
public record Step(BlockPos dest, Type type, List<BlockPos> breaks, @Nullable BlockPos place) {
	public enum Type {
		/** Same level, cardinal (may bridge if {@code place} is set). */
		WALK,
		/** Same level, diagonal; never breaks or places. */
		DIAGONAL,
		/** One block up onto a neighbour. */
		ASCEND,
		/** One block down onto a neighbour (staircase). */
		DESCEND,
		/** Walk off an edge and drop 2+ blocks (onto solid or into water). */
		FALL,
		/** Mine the block underneath and drop into it. */
		DIG_DOWN,
		/** Jump and place a block underneath. */
		PILLAR,
		/** Swim straight up or down in water. */
		SWIM_VERTICAL
	}

	public boolean isFlatWalk() {
		return (type == Type.WALK || type == Type.DIAGONAL) && breaks.isEmpty() && place == null;
	}
}
