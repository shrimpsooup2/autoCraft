package com.autocraft.behavior;

import com.autocraft.Bot;
import com.autocraft.build.StructureRegistry;
import com.autocraft.goal.Requirement.Dim;
import com.autocraft.world.BlockUtil;
import java.util.List;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Where a respawn bed can go near a spot: two cells side by side on flat solid ground, dry (no water or lava touching
 * them), room above both (vanilla calls a bed with a block over it "obstructed" and won't use it), no hostile mob close
 * by, outside our builds and farms, and a cell behind the foot to stand in — facing the head from there is what turns
 * a placed bed that way.
 */
final class BedSite {
	/** Search this far around the spot horizontally, and this far up and down. */
	private static final int RADIUS = 8;
	private static final int DEPTH = 3;
	/** No hostile mob this close to the bed (it would be in the way, and a respawn there walks into it). */
	private static final double MOB_CLEARANCE = 6;

	/** Foot of the bed, the way it points (head = foot + facing), and the cell behind the foot to place it from. */
	record Site(BlockPos foot, Direction facing, BlockPos stand) {
		BlockPos head() {
			return foot.relative(facing);
		}
	}

	private BedSite() {}

	/** The fitting site nearest to {@code around}, skipping {@code rejected} feet; null when none fits. */
	static @Nullable Site find(Bot bot, BlockPos around, Set<BlockPos> rejected) {
		ClientLevel level = bot.level();
		Dim dim = bot.dimension();
		List<Entity> mobs = level.getEntities((Entity) null, new AABB(around).inflate(RADIUS + MOB_CLEARANCE),
				e -> e instanceof Enemy && e.isAlive());
		Site best = null;
		double bestDist = Double.MAX_VALUE;
		for (int dy = -DEPTH; dy <= DEPTH; dy++) {
			for (int dx = -RADIUS; dx <= RADIUS; dx++) {
				for (int dz = -RADIUS; dz <= RADIUS; dz++) {
					BlockPos foot = around.offset(dx, dy, dz);
					double dist = foot.distSqr(around);
					if (dist >= bestDist || rejected.contains(foot) || !bedCell(level, foot) || crowded(mobs, foot)) continue;
					for (Direction facing : Direction.Plane.HORIZONTAL) {
						BlockPos head = foot.relative(facing), stand = foot.relative(facing.getOpposite());
						if (!bedCell(level, head) || !standable(level, stand)) continue;
						if (StructureRegistry.protects(foot, dim) || StructureRegistry.protects(head, dim)) continue;
						best = new Site(foot.immutable(), facing, stand.immutable());
						bestDist = dist;
						break;
					}
				}
			}
		}
		return best;
	}

	/** Both halves still have room (nothing flowed, fell or was built into them since the site was picked). */
	static boolean stillFits(ClientLevel level, Site s) {
		return bedCell(level, s.foot()) && bedCell(level, s.head());
	}

	/**
	 * Half a bed can go here: empty (or grass, a snow layer), no fluid, room above to get in, flat solid floor that isn't
	 * hot or another bed, nothing wet or burning beside it.
	 */
	private static boolean bedCell(ClientLevel level, BlockPos pos) {
		BlockState at = level.getBlockState(pos);
		if (!at.canBeReplaced() || !at.getFluidState().isEmpty()) return false;
		BlockPos above = pos.above();
		BlockState over = level.getBlockState(above);
		if (!BlockUtil.isPassable(level, above, over) || !over.getFluidState().isEmpty()) return false;
		BlockPos below = pos.below();
		BlockState floor = level.getBlockState(below);
		if (!floor.isFaceSturdy(level, below, Direction.UP) || BlockUtil.isDangerous(floor) || floor.is(BlockTags.BEDS)) return false;
		return !BlockUtil.touchesWater(level, pos) && !BlockUtil.touchesLava(level, pos) && !BlockUtil.touchesLava(level, above);
	}

	/** Feet and head fit here, dry, on something to stand on. */
	private static boolean standable(ClientLevel level, BlockPos pos) {
		BlockState feet = level.getBlockState(pos), head = level.getBlockState(pos.above()), floor = level.getBlockState(pos.below());
		return BlockUtil.isPassable(level, pos, feet) && feet.getFluidState().isEmpty() && BlockUtil.isPassable(level, pos.above(), head)
				&& head.getFluidState().isEmpty() && BlockUtil.canStandOn(level, pos.below(), floor);
	}

	private static boolean crowded(List<Entity> mobs, BlockPos foot) {
		for (Entity e : mobs) {
			if (e.distanceToSqr(Vec3.atCenterOf(foot)) < MOB_CLEARANCE * MOB_CLEARANCE) return true;
		}
		return false;
	}
}
