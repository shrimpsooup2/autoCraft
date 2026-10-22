package com.autocraft.path;

import java.util.List;
import net.minecraft.core.BlockPos;

/**
 * Where the pathfinder should end. Coordinates are the player's feet block. Heuristics are in ticks and
 * should not wildly overestimate (A* is run weighted, so mild overestimates only cost optimality).
 * Implementations are records so the navigator can tell "same goal" from "new goal" with equals().
 */
public interface Goal {
	/** Ticks to walk one block. */
	double WALK = 4.63;
	/** Ticks per block of vertical travel (digging down / pillaring up is slow). */
	double VERTICAL = 6.0;

	boolean isEnd(int x, int y, int z);

	double heuristic(int x, int y, int z);

	static double horizontal(int dx, int dz) {
		return Math.sqrt((double) dx * dx + (double) dz * dz) * WALK;
	}

	/** Stand exactly on this feet position. */
	record Block(BlockPos pos) implements Goal {
		@Override
		public boolean isEnd(int x, int y, int z) {
			return x == pos.getX() && y == pos.getY() && z == pos.getZ();
		}

		@Override
		public double heuristic(int x, int y, int z) {
			return horizontal(x - pos.getX(), z - pos.getZ()) + Math.abs(y - pos.getY()) * VERTICAL;
		}
	}

	/** Feet within {@code radius} blocks of {@code pos}. */
	record Near(BlockPos pos, int radius) implements Goal {
		@Override
		public boolean isEnd(int x, int y, int z) {
			return pos.distSqr(new BlockPos(x, y, z)) <= (double) radius * radius;
		}

		@Override
		public double heuristic(int x, int y, int z) {
			return Math.max(0, horizontal(x - pos.getX(), z - pos.getZ()) + Math.abs(y - pos.getY()) * VERTICAL - radius * WALK);
		}
	}

	/** Stand so that {@code target} touches the feet or head block: close enough to mine or open it. */
	record Adjacent(BlockPos target) implements Goal {
		@Override
		public boolean isEnd(int x, int y, int z) {
			int dx = Math.abs(x - target.getX()), dz = Math.abs(z - target.getZ());
			int dyFeet = target.getY() - y;
			if (dx + dz == 1) return dyFeet == 0 || dyFeet == 1;
			if (dx + dz == 0) return dyFeet == -1 || dyFeet == 2;
			return false;
		}

		@Override
		public double heuristic(int x, int y, int z) {
			return horizontal(x - target.getX(), z - target.getZ()) + Math.abs(y - target.getY()) * VERTICAL;
		}
	}

	/** Reach a Y level anywhere. */
	record YLevel(int y) implements Goal {
		@Override
		public boolean isEnd(int x, int y, int z) {
			return y == this.y;
		}

		@Override
		public double heuristic(int x, int y, int z) {
			return Math.abs(y - this.y) * VERTICAL;
		}
	}

	/** Reach a column at any height. */
	record XZ(int x, int z) implements Goal {
		@Override
		public boolean isEnd(int x, int y, int z) {
			return x == this.x && z == this.z;
		}

		@Override
		public double heuristic(int x, int y, int z) {
			return horizontal(x - this.x, z - this.z);
		}
	}

	/** Reach a column while staying at a Y level (tunnelling for ores). */
	record XYZ(int x, int y, int z) implements Goal {
		@Override
		public boolean isEnd(int x, int y, int z) {
			return x == this.x && z == this.z && y == this.y;
		}

		@Override
		public double heuristic(int x, int y, int z) {
			return horizontal(x - this.x, z - this.z) + Math.abs(y - this.y) * VERTICAL;
		}
	}

	/** Get at least {@code distance} blocks (horizontally) away from every threat. */
	record RunAway(List<BlockPos> threats, int distance) implements Goal {
		@Override
		public boolean isEnd(int x, int y, int z) {
			return minDistSq(x, z) >= (double) distance * distance;
		}

		@Override
		public double heuristic(int x, int y, int z) {
			return Math.max(0, distance - Math.sqrt(minDistSq(x, z))) * WALK;
		}

		private double minDistSq(int x, int z) {
			double best = Double.MAX_VALUE;
			for (BlockPos t : threats) {
				double dx = x - t.getX(), dz = z - t.getZ();
				best = Math.min(best, dx * dx + dz * dz);
			}
			return best;
		}
	}
}
