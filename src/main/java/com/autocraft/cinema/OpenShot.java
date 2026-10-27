package com.autocraft.cinema;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Cold open at dawn, on the land around you, as it is (a forest is fine: nothing is cut down; the bot's walk goes
 * round the trees, and the camera steers round them). Over the intro the bot (in your skin, its status light red)
 * strolls out of the trees with the camera riding alongside; over the build-up the camera rises into the sky where
 * the logo flies in, landing on the drop, the title with it.
 */
final class OpenShot extends Shot {
	private static final int HALF = 10;   // the walk: from 10 blocks behind the spot you stood on to 8 past it
	private static final double SPEED = 1.7;

	private Direction dir = Direction.EAST;
	/** The walk, worked out on the real ground when the set is built. */
	private List<Vec3> way = List.of();
	private Anim.Track walk;
	private Actor bot;
	private Logo logo;

	OpenShot() {
		super("Open", null, 0, 23300);
	}

	@Override
	void build(ServerLevel level, BlockPos at) {
		origin = new BlockPos(at.getX(), Stage.ground(level, at.getX(), at.getZ()) + 1, at.getZ());
		dir = clearest(level);
		Vec3 d = unit(dir);
		BlockPos from = ground(level, d, -HALF), to = ground(level, d, HALF - 2);
		List<Vec3> route = from != null && to != null ? Walker.route(level, from, to, Walker.NOTHING) : null;
		if (route == null || route.size() < 2) {   // nowhere to walk round: straight along the ground
			route = new ArrayList<>();
			for (int k = -HALF; k <= HALF - 2; k++) {
				BlockPos c = ground(level, d, k);
				if (c != null) route.add(Walker.feet(level, c));
			}
		}
		way = route;
	}

	@Override
	void start() {
		Vec3 d = unit(dir), side = unit(dir.getClockWise());
		Vec3 first = way.isEmpty() ? Vec3.atBottomCenterOf(origin) : way.getFirst();
		walk = new Anim.Track().at(bar(0.4), first);
		for (int i = 1; i < way.size(); i++) walk.to(way.get(i), SPEED);
		bot = add(Actor.me(level, first, Anim.yaw(d)));
		bot.light = FilmHud.RED;
		logo = new Logo(Logo.above(level, origin.relative(dir, 60).above(58), dir.getOpposite(), 6), dir.getOpposite());
		Vec3 end = walk.last();
		Vec3 sky = Vec3.atCenterOf(logo.centre);
		double up = Math.max(44, sky.y - origin.getY() - 14);
		cam.key(0, on(bot, side.x * 3.0 - d.x * 2.5, 1.4, side.z * 3.0 - d.z * 2.5), on(bot, d.x * 2, 1.2, d.z * 2), 56)
				.key(bar(4), on(bot, side.x * 3.6 - d.x * 0.5, 1.5, side.z * 3.6 - d.z * 0.5), on(bot, d.x, 1.3, d.z))
				.key(bar(8), on(bot, side.x * 3.0 + d.x * 1.8, 1.8, side.z * 3.0 + d.z * 1.8), on(bot, 0, 1.4, 0))
				.key(bar(11), end.add(side.scale(7)).add(d.scale(-6)).add(0, 12, 0), end.add(d.scale(16)).add(0, 9, 0), 62)
				.key(bar(14.5), Vec3.atCenterOf(origin).add(d.scale(8)).add(0, up, 0), sky.add(0, -7, 0), 66)
				.key(duration, Vec3.atCenterOf(origin).add(d.scale(12)).add(0, up + 2, 0), sky.add(0, -8, 0), 64);
		cam.watch(on(bot, 0, 1.2, 0), 0, bar(9), 0.4);
	}

	/** The cardinal direction with the most walkable, driest, smoothest strip (east, toward the sunrise, if it's close). */
	private Direction clearest(Level level) {
		Direction best = Direction.EAST;
		double bestScore = Double.MAX_VALUE;
		for (Direction cand : new Direction[] {Direction.EAST, Direction.SOUTH, Direction.NORTH, Direction.WEST}) {
			Vec3 d = unit(cand);
			double score = cand == Direction.EAST ? -2 : 0;
			int prev = Integer.MIN_VALUE;
			for (int k = -HALF; k <= HALF; k++) {
				BlockPos c = ground(level, d, k);
				if (c == null) {
					score += 25;   // water or nowhere to stand
					continue;
				}
				if (prev != Integer.MIN_VALUE) score += Math.abs(c.getY() - prev) * (Math.abs(c.getY() - prev) > 1 ? 6 : 1);
				prev = c.getY();
			}
			if (score < bestScore) {
				bestScore = score;
				best = cand;
			}
		}
		return best;
	}

	/** The cell a player stands in on the real ground k blocks along d (under any canopy, never on a tree), or null. */
	private BlockPos ground(Level level, Vec3 d, int k) {
		int x = (int) Math.floor(origin.getX() + 0.5 + d.x * k), z = (int) Math.floor(origin.getZ() + 0.5 + d.z * k);
		int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		for (int y = top; y >= top - 12; y--) {
			BlockPos f = new BlockPos(x, y, z);
			BlockState under = level.getBlockState(f.below());
			if (under.is(BlockTags.LOGS) || under.is(BlockTags.LEAVES)) continue;
			if (!under.getFluidState().isEmpty()) return null;
			if (Walker.standable(level, f, Walker.NOTHING)) return f;
		}
		return null;
	}

	static Vec3 unit(Direction d) {
		return Vec3.atLowerCornerOf(d.getUnitVec3i());
	}

	@Override
	void tick(double t) {
		bot.follow(walk, t);
		if (t > walk.end() + 0.5) bot.look(Vec3.atCenterOf(logo.centre));
		double landed = bar(16);
		bot.lightFlash = Anim.window(t, landed - 0.3, landed, landed + 0.1, landed + 0.8);
		if (at(t, landed)) logo.place(this);
	}

	@Override
	void draw(Fx fx, double t) {
		logo.draw(fx, t, Logo.flyFrom(bar(16)));
		logo.title(fx, t, "autoCraft", bar(16), "Tell it what you want.", bar(16.5));
	}

	@Override
	void end() {
		if (logo != null) logo.remove(this);   // the next take builds it from nothing
		super.end();
	}
}
