package com.autocraft.cinema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Finding: a cutaway hill (a pond, a stone ridge, ores inside). The bot scans: the rock turns to glass — x-ray —
 * and the diamond ores glow inside it. Nearby ones get brackets; a path search spreads through the slice and
 * settles on the cheapest route to one, whose bracket turns amber. The rock comes back and the bot walks the
 * route: down the hill, swimming the pond, digging straight through the ridge, and mines it.
 */
final class FindShot extends Shot {
	private static final int X0 = -15, X1 = 15, DEPTH = 7, BOTTOM = -6, WATER_TOP = 2;
	private static final double XRAY_ON = 2.4, XRAY_OFF = 9.6, SEARCH = 5.4, PATH = 7.0, WALK = 10.2;
	private static final int CYAN = 0x39D0FF, AMBER = 0xFFBF3C;

	private final Map<BlockPos, BlockState> slice = new LinkedHashMap<>();   // the diorama's blocks (world pos → real state)
	private final List<BlockPos> ores = new ArrayList<>();
	private BlockPos target;
	private List<int[]> path = List.of();
	private List<int[]> explored = List.of();
	private final List<Double> stepTimes = new ArrayList<>();
	private final Map<Integer, List<BlockPos>> digsAt = new HashMap<>();
	private Actor bot;
	private double mineAt;
	private boolean xrayOn;

	FindShot() {
		super("Find", "Find", 23, 4000);
	}

	@Override
	BlockPos offset() {
		return new BlockPos(70, 0, 0);
	}

	/** Surface height above the pad along x: a hill, a slope, a pond, a stone ridge. */
	private static int profile(int x) {
		if (x <= -8) return 7;
		if (x <= -4) return 7 - (x + 8);
		if (x <= 0) return 0;
		if (x <= 2) return 3;
		if (x <= 10) return 8;
		return 6;
	}

	private static boolean ridge(int x) {
		return x >= 3 && x <= 10;
	}

	private static BlockState kind(int x, int y, int top) {
		if (y == top) return ridge(x) ? Blocks.STONE.defaultBlockState() : (x >= -1 && x <= 0 ? Blocks.SAND.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState());
		if (y >= top - 2 && !ridge(x)) return Blocks.DIRT.defaultBlockState();
		return y < -4 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
	}

	@Override
	void build(ServerLevel level, BlockPos at) {
		int y0 = Stage.pad(level, at.getX(), at.getZ(), 20);
		origin = new BlockPos(at.getX(), y0, at.getZ());
		Random rng = new Random(9);
		// the trench in front that exposes the cut face
		Stage.clear(level, origin.getX() + X0 - 2, y0 + BOTTOM, origin.getZ() - 9, origin.getX() + X1 + 2, y0 + 12, origin.getZ() - 1);
		Stage.fill(level, origin.getX() + X0 - 2, y0 + BOTTOM - 1, origin.getZ() - 9, origin.getX() + X1 + 2, y0 + BOTTOM - 1, origin.getZ() - 1,
				Blocks.GRASS_BLOCK.defaultBlockState());
		for (int x = X0; x <= X1; x++) {
			int top = profile(x);
			for (int z = 0; z < DEPTH; z++) {
				for (int y = BOTTOM; y <= top; y++) {
					BlockState s = kind(x, y, top);
					if (y < top - 1 && rng.nextInt(22) == 0) s = rng.nextBoolean() ? Blocks.COAL_ORE.defaultBlockState() : Blocks.IRON_ORE.defaultBlockState();
					Stage.set(level, b(x, y, z), s);
				}
				for (int y = top + 1; y <= WATER_TOP; y++) Stage.set(level, b(x, y, z), Blocks.WATER.defaultBlockState());
				for (int y = Math.max(top, WATER_TOP) + 1; y <= 14; y++) Stage.set(level, b(x, y, z), Blocks.AIR.defaultBlockState());
			}
			if (top < WATER_TOP) {   // glass keeps the pond in at the cut face
				for (int y = top + 1; y <= WATER_TOP; y++) Stage.set(level, b(x, y, -1), Blocks.GLASS.defaultBlockState());
			}
		}
		target = b(8, 3, 0);
		Stage.set(level, target, Blocks.DIAMOND_ORE.defaultBlockState());
		ores.add(target);
		for (int i = 0; i < 9; i++) {
			int x = X0 + 2 + rng.nextInt(X1 - X0 - 3), z = rng.nextInt(DEPTH);
			int y = Math.max(BOTTOM + 1, Math.min(profile(x) - 2, BOTTOM + 1 + rng.nextInt(7)));
			BlockPos p = b(x, y, z);
			Stage.set(level, p, y < -4 ? Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState() : Blocks.DIAMOND_ORE.defaultBlockState());
			ores.add(p);
		}
		Sets.oak(level, origin.getX() - 13, origin.getY() + profile(-13) + 1, origin.getZ() + 4, 5);
		Sets.oak(level, origin.getX() - 10, origin.getY() + profile(-10) + 1, origin.getZ() + 5, 4);
		Sets.oak(level, origin.getX() + 13, origin.getY() + profile(13) + 1, origin.getZ() + 4, 4);
		for (int x = X0; x <= X1; x++) {
			for (int z = 0; z < DEPTH; z++) {
				for (int y = BOTTOM; y <= profile(x); y++) {
					BlockPos p = b(x, y, z);
					slice.put(p, level.getBlockState(p));
				}
			}
		}
	}

	// ---- the route (a weighted A* through the front slice, like the bot's pathfinder) --------------------------------

	private boolean solid(int x, int y, Set<Long> dug) {
		return x >= X0 && x <= X1 && y >= BOTTOM && y <= profile(x) && !dug.contains(key(x, y));
	}

	private static boolean water(int x, int y) {
		return y > profile(x) && y <= WATER_TOP;
	}

	private static long key(int x, int y) {
		return ((long) x << 32) | (y & 0xFFFFFFFFL);
	}

	private void plan() {
		Set<Long> none = new HashSet<>();
		int[] start = {-11, profile(-11) + 1};
		int[] goal = {7, 3};
		PriorityQueue<double[]> open = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
		Map<Long, Double> g = new HashMap<>();
		Map<Long, Long> parent = new HashMap<>();
		Set<Long> closed = new HashSet<>();
		List<int[]> seen = new ArrayList<>();
		open.add(new double[] {0, 0, start[0], start[1]});
		g.put(key(start[0], start[1]), 0.0);
		while (!open.isEmpty()) {
			double[] cur = open.poll();
			int x = (int) cur[2], y = (int) cur[3];
			long k = key(x, y);
			if (!closed.add(k)) continue;
			seen.add(new int[] {x, y});
			if (x == goal[0] && y == goal[1]) break;
			for (int dx : new int[] {-1, 1}) {
				for (int dy : new int[] {-1, 0, 1, -2, -3}) {
					int nx = x + dx, ny = y + dy;
					if (nx < X0 || nx > X1 || ny < BOTTOM || ny > 12) continue;
					double c;
					if (dy < -1) {
						boolean clear = true;
						for (int yy = ny; yy <= y + 1; yy++) clear &= !solid(nx, yy, none);
						if (!clear || !(solid(nx, ny - 1, none) || water(nx, ny))) continue;
						c = 1 + 0.3 * -dy;
					} else {
						int dig = (solid(nx, ny, none) ? 1 : 0) + (solid(nx, ny + 1, none) ? 1 : 0);
						if (dig == 0 && !(solid(nx, ny - 1, none) || water(nx, ny))) continue;
						c = 1 + 0.5 * Math.abs(dy) + dig * 3.5;
						if (dy == 1 && solid(x, y + 2, none)) c += 3.5;
					}
					if (water(nx, ny)) c += 1;
					long nk = key(nx, ny);
					double ng = cur[1] + c;
					if (ng < g.getOrDefault(nk, Double.MAX_VALUE)) {
						g.put(nk, ng);
						parent.put(nk, k);
						open.add(new double[] {ng + 1.6 * (Math.abs(nx - goal[0]) + Math.abs(ny - goal[1])), ng, nx, ny});
					}
				}
			}
		}
		List<int[]> route = new ArrayList<>();
		long k = key(goal[0], goal[1]);
		while (true) {
			route.addFirst(new int[] {(int) (k >> 32), (int) k});
			Long p = parent.get(k);
			if (p == null) break;
			k = p;
		}
		path = route;
		explored = seen.subList(0, Math.min(seen.size(), 180));
	}

	@Override
	void start() {
		plan();
		int[] s0 = path.getFirst();
		bot = add(Actor.me(level, foot(s0), -90));
		bot.light = 0x5EE36A;
		bot.hold(Items.IRON_PICKAXE);
		// timing of the walk: one step after another, pausing to dig
		Set<Long> dug = new HashSet<>();
		double t = WALK;
		stepTimes.add(t);
		for (int i = 1; i < path.size(); i++) {
			int[] a = path.get(i);
			List<BlockPos> digs = new ArrayList<>();
			for (int dy : new int[] {1, 0}) {
				if (solid(a[0], a[1] + dy, dug)) {
					digs.add(b(a[0], a[1] + dy, 0));
					dug.add(key(a[0], a[1] + dy));
				}
			}
			if (!digs.isEmpty()) {
				digsAt.put(i, digs);
				t += 0.35 * digs.size();
			}
			t += water(a[0], a[1]) ? 0.4 : 0.22;
			stepTimes.add(t);
		}
		mineAt = t + 0.3;
		Vec3 c = v(0, 4, -26);
		Vec3 end = Vec3.atCenterOf(target);
		cam.key(0, v(-3, 8, -27), v(-2, 3, 2), 60)
				.key(SEARCH, v(-1, 7, -25), v(-0.5, 2.5, 2))
				.key(WALK, c.add(0, 3.5, 0), v(0, 2.5, 2))
				.key((WALK + mineAt) / 2, v(1, 5.5, -14), v(2, 2.5, 1))
				.key(mineAt, end.add(-2.4, 2.3, -7.5), end.add(-0.8, 0, 0), 58)
				.key(duration, end.add(-1.9, 2.0, -6.5), end.add(-0.7, 0, 0));
	}

	private Vec3 foot(int[] cell) {
		double y = origin.getY() + cell[1] - (water(cell[0], cell[1]) ? 0.45 : 0);
		return new Vec3(origin.getX() + cell[0] + 0.5, y, origin.getZ() + 0.5);
	}

	@Override
	void tick(double t) {
		if (at(t, XRAY_ON)) xray(true);
		if (at(t, XRAY_OFF)) xray(false);
		if (t < 1.6) bot.look(v(-6, profile(-11) + 1.5, -6));
		else if (t < WALK) bot.look(Vec3.atCenterOf(target));
		// walk the route
		if (t >= WALK && t < mineAt) {
			int i = 1;
			while (i < stepTimes.size() - 1 && t >= stepTimes.get(i)) i++;
			double tEnd = stepTimes.get(i), tStart = stepTimes.get(i - 1);
			List<BlockPos> digs = digsAt.getOrDefault(i, List.of());
			double digTime = 0.35 * digs.size();
			Vec3 a = foot(path.get(i - 1)), b = foot(path.get(i));
			bot.face(b.x > a.x ? -90 : 90);
			if (t < tStart + digTime) {
				bot.moveTo(a);
				int k = (int) ((t - tStart) / 0.35);
				BlockPos d = digs.get(Math.min(k, digs.size() - 1));
				bot.look(Vec3.atCenterOf(d));
				crack(d, (int) Math.min(9, ((t - tStart) % 0.35) / 0.35 * 10));
				if (every(t, tStart, tStart + digTime, 0.15)) bot.swing();
			} else {
				bot.look(null);
				bot.moveTo(Anim.lerp(a, b, (t - tStart - digTime) / Math.max(0.01, tEnd - tStart - digTime)));
			}
			for (int k = 0; k < digs.size(); k++) {
				if (at(t, tStart + 0.35 * (k + 1))) dig(digs.get(k));
			}
		}
		if (t >= mineAt) {
			bot.look(Vec3.atCenterOf(target));
			if (every(t, mineAt, mineAt + 1.2, 0.2)) bot.swing();
			if (t < mineAt + 1.2) crack(target, (int) Math.min(9, (t - mineAt) / 1.2 * 10));
			if (at(t, mineAt + 1.2)) {
				dig(target);
				particles(ParticleTypes.END_ROD, Vec3.atCenterOf(target), 14, 0.8, 0.08);
			}
		}
	}

	/** X-ray: the hill's rock and soil turn to glass on this client (ores stay), or back. */
	private void xray(boolean on) {
		xrayOn = on;
		BlockState glass = Blocks.STAINED_GLASS.pick(DyeColor.LIGHT_BLUE).defaultBlockState();
		for (Map.Entry<BlockPos, BlockState> e : slice.entrySet()) {
			BlockState real = e.getValue();
			if (ores.contains(e.getKey()) || real.is(Blocks.COAL_ORE) || real.is(Blocks.IRON_ORE)) continue;
			level.setBlock(e.getKey(), on ? glass : real, net.minecraft.world.level.block.Block.UPDATE_ALL);
		}
	}

	@Override
	void draw(Fx fx, double t) {
		boolean xr = t >= XRAY_ON && t < XRAY_OFF;
		Vec3 bp = bot.entity.getPosition(fx.partial);
		// scan pulses spreading from the bot
		for (int k = 0; k < 3; k++) {
			double s = t - (XRAY_ON - 0.2 + k * 0.7);
			if (s > 0 && s < 1.6) fx.ring(bp.add(0, 0.1, 0), 1 + s * 16, 0.12, Fx.argb((int) (220 * (1 - s / 1.6)), CYAN), 1);
		}
		if (xr) {
			double a = Anim.window(t, XRAY_ON, XRAY_ON + 0.5, XRAY_OFF - 0.4, XRAY_OFF);
			for (BlockPos o : ores) {
				fx.glowBox(new AABB(o).inflate(0.03), Fx.argb((int) (60 * a), 0x4FF0E6));
				fx.outline(o, Fx.argb((int) (255 * a), 0x9FFFF8), 2.5f);
			}
			for (int x = X0; x <= X1; x++) {   // the cut face's block grid
				for (int y = BOTTOM; y <= profile(x); y++) fx.outline(b(x, y, 0), Fx.argb((int) (90 * a), CYAN), 1.0f);
			}
			// brackets: candidates near the front, the chosen one amber once the route is found
			for (BlockPos o : ores) {
				if (o.getZ() - origin.getZ() > 2) continue;
				boolean chosen = o.equals(target) && t >= PATH;
				double grow = Anim.pop(t, 3.6 + (o.getX() - origin.getX() + 15) * 0.03, 0.4);
				fx.outline(new AABB(o).inflate(chosen ? 0.22 : 0.14).move(0, 0, -0.1), Fx.argb((int) (230 * grow * a), chosen ? AMBER : 0xD8F4FF), chosen ? 4f : 2f);
			}
			// the search spreading, then the route
			for (int i = 0; i < explored.size(); i++) {
				double appear = SEARCH + 1.4 * i / explored.size();
				if (t < appear) break;
				int[] c = explored.get(i);
				Vec3 p = new Vec3(origin.getX() + c[0] + 0.5, origin.getY() + c[1] + 0.5, origin.getZ() - 0.3);
				double s = 0.07 * Anim.pop(t, appear, 0.2);
				fx.glowBox(new AABB(p.subtract(s, s, s), p.add(s, s, s)), Fx.argb((int) (200 * a), 0x7FD8FF));
			}
		}
		if (t >= PATH && t < mineAt + 0.5) {
			List<Vec3> pts = new ArrayList<>();
			for (int[] c : path) pts.add(new Vec3(origin.getX() + c[0] + 0.5, origin.getY() + c[1] + 0.35, origin.getZ() - 0.35));
			fx.ribbon(pts, 0.1, Fx.argb(255, AMBER), Anim.ramp(t, PATH, PATH + 0.9) * (1 - Anim.ramp(t, mineAt, mineAt + 0.5)));
		}
		if (t >= mineAt + 1.2) {   // three diamonds hop out and hover
			Vec3 c = Vec3.atCenterOf(target);
			for (int k = 0; k < 3; k++) {
				double p = Anim.pop(t, mineAt + 1.25 + k * 0.1, 0.4);
				Vec3 home = c.add((k - 1) * 0.55, 0.25 + 0.08 * Math.sin((t - mineAt) * 3 + k), -1.1);
				fx.item(home, new ItemStack(Items.DIAMOND), 0.35 * p, (float) (t * 120 + k * 60), false);
			}
		}
	}

	@Override
	void end() {
		if (level != null && xrayOn) xray(false);
		super.end();
	}
}
