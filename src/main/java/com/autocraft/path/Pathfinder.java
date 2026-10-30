package com.autocraft.path;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/**
 * Incremental weighted A* over feet positions. {@link #run} expands nodes until a deadline and can be
 * resumed next tick, so long searches never freeze the game. Returns the full path when the goal is
 * reached, otherwise the path to the node closest to the goal (partial path).
 */
final class Pathfinder {
	/**
	 * Path result; {@code complete} = ends on a goal position; {@code exhausted} = every reachable position was
	 * searched without finding the goal (it's unreachable from here, no point retrying).
	 */
	record Result(List<Step> steps, boolean complete, boolean exhausted) {}

	private static final int MAX_NODES = 120_000;
	private static final double HEURISTIC_WEIGHT = 1.4;
	private static final int MAX_FALL_DRY = 3;
	private static final int MAX_FALL_WATER = 24;
	private static final double SQRT2 = Math.sqrt(2);

	// costs in ticks
	private static final double WALK = Goal.WALK;
	private static final double SWIM = 9;
	private static final double SWIM_SPRINT = 5;
	private static final double SUBMERGED_EXTRA = 4;
	/** Submerged with no air above: each block of it eats breath the bot may not get back (flooded caves drowned it). */
	private static final double UNDER_CEILING_EXTRA = 40;
	private static final double JUMP_EXTRA = 2.5;
	private static final double DESCEND_EXTRA = 2;
	private static final double FALL_PER_BLOCK = 1;
	private static final double DIG_DOWN = 5;
	private static final double PILLAR = 14;
	private static final double BRIDGE = 16;
	private static final double SWIM_VERTICAL = 6;
	private static final double LAVA_NEARBY = 12;

	private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
	private static final int[][] DIAGONALS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

	private static final class Node {
		final int x, y, z;
		double g = Double.MAX_VALUE;
		final double h;
		@Nullable Node parent;
		@Nullable Step step;
		int placed;
		boolean closed;

		Node(int x, int y, int z, double h) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.h = h;
		}
	}

	private record Open(Node node, double f) {}

	private final PathContext ctx;
	private final Goal goal;
	private final AvoidedMoves avoided;
	private final Long2ObjectOpenHashMap<Node> nodes = new Long2ObjectOpenHashMap<>();
	private final PriorityQueue<Open> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
	private final Node start;
	private Node best;
	private int expanded;
	private @Nullable Result result;

	Pathfinder(PathContext ctx, BlockPos from, Goal goal, AvoidedMoves avoided) {
		this.ctx = ctx;
		this.goal = goal;
		this.avoided = avoided;
		this.start = node(from.getX(), from.getY(), from.getZ());
		start.g = 0;
		best = start;
		open.add(new Open(start, start.h * HEURISTIC_WEIGHT));
	}

	/** Expand until done or {@code deadlineNanos}; returns null while still searching. */
	@Nullable Result run(long deadlineNanos) {
		if (result != null) return result;
		int sinceClockCheck = 0;
		while (!open.isEmpty()) {
			Open o = open.poll();
			Node n = o.node;
			if (n.closed || o.f != n.g + n.h * HEURISTIC_WEIGHT) continue;
			n.closed = true;
			if (goal.isEnd(n.x, n.y, n.z)) {
				return result = new Result(backtrack(n), true, false);
			}
			if (n.h < best.h) best = n;
			expand(n);
			if (++expanded >= MAX_NODES) break;
			if (++sinceClockCheck >= 64) {
				sinceClockCheck = 0;
				if (System.nanoTime() > deadlineNanos) return null;
			}
		}
		return finish();
	}

	/** Stop searching and return the best path found so far. */
	Result finish() {
		if (result == null) result = new Result(best == start ? List.of() : backtrack(best), false, open.isEmpty());
		return result;
	}

	int expanded() {
		return expanded;
	}

	private List<Step> backtrack(Node end) {
		List<Step> steps = new ArrayList<>();
		for (Node n = end; n.parent != null; n = n.parent) steps.add(n.step);
		Collections.reverse(steps);
		return steps;
	}

	private Node node(int x, int y, int z) {
		long key = BlockPos.asLong(x, y, z);
		Node n = nodes.get(key);
		if (n == null) {
			n = new Node(x, y, z, goal.heuristic(x, y, z));
			nodes.put(key, n);
		}
		return n;
	}

	private void add(Node from, int x, int y, int z, double cost, Step.Type type, List<BlockPos> breaks, @Nullable BlockPos place) {
		if (cost >= PathContext.INF || avoided.contains(from.x, from.y, from.z, x, y, z)) return;
		Node n = node(x, y, z);
		if (n.closed) return;
		double g = from.g + cost;
		if (g >= n.g) return;
		n.g = g;
		n.parent = from;
		n.placed = from.placed + (place != null ? 1 : 0);
		n.step = new Step(new BlockPos(x, y, z), type, breaks, place);
		open.add(new Open(n, g + n.h * HEURISTIC_WEIGHT));
	}

	// ---- moves ----------------------------------------------------------------------------------

	private void expand(Node n) {
		int x = n.x, y = n.y, z = n.z;
		boolean inWater = ctx.water(x, y, z);
		boolean onSolid = ctx.standOn(x, y - 1, z);
		for (int[] d : CARDINALS) {
			traverse(n, d[0], d[1], onSolid);
			ascend(n, d[0], d[1], inWater, onSolid);
			descendOrFall(n, d[0], d[1]);
		}
		for (int[] d : DIAGONALS) diagonal(n, d[0], d[1]);
		if (!inWater) {
			digDown(n);
			if (onSolid) pillar(n);
		} else {
			swimVertical(n);
		}
	}

	private void traverse(Node n, int dx, int dz, boolean onSolid) {
		int x = n.x + dx, y = n.y, z = n.z + dz;
		double head = ctx.clearCost(x, y + 1, z), feet = ctx.clearCost(x, y, z);
		if (head >= PathContext.INF || feet >= PathContext.INF) return;
		BlockPos place = null;
		double move;
		if (ctx.water(x, y, z)) {
			move = swimCost(x, y, z);
		} else if (ctx.standOn(x, y - 1, z)) {
			move = WALK * ctx.floorMultiplier(x, y, z);
		} else if (onSolid && n.placed < ctx.throwaway && ctx.replaceable(x, y - 1, z) && !ctx.lava(x, y - 2, z)) {
			place = new BlockPos(x, y - 1, z);
			move = WALK + BRIDGE;
		} else {
			return; // a gap: descendOrFall covers it
		}
		double cost = move + head + feet + (ctx.nearLava(x, y, z) ? LAVA_NEARBY : 0);
		add(n, x, y, z, cost, Step.Type.WALK, breaks(x, y + 1, z, head, x, y, z, feet), place);
	}

	private void diagonal(Node n, int dx, int dz) {
		int x = n.x + dx, y = n.y, z = n.z + dz;
		if (!ctx.passable(x, y, z) || !ctx.passable(x, y + 1, z)) return;
		if (!ctx.passable(n.x + dx, y, n.z) || !ctx.passable(n.x + dx, y + 1, n.z)) return;
		if (!ctx.passable(n.x, y, n.z + dz) || !ctx.passable(n.x, y + 1, n.z + dz)) return;
		double move;
		if (ctx.water(x, y, z)) move = swimCost(x, y, z) * SQRT2;
		else if (ctx.standOn(x, y - 1, z)) move = WALK * SQRT2 * ctx.floorMultiplier(x, y, z);
		else return;
		add(n, x, y, z, move + (ctx.nearLava(x, y, z) ? LAVA_NEARBY : 0), Step.Type.DIAGONAL, List.of(), null);
	}

	/**
	 * Ticks per block into water at (x,y,z). Deep enough to swim and fed: sprint-swum along the surface (about 0.28
	 * blocks a tick, like sprinting on land). Wading (ground underfoot, head out) or hungry: paddling. Under the
	 * surface costs extra — it uses air, and the bot floats at the top anyway — and a lot extra under a ceiling.
	 */
	private double swimCost(int x, int y, int z) {
		boolean deep = !ctx.standOn(x, y - 1, z);
		double cost = deep && ctx.sprintSwim ? SWIM_SPRINT : SWIM;
		if (!ctx.water(x, y + 1, z)) return cost;
		return cost + SUBMERGED_EXTRA + (ctx.underCeiling(x, y + 1, z) ? UNDER_CEILING_EXTRA : 0);
	}

	private void ascend(Node n, int dx, int dz, boolean inWater, boolean onSolid) {
		if (!onSolid && !inWater) return;
		int x = n.x + dx, y = n.y + 1, z = n.z + dz;
		if (!ctx.standOn(x, y - 1, z)) return; // must step onto an existing block
		double above = ctx.clearCost(n.x, n.y + 2, n.z);
		double head = ctx.clearCost(x, y + 1, z), feet = ctx.clearCost(x, y, z);
		if (above >= PathContext.INF || head >= PathContext.INF || feet >= PathContext.INF) return;
		List<BlockPos> breaks = new ArrayList<>(3);
		if (above > 0) breaks.add(new BlockPos(n.x, n.y + 2, n.z));
		if (head > 0) breaks.add(new BlockPos(x, y + 1, z));
		if (feet > 0) breaks.add(new BlockPos(x, y, z));
		double cost = WALK + JUMP_EXTRA + above + head + feet + (ctx.nearLava(x, y, z) ? LAVA_NEARBY : 0);
		add(n, x, y, z, cost, Step.Type.ASCEND, breaks, null);
	}

	private void descendOrFall(Node n, int dx, int dz) {
		int x = n.x + dx, y = n.y, z = n.z + dz;
		if (ctx.standOn(x, y - 1, z)) return; // level ground: that's a traverse
		double head = ctx.clearCost(x, y + 1, z), feet = ctx.clearCost(x, y, z);
		if (head >= PathContext.INF || feet >= PathContext.INF) return;

		// one step down (staircase), mining as needed
		double below = ctx.clearCost(x, y - 1, z);
		if (below < PathContext.INF && ctx.standOn(x, y - 2, z)) {
			List<BlockPos> breaks = new ArrayList<>(3);
			if (head > 0) breaks.add(new BlockPos(x, y + 1, z));
			if (feet > 0) breaks.add(new BlockPos(x, y, z));
			if (below > 0) breaks.add(new BlockPos(x, y - 1, z));
			double cost = WALK + DESCEND_EXTRA + head + feet + below + (ctx.nearLava(x, y - 1, z) ? LAVA_NEARBY : 0);
			add(n, x, y - 1, z, cost, Step.Type.DESCEND, breaks, null);
			return;
		}

		// free fall down an open column
		if (head > 0 || feet > 0 || !ctx.passable(x, y - 1, z)) return;
		for (int k = 2; k <= MAX_FALL_WATER; k++) {
			int ty = y - k;
			if (ctx.state(x, ty, z) == null) return;
			if (ctx.water(x, ty, z)) {
				add(n, x, ty, z, WALK + k * FALL_PER_BLOCK + 4, Step.Type.FALL, List.of(), null);
				return;
			}
			if (!ctx.passable(x, ty, z)) return;
			if (ctx.standOn(x, ty - 1, z)) {
				if (k <= MAX_FALL_DRY) add(n, x, ty, z, WALK + k * FALL_PER_BLOCK + 2, Step.Type.FALL, List.of(), null);
				return;
			}
		}
	}

	private void digDown(Node n) {
		double floor = ctx.clearCost(n.x, n.y - 1, n.z);
		if (floor == 0 || floor >= PathContext.INF) return;
		if (!ctx.standOn(n.x, n.y - 2, n.z)) return;
		add(n, n.x, n.y - 1, n.z, DIG_DOWN + floor, Step.Type.DIG_DOWN, List.of(new BlockPos(n.x, n.y - 1, n.z)), null);
	}

	private void pillar(Node n) {
		if (n.placed >= ctx.throwaway || !ctx.replaceable(n.x, n.y, n.z)) return;
		double above = ctx.clearCost(n.x, n.y + 2, n.z);
		if (above >= PathContext.INF) return;
		List<BlockPos> breaks = above > 0 ? List.of(new BlockPos(n.x, n.y + 2, n.z)) : List.of();
		add(n, n.x, n.y + 1, n.z, PILLAR + above, Step.Type.PILLAR, breaks, new BlockPos(n.x, n.y, n.z));
	}

	private void swimVertical(Node n) {
		if (ctx.water(n.x, n.y + 1, n.z) && ctx.passable(n.x, n.y + 2, n.z)) {
			add(n, n.x, n.y + 1, n.z, SWIM_VERTICAL, Step.Type.SWIM_VERTICAL, List.of(), null);
		}
		if (ctx.water(n.x, n.y - 1, n.z)) {
			add(n, n.x, n.y - 1, n.z, SWIM_VERTICAL, Step.Type.SWIM_VERTICAL, List.of(), null);
		}
	}

	private static List<BlockPos> breaks(int x1, int y1, int z1, double c1, int x2, int y2, int z2, double c2) {
		if (c1 == 0 && c2 == 0) return List.of();
		if (c1 == 0) return List.of(new BlockPos(x2, y2, z2));
		if (c2 == 0) return List.of(new BlockPos(x1, y1, z1));
		return List.of(new BlockPos(x1, y1, z1), new BlockPos(x2, y2, z2));
	}
}
