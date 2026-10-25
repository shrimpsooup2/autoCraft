package com.autocraft.build;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Finds how to look and where to click so a placed block comes out in the wanted orientation (stairs
 * upside down, logs sideways, a hopper pointing into a chest, an observer facing up...). It asks the
 * block's own placement logic: for each clickable neighbour face, hit point and a set of view angles it
 * builds the same {@link BlockPlaceContext} the game will build and compares the predicted state.
 */
public final class PlacementSolver {
	/** Look this way, then click {@code hit} on {@code against}'s {@code face}. */
	public record Solution(float yaw, float pitch, BlockPos against, Direction face, Vec3 hit) {}

	private static final float[] YAWS = {0, 90, 180, -90};
	private static final float[] PITCHES = {0, 60, -60, 89, -89};

	private PlacementSolver() {}

	public static @Nullable Solution solve(LocalPlayer p, BlockPos pos, BlockState target, ItemStack stack, double reach) {
		ClientLevel level = (ClientLevel) p.level();
		float yaw0 = p.getYRot(), pitch0 = p.getXRot();
		try {
			BlockState current = level.getBlockState(pos);
			boolean doubling = target.hasProperty(BlockStateProperties.SLAB_TYPE) && target.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE
					&& current.is(target.getBlock());
			for (Direction face : Direction.values()) {
				// normal case: click a solid neighbour; doubling a slab: click into the existing slab itself
				BlockPos against = doubling ? pos : pos.relative(face.getOpposite());
				BlockState clicked = level.getBlockState(against);
				if (!doubling && (clicked.isAir() || clicked.canBeReplaced())) continue;
				for (Vec3 hit : hits(against, face, doubling)) {
					if (p.getEyePosition().distanceTo(hit) > reach) continue;
					for (float yaw : YAWS) {
						for (float pitch : PITCHES) {
							p.setYRot(yaw);
							p.setXRot(pitch);
							BlockPlaceContext ctx = new BlockPlaceContext(p, InteractionHand.MAIN_HAND, stack, new BlockHitResult(hit, face, against, false));
							if (!ctx.getClickedPos().equals(pos) || !ctx.canPlace()) continue;
							BlockState predicted = target.getBlock().getStateForPlacement(ctx);
							if (predicted != null && BuildPlan.sameProps(predicted, target)) return new Solution(yaw, pitch, against, face, hit);
						}
					}
				}
			}
			return null;
		} finally {
			p.setYRot(yaw0);
			p.setXRot(pitch0);
		}
	}

	/** Face centre plus upper/lower points on side faces (top-half vs bottom-half slabs and stairs). */
	private static List<Vec3> hits(BlockPos against, Direction face, boolean doubling) {
		Vec3 center = Vec3.atCenterOf(against).add(Vec3.atLowerCornerOf(face.getUnitVec3i()).scale(doubling ? 0.0 : 0.5));
		List<Vec3> out = new ArrayList<>(3);
		out.add(center);
		if (face.getAxis().isHorizontal()) {
			out.add(center.add(0, 0.25, 0));
			out.add(center.add(0, -0.25, 0));
		}
		return out;
	}

	/** Any solid neighbour face to click when orientation doesn't matter or couldn't be solved. */
	public static @Nullable Solution anyFace(LocalPlayer p, BlockPos pos, double reach) {
		ClientLevel level = (ClientLevel) p.level();
		for (Direction face : Direction.values()) {
			BlockPos against = pos.relative(face.getOpposite());
			BlockState clicked = level.getBlockState(against);
			if (clicked.isAir() || clicked.canBeReplaced()) continue;
			Vec3 hit = Vec3.atCenterOf(against).add(Vec3.atLowerCornerOf(face.getUnitVec3i()).scale(0.5));
			if (p.getEyePosition().distanceTo(hit) <= reach) return new Solution(p.getYRot(), p.getXRot(), against, face, hit);
		}
		return null;
	}
}
