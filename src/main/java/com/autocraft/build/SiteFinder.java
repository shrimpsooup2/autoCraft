package com.autocraft.build;

import com.autocraft.Bot;
import com.autocraft.goal.Requirement.Dim;
import com.autocraft.path.Navigator;
import com.autocraft.schematic.Schematic;
import com.autocraft.world.BlockUtil;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Picks where to put an automatically built structure (farms): near the player, on solid ground, no
 * fluids or existing structures in the way, and as little digging as possible.
 */
public final class SiteFinder {
	private static final int RADIUS = 16;
	private static final int STEP = 2;

	private SiteFinder() {}

	public static @Nullable BlockPos find(Bot bot, Schematic s) {
		ClientLevel level = bot.level();
		BlockPos feet = Navigator.feet(bot.player());
		Dim dim = bot.dimension();
		BlockPos best = null;
		int bestScore = Integer.MAX_VALUE;
		for (int dx = -RADIUS; dx <= RADIUS; dx += STEP) {
			for (int dz = -RADIUS; dz <= RADIUS; dz += STEP) {
				for (int dy = -2; dy <= 2; dy++) {
					BlockPos origin = feet.offset(dx, dy - 1, dz); // bottom layer sits at ground level
					int score = score(level, s, origin, dim);
					if (score < 0) continue;
					score += Math.abs(dx) + Math.abs(dz) + Math.abs(dy) * 2;
					if (score < bestScore) {
						bestScore = score;
						best = origin;
					}
				}
			}
		}
		return best;
	}

	/** Blocks to change, or -1 when the site can't work. */
	private static int score(ClientLevel level, Schematic s, BlockPos origin, Dim dim) {
		int changes = 0;
		for (Schematic.Cell cell : s.cells()) {
			BlockPos pos = origin.offset(cell.pos());
			if (!level.isLoaded(pos) || StructureRegistry.protects(pos, dim)) return -1;
			BlockState world = level.getBlockState(pos);
			if (!world.getFluidState().isEmpty() && !BuildPlan.isFluid(cell.state())) return -1;
			if (!world.isAir() && !BlockUtil.isBreakable(level, pos, world) && !BuildPlan.matches(world, cell.state())) return -1;
			if (!BuildPlan.matches(world, cell.state())) changes++;
			if (cell.pos().getY() == 0 && !cell.state().isAir()) {
				BlockPos below = pos.below();
				if (!BlockUtil.canStandOn(level, below, level.getBlockState(below))) return -1; // needs real ground
			}
		}
		return changes;
	}
}
