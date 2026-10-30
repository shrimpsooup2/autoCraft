package com.autocraft.path;

import com.autocraft.control.InvUtil;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Up out of a cave or a mine before a surface search (trees, animals, a biome): from a cave (or the deep end of a
 * portal) walking "the current heading" stayed underground for good, tunnelling a hundred blocks in nine minutes and
 * drowning in a flooded cave while looking for snowy slopes. With a pickaxe, straight up the own column (dig
 * overhead, jump, block under), not wherever a Y level is quickest to reach (that wound up through caves and
 * staircases); anywhere up when the column fails (bedrock overhead), has water or lava in it (dug up into a lake's
 * bed, it flooded the shaft and was stuck down there half an hour), or there's no pickaxe (by hand, any cave beats
 * digging).
 */
public final class SurfaceClimb {
	/** Further below the ground than this, a surface search heads up first. */
	private static final int SLACK = 6;

	private SurfaceClimb() {}

	/** Well below the ground at the own column; overworld only (the Nether's "surface" is its roof). */
	public static boolean below(ClientLevel level, BlockPos feet) {
		return level.dimension() == Level.OVERWORLD && feet.getY() < top(level, feet) - SLACK;
	}

	/** The way up: the own column's top when it can be dug straight up dry, else the surface level reached anyhow. */
	public static Goal goal(LocalPlayer p, ClientLevel level, BlockPos feet, boolean anywhere) {
		int top = top(level, feet);
		boolean straight = !anywhere && InvUtil.canHarvest(p, Blocks.STONE.defaultBlockState()) && dryColumn(level, feet, top);
		return straight ? new Goal.Near(new BlockPos(feet.getX(), top, feet.getZ()), 1) : new Goal.YLevel(top);
	}

	private static int top(ClientLevel level, BlockPos feet) {
		return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, feet.getX(), feet.getZ());
	}

	/** No water or lava anywhere in the column from above our head up to (and including) the surface block. */
	private static boolean dryColumn(ClientLevel level, BlockPos feet, int top) {
		BlockPos.MutableBlockPos m = feet.mutable();
		for (int y = feet.getY() + 2; y <= top; y++) {
			if (!level.getBlockState(m.setY(y)).getFluidState().isEmpty()) return false;
		}
		return true;
	}
}
