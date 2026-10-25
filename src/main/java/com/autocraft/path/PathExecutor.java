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

	/** Ticks a step may take without mining/placing before we call it stuck (then the path is re-planned). */
	private static final int STUCK_TICKS = 40;
	/** Upper bound for mining a single planned block. */
	private static final int MINE_TICKS = 20 * 25;
	private static final double CENTER_TOLERANCE = 0.4;
	/** In water the body drifts and bobs: looser centring before turns. */
	private static final double CENTER_TOLERANCE_WATER = 0.7;
	/** Straight water steps ahead before sprint-swimming is worth starting. */
	private static final int SPRINT_SWIM_RUN = 4;
	/** Trying this long to get the eyes under (to start swimming) means the water is too shallow: paddle instead. */
	private static final int SWIM_START_TICKS = 30;
	/** Skimming: looking this far up keeps a swimming player at the top, head clearing the water. */
	private static final float SKIM_PITCH = -8.0F;

	private final List<Step> steps;
	private final BlockPos start;
	private final Predicate<ItemStack> scaffold;
	private int index;
	private int moveTicks;
	private int mineTicks;
	/** Short of breath: paddling with the head up until the air bar is full again. */
	private boolean breathing;
	private int swimStartTicks;
	/** This stretch of water is too shallow to swim in (until we're out of it). */
	private boolean tooShallow;

	PathExecutor(List<Step> steps, BlockPos start, Predicate<ItemStack> scaffold) {
		this.steps = steps;
		this.start = start;
		this.scaffold = scaffold;
	}

	int remaining() {
		return steps.size() - index;
	}

	/** Where the path ends. */
	BlockPos end() {
		return steps.getLast().dest();
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
		move(level, p, controls, step, feet);
		return Result.RUNNING;
	}

	/** Skip forward past every step whose destination the player already stands on. */
	private void advance(BlockPos feet, LocalPlayer p) {
		int limit = Math.min(steps.size(), index + 4);
		for (int k = index; k < limit; k++) {
			Step s = steps.get(k);
			if (!reached(feet, s.dest(), p) || !Navigator.settled(p)) continue;
			Step next = k + 1 < steps.size() ? steps.get(k + 1) : null;
			double tolerance = p.isInWater() ? CENTER_TOLERANCE_WATER : CENTER_TOLERANCE;
			if (next != null && turns(s, next, k == 0 ? start : steps.get(k - 1).dest()) && horizontalDist(p, s.dest()) > tolerance) {
				continue; // reached the block, but centre up before turning so we don't clip corners or slip off edges
			}
			index = k + 1;
			moveTicks = 0;
			mineTicks = 0;
		}
	}

	/** At {@code dest}: exactly, or in water within a block of its height (floating, bobbing, swimming). */
	private static boolean reached(BlockPos feet, BlockPos dest, LocalPlayer p) {
		if (feet.equals(dest)) return true;
		return p.isInWater() && feet.getX() == dest.getX() && feet.getZ() == dest.getZ() && Math.abs(feet.getY() - dest.getY()) <= 1;
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
		// in water the height comes and goes (bobbing, coming up for air): only leaving the line counts
		if (!p.isInWater() && (feet.getY() < lowY || feet.getY() > highY)) return true;
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

	private void move(ClientLevel level, LocalPlayer p, Controls controls, Step step, BlockPos feet) {
		Vec3 target = Vec3.atBottomCenterOf(step.dest());
		double dist = horizontalDist(p, step.dest());
		boolean inWater = p.isInWater();
		if (!inWater) {
			tooShallow = false; // out of that stretch of water
			swimStartTicks = 0;
		}
		switch (step.type()) {
			case WALK, DIAGONAL -> {
				if (inWater) {
					swim(level, p, controls, step, target);
					return;
				}
				controls.faceHorizontally(target);
				controls.forward();
				if (shouldSprint(p)) controls.sprint();
				if (p.horizontalCollision && p.onGround()) controls.jump();
			}
			case ASCEND -> {
				if (p.isSwimming()) p.setSprinting(false); // stand up to climb out: a swimming body dives when it jumps
				controls.faceHorizontally(target, inWater ? -15.0F : 10.0F);
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

	/**
	 * One water step. Short of breath (a third of the bar gone): paddle with the head up until it's full. A step
	 * under the surface (a dive to a wreck or a sunken block): swim straight at it. A longer straight swim in deep
	 * water with food to spare: sprint-swim skimming the surface — sink under once (swimming only starts with the eyes
	 * in the water), then look a little up so the body rides at the top and the head keeps clearing the water, which
	 * keeps the air bar full. Otherwise paddle with the head up.
	 */
	private void swim(ClientLevel level, LocalPlayer p, Controls controls, Step step, Vec3 target) {
		int air = p.getAirSupply(), max = p.getMaxAirSupply();
		if (air < max * 2 / 3) breathing = true;
		else if (air >= max) breathing = false;
		boolean fed = p.getFoodData().getFoodLevel() > 6;
		controls.forward();
		if (BlockUtil.isWater(level.getBlockState(step.dest().above())) && !breathing) {
			controls.lookAt(Vec3.atCenterOf(step.dest())); // a dive: swimming steers where it looks
			if (fed) controls.sprint();
			return;
		}
		if (!breathing && fed && !tooShallow && waterRunAhead(level) >= SPRINT_SWIM_RUN) {
			controls.sprint();
			if (p.isSwimming()) {
				swimStartTicks = 0;
				controls.faceHorizontally(target, SKIM_PITCH);
			} else if (++swimStartTicks > SWIM_START_TICKS) {
				tooShallow = true; // can't get the eyes under here: paddle across
			} else {
				controls.faceHorizontally(target, 30.0F);
				controls.sneak(); // sink until the eyes are under, then the sprint turns into swimming
			}
			return;
		}
		// paddling: a swimming body would steer down as it jumps, so stand up first
		if (p.isSwimming()) p.setSprinting(false);
		controls.faceHorizontally(target, breathing ? -30.0F : 0.0F);
		controls.jump(); // head above water; against a bank this also climbs out
	}

	/** Straight-ahead water steps from the current one (a turn or a step onto land ends the run). */
	private int waterRunAhead(ClientLevel level) {
		int n = 0;
		for (int k = index; k < Math.min(steps.size(), index + SPRINT_SWIM_RUN); k++) {
			Step s = steps.get(k);
			if (s.type() != Step.Type.WALK && s.type() != Step.Type.DIAGONAL || !BlockUtil.isWater(level.getBlockState(s.dest()))) break;
			if (k > index && turns(steps.get(k - 1), s, k - 1 == 0 ? start : steps.get(k - 2).dest())) break;
			n++;
		}
		return n;
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
