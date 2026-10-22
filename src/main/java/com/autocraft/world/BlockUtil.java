package com.autocraft.world;

import com.autocraft.build.StructureRegistry;
import com.autocraft.control.InvUtil;
import com.autocraft.goal.Requirement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Block classification shared by the pathfinder, miner and behaviors. Reads only (plus the built-structure registry). */
public final class BlockUtil {
	private BlockUtil() {}

	public static boolean isWater(BlockState s) {
		return s.getFluidState().is(FluidTags.WATER);
	}

	public static boolean isLava(BlockState s) {
		return s.getFluidState().is(FluidTags.LAVA);
	}

	/** Touching or standing in this hurts or traps the player. */
	public static boolean isDangerous(BlockState s) {
		return isLava(s)
				|| s.is(BlockTags.FIRE)
				|| s.is(Blocks.MAGMA_BLOCK)
				|| s.is(BlockTags.CAMPFIRES)
				|| s.is(Blocks.CACTUS)
				|| s.is(Blocks.SWEET_BERRY_BUSH)
				|| s.is(Blocks.WITHER_ROSE)
				|| s.is(Blocks.POWDER_SNOW)
				|| s.is(Blocks.COBWEB)
				|| s.is(Blocks.POINTED_DRIPSTONE);
	}

	/** The player's body can occupy this block (air, plants, water) without being hurt or teleported. */
	public static boolean isPassable(BlockGetter level, BlockPos pos, BlockState s) {
		if (isDangerous(s) || s.is(Blocks.NETHER_PORTAL) || s.is(Blocks.END_PORTAL)) return false;
		if (!s.getFluidState().isEmpty() && !isWater(s)) return false;
		return s.getCollisionShape(level, pos).isEmpty();
	}

	/** The player can stand on top of this block. */
	public static boolean canStandOn(BlockGetter level, BlockPos pos, BlockState s) {
		if (isDangerous(s)) return false;
		VoxelShape shape = s.getCollisionShape(level, pos);
		if (shape.isEmpty()) return false;
		double top = shape.max(Direction.Axis.Y);
		return top >= 0.8 && top <= 1.0
				&& shape.min(Direction.Axis.X) <= 0.1 && shape.max(Direction.Axis.X) >= 0.9
				&& shape.min(Direction.Axis.Z) <= 0.1 && shape.max(Direction.Axis.Z) >= 0.9;
	}

	/** Blocks the bot must never break: containers, beds, portals, and anything unbreakable. */
	public static boolean isProtected(BlockState s) {
		return s.getBlock() instanceof BaseEntityBlock
				|| s.is(BlockTags.BEDS)
				|| s.is(Blocks.NETHER_PORTAL)
				|| s.is(Blocks.END_PORTAL_FRAME)
				|| s.is(Blocks.BEDROCK);
	}

	/**
	 * Safe to mine: solid, breakable, not protected, not inside something the bot built, not part of a lit
	 * portal frame, and no lava beside or above that would pour into the gap (lava below can't flow up).
	 */
	public static boolean isBreakable(BlockGetter level, BlockPos pos, BlockState s) {
		if (s.isAir() || s.getDestroySpeed(level, pos) < 0 || isProtected(s)) return false;
		if (!s.getFluidState().isEmpty() && s.canBeReplaced()) return false;
		if (level instanceof Level l && StructureRegistry.protects(pos, Requirement.Dim.of(l))) return false;
		for (Direction d : Direction.values()) {
			BlockState n = level.getBlockState(pos.relative(d));
			if (d != Direction.DOWN && isLava(n)) return false;
			if (n.is(Blocks.NETHER_PORTAL)) return false;
		}
		return true;
	}

	public static boolean touchesLava(BlockGetter level, BlockPos pos) {
		for (Direction d : Direction.values()) {
			if (isLava(level.getBlockState(pos.relative(d)))) return true;
		}
		return false;
	}

	public static boolean touchesWater(BlockGetter level, BlockPos pos) {
		for (Direction d : Direction.values()) {
			if (d != Direction.DOWN && isWater(level.getBlockState(pos.relative(d)))) return true;
		}
		return false;
	}

	/** Sand/gravel/etc. resting on top of {@code pos} will fall when it is mined. */
	public static boolean hasFallingBlockAbove(BlockGetter level, BlockPos pos) {
		return level.getBlockState(pos.above()).getBlock() instanceof FallingBlock;
	}

	/** Ticks to break {@code s} with the best tool in the inventory (vanilla formula, no enchant/effect bonuses). */
	public static int breakTicks(Player p, BlockGetter level, BlockPos pos, BlockState s) {
		float hardness = s.getDestroySpeed(level, pos);
		if (hardness < 0) return Integer.MAX_VALUE;
		if (hardness == 0) return 1;
		float speed = InvUtil.bestSpeed(p, s);
		boolean harvest = InvUtil.canHarvest(p, s);
		float perTick = speed / hardness / (harvest ? 30.0F : 100.0F);
		return (int) Math.ceil(1.0F / perTick);
	}
}
