package com.autocraft.cinema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * The mod icon built from blocks in the sky — red concrete for the gear, redstone blocks for the circuit traces —
 * flying in column by column, then the title in Minecraft's font beneath it.
 */
final class Logo {
	static final double COLUMN_DELAY = 0.06;
	static final double FLIGHT = 0.8;

	final BlockPos centre;
	final Direction toward;
	private final List<BlockPos> blocks = new ArrayList<>();
	private final List<BlockState> states = new ArrayList<>();
	private final List<Integer> cols = new ArrayList<>();
	private boolean placed;

	/** centre: middle of the logo; toward: the side the camera watches from. */
	Logo(BlockPos centre, Direction toward) {
		this.centre = centre;
		this.toward = toward;
		for (Map.Entry<BlockPos, Boolean> e : Sets.logoPixels().entrySet()) {
			BlockPos px = e.getKey();
			blocks.add(Sets.logoBlock(centre, toward, px.getX(), px.getY()));
			states.add(e.getValue() ? Blocks.CONCRETE.pick(DyeColor.RED).defaultBlockState() : Blocks.REDSTONE_BLOCK.defaultBlockState());
			cols.add(px.getX());
		}
	}

	/** When the last column lands, for a fly-in starting at t0. */
	static double landed(double t0) {
		return t0 + 32 * COLUMN_DELAY + FLIGHT;
	}

	/** When the fly-in must start for the last column to land at `t`. */
	static double flyFrom(double t) {
		return t - (32 * COLUMN_DELAY + FLIGHT);
	}

	/**
	 * A centre at least as high as asked and high enough that the logo and the title hanging 27 blocks under it
	 * clear everything below them (hills, trees) by `clearance` blocks.
	 */
	static BlockPos above(Level level, BlockPos centre, Direction toward, int clearance) {
		Direction right = toward.getCounterClockWise();
		int top = level.getMinY();
		for (int col = -18; col <= 18; col += 2) {
			for (int d = -3; d <= 3; d += 3) {
				BlockPos p = centre.relative(right, col).relative(toward, d);
				top = Math.max(top, level.getHeight(Heightmap.Types.MOTION_BLOCKING, p.getX(), p.getZ()));
			}
		}
		return new BlockPos(centre.getX(), Math.max(centre.getY(), top + 28 + clearance), centre.getZ());
	}

	/** Columns flying in from t0 (drawn as block models until they land and become real blocks). */
	void draw(Fx fx, double t, double t0) {
		if (placed || t < t0) return;
		Vec3 from = Vec3.atLowerCornerOf(toward.getUnitVec3i()).scale(9).add(0, -14, 0);
		for (int i = 0; i < blocks.size(); i++) {
			double s = t0 + cols.get(i) * COLUMN_DELAY;
			if (t < s) continue;
			double u = Anim.clamp01((t - s) / FLIGHT);
			double e = 1 - Math.pow(1 - u, 3);
			Vec3 p = Vec3.atLowerCornerOf(blocks.get(i)).add(from.scale(1 - e));
			fx.ghost(p, states.get(i), 255, 0.4 + 0.6 * e);
		}
	}

	/** Make it real (once). */
	void place(Shot shot) {
		if (placed) return;
		placed = true;
		for (int i = 0; i < blocks.size(); i++) shot.place(blocks.get(i), states.get(i));
		shot.particles(net.minecraft.core.particles.ParticleTypes.END_ROD, Vec3.atCenterOf(centre), 60, 24, 0.15);
	}

	/** Take it down again, so the next take builds it from nothing. */
	void remove(Shot shot) {
		if (!placed) return;
		placed = false;
		for (BlockPos b : blocks) shot.place(b, Blocks.AIR.defaultBlockState());
	}

	/** Title lines under the logo, popping in from t0 and t1. */
	void title(Fx fx, double t, String big, double t0, String small, double t1) {
		float yaw = Anim.yaw(Vec3.atLowerCornerOf(toward.getUnitVec3i()));
		Vec3 base = Vec3.atCenterOf(centre).add(Vec3.atLowerCornerOf(toward.getUnitVec3i()).scale(1.5));
		double a = Anim.pop(t, t0, 0.5);
		if (a > 0) fx.text(base.add(0, -21, 0), Component.literal(big), (float) (0.62 * a), 0xFFFFFFFF, 0, false, yaw, true);
		double b = Anim.pop(t, t1, 0.5);
		if (b > 0 && small != null) fx.text(base.add(0, -26.5, 0), Component.literal(small), (float) (0.26 * b), 0xFFFFF1E0, 0, false, yaw, true);
	}
}
