package com.autocraft.cinema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Ways on foot for the puppets, through the real world: which cells a player can stand in, and the shortest walk
 * between two of them around trees, rocks and walls (a step up a block, a drop of up to two, never through water
 * or onto anything `avoid` says). Routes are straightened where the ground allows, so the walk reads naturally.
 */
final class Walker {
	/** Nothing extra to keep off. */
	static final Predicate<BlockState> NOTHING = s -> false;
	private static final int MAX_NODES = 6000;

	private Walker() {}

	/** Nothing to bump into here (and nothing to avoid, no water). */
	static boolean open(Level level, BlockPos p, Predicate<BlockState> avoid) {
		BlockState s = level.getBlockState(p);
		return !avoid.test(s) && s.getFluidState().isEmpty() && s.getCollisionShape(level, p).isEmpty();
	}

	/** The height of what's under feet cell f (0.5–1.5 above f's floor block bottom, so slabs count), or -1 if it can't be stood on. */
	static double floor(Level level, BlockPos f, Predicate<BlockState> avoid) {
		BlockPos below = f.below();
		BlockState s = level.getBlockState(below);
		if (avoid.test(s)) return -1;
		VoxelShape shape = s.getCollisionShape(level, below);
		if (shape.isEmpty()) return -1;
		double top = shape.max(Direction.Axis.Y);
		return top >= 0.5 ? top : -1;
	}

	static boolean standable(Level level, BlockPos f, Predicate<BlockState> avoid) {
		return open(level, f, avoid) && open(level, f.above(), avoid) && floor(level, f, avoid) >= 0;
	}

	/** Where feet in cell f actually rest. */
	static Vec3 feet(Level level, BlockPos f) {
		return new Vec3(f.getX() + 0.5, f.getY() - 1 + Math.max(0, floor(level, f, NOTHING)), f.getZ() + 0.5);
	}

	/** The standable cell nearest `around` (searching a few blocks out and up/down), or null. */
	static @Nullable BlockPos nearestStandable(Level level, BlockPos around, int radius, Predicate<BlockState> avoid) {
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				for (int dy = -3; dy <= 3; dy++) {
					BlockPos f = around.offset(dx, dy, dz);
					double d = dx * dx + dz * dz + dy * dy * 2;
					if (d < bestD && standable(level, f, avoid)) {
						bestD = d;
						best = f;
					}
				}
			}
		}
		return best;
	}

	/** The walk from one cell to another as feet positions, straightened; null if there's none within reach. */
	static @Nullable List<Vec3> route(Level level, BlockPos from, BlockPos to, Predicate<BlockState> avoid) {
		List<BlockPos> cells = search(level, from, to, avoid);
		if (cells == null) return null;
		List<BlockPos> straight = straighten(level, cells, avoid);
		List<Vec3> out = new ArrayList<>(straight.size());
		for (BlockPos c : straight) out.add(feet(level, c));
		return out;
	}

	private record Node(BlockPos pos, double f) {}

	/** A* over standable cells: 8 ways on the level, a step up, drops of up to two. */
	private static @Nullable List<BlockPos> search(Level level, BlockPos from, BlockPos to, Predicate<BlockState> avoid) {
		Map<BlockPos, Double> g = new HashMap<>();
		Map<BlockPos, BlockPos> parent = new HashMap<>();
		PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
		g.put(from, 0.0);
		open.add(new Node(from, h(from, to)));
		int expanded = 0;
		BlockPos closest = from;
		double closestD = Double.MAX_VALUE;
		while (!open.isEmpty() && expanded < MAX_NODES) {
			Node n = open.poll();
			BlockPos c = n.pos;
			double gc = g.get(c);
			if (n.f > gc + h(c, to) + 1e-9) continue;   // stale entry
			expanded++;
			double d = h(c, to);
			if (d < closestD) {
				closestD = d;
				closest = c;
			}
			if (c.equals(to)) break;
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					if (dx == 0 && dz == 0) continue;
					boolean diagonal = dx != 0 && dz != 0;
					if (diagonal && !(open(level, c.offset(dx, 0, 0), avoid) && open(level, c.offset(dx, 1, 0), avoid)
							&& open(level, c.offset(0, 0, dz), avoid) && open(level, c.offset(0, 1, dz), avoid))) continue;
					for (int dy : diagonal ? new int[] {0} : new int[] {0, 1, -1, -2}) {
						BlockPos nb = c.offset(dx, dy, dz);
						if (!standable(level, nb, avoid)) continue;
						if (dy == 1 && !open(level, c.above(2), avoid)) continue;
						if (dy < 0 && !(open(level, c.offset(dx, 0, dz), avoid) && open(level, c.offset(dx, 1, dz), avoid))) continue;
						double step = (diagonal ? 1.414 : 1) + Math.abs(dy) * 0.6;
						double ng = gc + step;
						if (ng < g.getOrDefault(nb, Double.MAX_VALUE)) {
							g.put(nb, ng);
							parent.put(nb, c);
							open.add(new Node(nb, ng + h(nb, to)));
						}
						break;
					}
				}
			}
		}
		BlockPos end = g.containsKey(to) ? to : closest;
		if (end.equals(from) && !from.equals(to)) return null;
		List<BlockPos> path = new ArrayList<>();
		for (BlockPos c = end; c != null; c = parent.get(c)) path.addFirst(c);
		return path;
	}

	private static double h(BlockPos a, BlockPos b) {
		double dx = a.getX() - b.getX(), dy = a.getY() - b.getY(), dz = a.getZ() - b.getZ();
		return Math.sqrt(dx * dx + dz * dz) + Math.abs(dy) * 0.6;
	}

	/** Skip corners: from each point go straight to the furthest later one the ground allows (same level, all standable on the way). */
	private static List<BlockPos> straighten(Level level, List<BlockPos> cells, Predicate<BlockState> avoid) {
		List<BlockPos> out = new ArrayList<>();
		int i = 0;
		out.add(cells.getFirst());
		while (i < cells.size() - 1) {
			int j = cells.size() - 1;
			while (j > i + 1 && !straight(level, cells.get(i), cells.get(j), avoid)) j--;
			out.add(cells.get(j));
			i = j;
		}
		return out;
	}

	/** A straight walk on one level: every cell the line (and a body's width either side of it) crosses is standable. */
	private static boolean straight(Level level, BlockPos a, BlockPos b, Predicate<BlockState> avoid) {
		if (a.getY() != b.getY()) return false;
		Vec3 pa = Vec3.atBottomCenterOf(a), pb = Vec3.atBottomCenterOf(b);
		Vec3 d = pb.subtract(pa);
		double len = d.horizontalDistance();
		if (len < 1e-6) return true;
		Vec3 side = new Vec3(-d.z / len, 0, d.x / len).scale(0.3);
		for (double s = 0; s <= len; s += 0.25) {
			Vec3 p = pa.add(d.scale(s / len));
			for (Vec3 q : new Vec3[] {p, p.add(side), p.subtract(side)}) {
				if (!standable(level, BlockPos.containing(q.x, a.getY(), q.z), avoid)) return false;
			}
		}
		return true;
	}
}
