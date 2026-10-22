package com.autocraft.path;

import com.autocraft.control.Controls;
import com.autocraft.control.InvUtil;
import com.autocraft.world.BlockUtil;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Walks a planned path one tick at a time: for each step, mine its blocks, place its block, then move.
 * Reports REPLAN when the world or the player no longer matches the plan.
 */
final class PathExecutor {
	enum Result { RUNNING, DONE, REPLAN }

	/** Ticks a step may take without mining/placing before we call it stuck. */
	private static final int STUCK_TICKS = 100;
	/** Upper bound for mining a single planned block. */
	private static final int MINE_TICKS = 20 * 25;
	private static final double CENTER_TOLERANCE = 0.4;

	private final List<Step> steps;
	private final BlockPos start;
	private final Predicate<ItemStack> scaffold;
	private int index;
	private int moveTicks;
	private int mineTicks;

	PathExecutor(List<Step> steps, BlockPos start, Predicate<ItemStack> scaffold) {
		this.steps = steps;
		this.start = start;
		this.scaffold = scaffold;
	}

	Result tick(ClientLevel level, LocalPlayer p, Controls controls) {
		BlockPos feet = Navigator.feet(p);
		advance(feet, p);
		if (index >= steps.size()) return Result.DONE;

		Step step = steps.get(index);
		BlockPos from = index == 0 ? start : steps.get(index - 1).dest();
		if (deviated(feet, from, step, p)) return Result.REPLAN;
		if (dangerAhead(level, step)) return Result.REPLAN;

		// 1. clear the way
		for (BlockPos b : step.breaks()) {
			BlockState s = level.getBlockState(b);
			if (BlockUtil.isPassable(level, b, s)) continue;
			if (!BlockUtil.isBreakable(level, b, s) || !p.isWithinBlockInteractionRange(b, 0.0)) return Result.REPLAN;
			if (++mineTicks > MINE_TICKS) return Result.REPLAN;
			controls.mine(b);
			return Result.RUNNING;
		}

		// 2. place a block (bridge floor or pillar)
		BlockPos place = step.place();
		if (place != null && level.getBlockState(place).canBeReplaced()) {
			if (InvUtil.count(p, scaffold) == 0) return Result.REPLAN;
			return step.type() == Step.Type.PILLAR ? pillar(p, controls, place, from) : bridge(p, controls, place);
		}

		// 3. move
		if (++moveTicks > STUCK_TICKS) return Result.REPLAN;
		move(p, controls, step, feet);
		return Result.RUNNING;
	}

	/** Skip forward past every step whose destination the player already stands on. */
	private void advance(BlockPos feet, LocalPlayer p) {
		int limit = Math.min(steps.size(), index + 4);
		for (int k = index; k < limit; k++) {
			Step s = steps.get(k);
			if (!feet.equals(s.dest()) || !Navigator.settled(p)) continue;
			Step next = k + 1 < steps.size() ? steps.get(k + 1) : null;
			if (next != null && turns(s, next, k == 0 ? start : steps.get(k - 1).dest()) && horizontalDist(p, s.dest()) > CENTER_TOLERANCE) {
				continue; // reached the block, but centre up before turning so we don't clip corners or slip off edges
			}
			index = k + 1;
			moveTicks = 0;
			mineTicks = 0;
		}
	}

	private boolean turns(Step s, Step next, BlockPos before) {
		int dx1 = s.dest().getX() - before.getX(), dz1 = s.dest().getZ() - before.getZ();
		int dx2 = next.dest().getX() - s.dest().getX(), dz2 = next.dest().getZ() - s.dest().getZ();
		return dx1 != dx2 || dz1 != dz2 || next.type() != Step.Type.WALK && next.type() != Step.Type.DIAGONAL;
	}

	private boolean deviated(BlockPos feet, BlockPos from, Step step, LocalPlayer p) {
		if (!Navigator.settled(p) || feet.equals(from) || feet.equals(step.dest())) return false;
		BlockPos dest = step.dest();
		int lowY = Math.min(from.getY(), dest.getY()) - 1, highY = Math.max(from.getY(), dest.getY()) + 1;
		if (feet.getY() < lowY || feet.getY() > highY) return true;
		return xzDistance(feet, from) > 1 && xzDistance(feet, dest) > 1;
	}

	private static int xzDistance(BlockPos a, BlockPos b) {
		return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getZ() - b.getZ()));
	}

	/** Lava flowed into the planned body space since planning. */
	private boolean dangerAhead(ClientLevel level, Step step) {
		BlockPos d = step.dest();
		return BlockUtil.isDangerous(level.getBlockState(d)) || BlockUtil.isDangerous(level.getBlockState(d.above()));
	}

	private Result pillar(LocalPlayer p, Controls controls, BlockPos place, BlockPos from) {
		controls.face(p.getYRot(), 90.0F);
		if (horizontalDist(p, from) > 0.25 && p.onGround()) {
			controls.faceHorizontally(Vec3.atBottomCenterOf(from));
			controls.forward();
			controls.sneak();
			return Result.RUNNING;
		}
		controls.jump();
		if (p.getY() >= place.getY() + 1.02) {
			controls.place(place, scaffold);
		}
		if (++moveTicks > STUCK_TICKS) return Result.REPLAN;
		return Result.RUNNING;
	}

	private Result bridge(LocalPlayer p, Controls controls, BlockPos place) {
		controls.sneak(); // never slide off the edge while placing
		if (!controls.place(place, scaffold)) return Result.REPLAN;
		return Result.RUNNING;
	}

	private void move(LocalPlayer p, Controls controls, Step step, BlockPos feet) {
		Vec3 target = Vec3.atBottomCenterOf(step.dest());
		double dist = horizontalDist(p, step.dest());
		boolean inWater = p.isInWater();
		switch (step.type()) {
			case WALK, DIAGONAL -> {
				controls.faceHorizontally(target);
				controls.forward();
				if (shouldSprint(p)) controls.sprint();
				if (inWater || p.horizontalCollision && p.onGround()) controls.jump();
			}
			case ASCEND -> {
				controls.faceHorizontally(target);
				controls.forward();
				if (inWater || p.onGround() && (p.horizontalCollision || dist < 1.1)) controls.jump();
			}
			case DESCEND, FALL, DIG_DOWN -> {
				controls.faceHorizontally(target);
				if (dist > 0.3) controls.forward();
			}
			case SWIM_VERTICAL -> {
				controls.faceHorizontally(target);
				if (dist > 0.3) controls.forward();
				if (step.dest().getY() > feet.getY()) controls.jump();
				else controls.sneak();
			}
			case PILLAR -> {
				// placed already; just let gravity land us on the new block
			}
		}
	}

	private boolean shouldSprint(LocalPlayer p) {
		if (p.getFoodData().getFoodLevel() <= 6 || p.isInWater()) return false;
		BlockPos prev = index == 0 ? start : steps.get(index - 1).dest();
		Step cur = steps.get(index);
		if (!cur.isFlatWalk()) return false;
		for (int k = index + 1; k < Math.min(steps.size(), index + 3); k++) {
			Step s = steps.get(k);
			if (!s.isFlatWalk() || turns(steps.get(k - 1), s, k - 1 == index ? prev : steps.get(k - 2).dest())) return false;
		}
		return index + 2 < steps.size();
	}

	private static double horizontalDist(LocalPlayer p, BlockPos pos) {
		double dx = p.getX() - (pos.getX() + 0.5), dz = p.getZ() - (pos.getZ() + 0.5);
		return Math.sqrt(dx * dx + dz * dz);
	}
}
