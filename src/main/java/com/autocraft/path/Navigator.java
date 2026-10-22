package com.autocraft.path;

import com.autocraft.Chat;
import com.autocraft.control.Controls;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/**
 * Plans with A* and walks the path. Behaviors call {@link #setGoal} (no-op for an equal goal) and then
 * {@link #tick} every tick they own. ARRIVED/FAILED are sticky until {@link #stop} or a different goal.
 * Searches run a few milliseconds per tick; long ones end with a partial path that is followed and then
 * re-planned from its end. Repeated attempts without getting closer to the goal end in FAILED.
 */
public final class Navigator {
	public enum Status { IDLE, MOVING, ARRIVED, FAILED }

	private static final long SEARCH_NANOS_PER_TICK = 12_000_000L;
	private static final int MAX_SEARCH_TICKS = 40;
	private static final int MAX_FAILURES = 4;
	private static final int RETRY_COOLDOWN = 20;

	private final Minecraft mc;
	private final Controls controls;
	private @Nullable Goal goal;
	private Status status = Status.IDLE;
	private @Nullable Pathfinder search;
	private int searchTicks;
	private @Nullable BlockPos searchStart;
	private @Nullable PathExecutor executor;
	private int failures;
	private int cooldown;
	private double bestHeuristic = Double.MAX_VALUE;

	public Navigator(Minecraft mc, Controls controls) {
		this.mc = mc;
		this.controls = controls;
	}

	public void setGoal(Goal newGoal) {
		if (Objects.equals(newGoal, goal)) return;
		stop();
		goal = newGoal;
		status = Status.MOVING;
	}

	public void stop() {
		goal = null;
		status = Status.IDLE;
		search = null;
		executor = null;
		failures = 0;
		cooldown = 0;
		bestHeuristic = Double.MAX_VALUE;
	}

	public @Nullable Goal goal() {
		return goal;
	}

	public Status tick() {
		if (goal == null) return Status.IDLE;
		if (status == Status.ARRIVED || status == Status.FAILED) return status;
		LocalPlayer p = mc.player;
		BlockPos feet = feet(p);
		if (goal.isEnd(feet.getX(), feet.getY(), feet.getZ()) && settled(p)) {
			search = null;
			executor = null;
			return status = Status.ARRIVED;
		}
		trackProgress(feet);

		if (executor != null) {
			PathExecutor.Result r = executor.tick(mc.level, p, controls);
			if (r == PathExecutor.Result.RUNNING) return Status.MOVING;
			executor = null;
			if (r == PathExecutor.Result.REPLAN) failures++;
		}
		if (failures >= MAX_FAILURES) return fail("no progress after " + failures + " attempts");
		if (cooldown > 0) {
			cooldown--;
			return Status.MOVING;
		}
		if (search == null) {
			if (!settled(p)) return Status.MOVING; // let falls/jumps land before planning
			search = new Pathfinder(new PathContext(mc.level, p), feet, goal);
			searchStart = feet;
			searchTicks = 0;
		}
		Pathfinder.Result result = search.run(System.nanoTime() + SEARCH_NANOS_PER_TICK);
		if (result == null && ++searchTicks < MAX_SEARCH_TICKS) return Status.MOVING;
		if (result == null) result = search.finish();
		Chat.LOG.debug("path {} -> {}: {} steps, complete={}, expanded={}", searchStart, goal, result.steps().size(), result.complete(), search.expanded());
		search = null;
		if (result.steps().isEmpty()) {
			failures++;
			cooldown = RETRY_COOLDOWN;
			return failures >= MAX_FAILURES ? fail("no path") : Status.MOVING;
		}
		executor = new PathExecutor(result.steps(), searchStart);
		return Status.MOVING;
	}

	/** Getting closer to the goal forgives earlier failed attempts. */
	private void trackProgress(BlockPos feet) {
		double h = goal.heuristic(feet.getX(), feet.getY(), feet.getZ());
		if (h < bestHeuristic - Goal.WALK) {
			bestHeuristic = h;
			failures = 0;
		}
	}

	private Status fail(String why) {
		Chat.LOG.info("Navigation to {} failed: {}", goal, why);
		search = null;
		executor = null;
		return status = Status.FAILED;
	}

	/** Feet block of the player (robust to soul sand / slab heights). */
	public static BlockPos feet(LocalPlayer p) {
		return BlockPos.containing(p.getX(), p.getY() + 0.2, p.getZ());
	}

	/** Standing on something or floating in water: a stable place to plan from or to call "arrived". */
	public static boolean settled(LocalPlayer p) {
		return p.onGround() || p.isInWater();
	}
}
