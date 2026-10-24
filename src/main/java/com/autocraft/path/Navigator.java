package com.autocraft.path;

import com.autocraft.Chat;
import com.autocraft.control.Controls;
import com.autocraft.control.InvUtil;
import java.util.Objects;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Plans with A* and walks the path. Behaviors call {@link #setGoal} (no-op for an equal goal) and then
 * {@link #tick} every tick they own. ARRIVED/FAILED are sticky until {@link #stop} or a different goal.
 * Searches run a few milliseconds per tick for at most half a second; then the best partial path is walked
 * and the next segment planned from its end, so the bot never stands around thinking. A search that explores
 * every reachable position without finding the goal fails right away; repeated attempts without getting
 * closer fail too.
 */
public final class Navigator {
	public enum Status { IDLE, MOVING, ARRIVED, FAILED }

	private static final long SEARCH_NANOS_PER_TICK = 12_000_000L;
	private static final int MAX_SEARCH_TICKS = 10;
	private static final int MAX_FAILURES = 3;
	private static final int RETRY_COOLDOWN = 5;

	private final Minecraft mc;
	private final Controls controls;
	/** Items the path must not consume as scaffolding while other blocks are available (job loot). */
	private final Predicate<ItemStack> keep;
	private @Nullable Goal goal;
	private Status status = Status.IDLE;
	private @Nullable Pathfinder search;
	private int searchTicks;
	private @Nullable BlockPos searchStart;
	private @Nullable PathExecutor executor;
	private int failures;
	private int cooldown;
	private double bestHeuristic = Double.MAX_VALUE;
	private Predicate<ItemStack> searchScaffold = InvUtil::isThrowaway;
	private Pathfinder.@Nullable Result preResult;
	private boolean lastPartial;

	public Navigator(Minecraft mc, Controls controls, Predicate<ItemStack> keep) {
		this.mc = mc;
		this.controls = controls;
		this.keep = keep;
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
		preResult = null;
		lastPartial = false;
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
			if (r == PathExecutor.Result.RUNNING) {
				prePlan(p);
				return Status.MOVING;
			}
			executor = null;
			if (r == PathExecutor.Result.REPLAN) {
				failures++;
				search = null;
				preResult = null;
			} else if (preResult != null && feet.equals(searchStart)) {
				Pathfinder.Result next = preResult; // planned while walking: keep going without a pause
				preResult = null;
				return accept(next);
			}
			search = null;
			preResult = null;
		}
		if (failures >= MAX_FAILURES) return fail("no progress after " + failures + " attempts");
		if (cooldown > 0) {
			cooldown--;
			return Status.MOVING;
		}
		if (search == null) {
			if (!settled(p)) return Status.MOVING; // let falls/jumps land before planning
			searchScaffold = InvUtil.scaffold(p, keep);
			search = new Pathfinder(new PathContext(mc.level, p, searchScaffold), feet, goal);
			searchStart = feet;
			searchTicks = 0;
		}
		Pathfinder.Result result = search.run(System.nanoTime() + SEARCH_NANOS_PER_TICK);
		if (result == null && ++searchTicks < MAX_SEARCH_TICKS) return Status.MOVING;
		if (result == null) result = search.finish();
		search = null;
		return accept(result);
	}

	private Status accept(Pathfinder.Result result) {
		Chat.LOG.debug("path {} -> {}: {} steps, complete={}", searchStart, goal, result.steps().size(), result.complete());
		if (result.exhausted() && result.steps().isEmpty()) return fail("unreachable");
		if (result.steps().isEmpty()) {
			failures++;
			cooldown = RETRY_COOLDOWN;
			return failures >= MAX_FAILURES ? fail("no path") : Status.MOVING;
		}
		if (result.exhausted()) failures++; // walking to the closest point we can reach; counts against retries
		lastPartial = !result.complete();
		executor = new PathExecutor(result.steps(), searchStart, searchScaffold);
		return Status.MOVING;
	}

	/** Near the end of a partial path: plan the next segment from its end while still walking. */
	private void prePlan(LocalPlayer p) {
		if (lastPartial && search == null && preResult == null && executor.remaining() <= 3) {
			searchStart = executor.end();
			search = new Pathfinder(new PathContext(mc.level, p, searchScaffold), searchStart, goal);
			searchTicks = 0;
		}
		if (search == null) return;
		Pathfinder.Result r = search.run(System.nanoTime() + SEARCH_NANOS_PER_TICK / 2);
		if (r == null && ++searchTicks >= MAX_SEARCH_TICKS * 2) r = search.finish();
		if (r != null) {
			preResult = r;
			search = null;
		}
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
