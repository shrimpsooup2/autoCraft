package com.autocraft.cinema;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Cold open at dawn, on the land around you (a forest is fine: the bot's way is cleared at walking height under
 * the canopy, and the camera's own way is cleared before the shot). Over the intro the bot (in your skin, its
 * status light red) strolls out of the trees with the camera riding alongside; over the build-up the camera rises
 * into the sky where the logo flies in, landing on the drop, the title with it.
 */
final class OpenShot extends Shot {
	private static final int HALF = 10;   // the walk: from 10 blocks behind the spot you stood on to 8 past it

	private Direction dir = Direction.EAST;
	private Anim.Track walk;
	private Actor bot;
	private Logo logo;

	OpenShot() {
		super("Open", null, 0, 23300);
	}

	@Override
	void build(ServerLevel level, BlockPos at) {
		origin = new BlockPos(at.getX(), Stage.ground(level, at.getX(), at.getZ()) + 1, at.getZ());
		dir = gentlest(level);
		// the walk: trunks, low leaves and plants out of the way up to head height, the canopy stays overhead
		Vec3 d = unit(dir);
		for (int k = -HALF; k <= HALF - 2; k++) {
			Vec3 p = along(level, d, k);
			for (int s = -1; s <= 1; s++) {
				BlockPos base = BlockPos.containing(p).relative(dir.getClockWise(), s);
				for (int y = 0; y <= 2; y++) {
					BlockPos c = base.above(y);
					BlockState st = level.getBlockState(c);
					if (st.is(BlockTags.LOGS) || st.is(BlockTags.LEAVES) || (!st.isAir() && st.getCollisionShape(level, c).isEmpty() && st.getFluidState().isEmpty())) {
						Stage.set(level, c, Blocks.AIR.defaultBlockState());
					}
				}
			}
		}
	}

	@Override
	void start() {
		Vec3 d = unit(dir), side = unit(dir.getClockWise());
		walk = new Anim.Track().at(bar(0.4), along(level, d, -HALF));
		for (int k = -HALF + 1; k <= HALF - 2; k++) walk.to(along(level, d, k), 1.7);
		bot = add(Actor.me(level, walk.pos(0), Anim.yaw(d)));
		bot.light = FilmHud.RED;
		logo = new Logo(Logo.above(level, origin.relative(dir, 60).above(58), dir.getOpposite(), 6), dir.getOpposite());
		Vec3 end = walk.last();
		Vec3 sky = Vec3.atCenterOf(logo.centre);
		double up = Math.max(44, sky.y - origin.getY() - 14);
		cam.key(0, on(bot, side.x * 3.0 - d.x * 2.5, 0.7, side.z * 3.0 - d.z * 2.5), on(bot, d.x * 2, 1.2, d.z * 2), 56)
				.key(bar(4), on(bot, side.x * 3.6 - d.x * 0.5, 1.1, side.z * 3.6 - d.z * 0.5), on(bot, d.x, 1.3, d.z))
				.key(bar(8), on(bot, side.x * 3.0 + d.x * 1.8, 1.6, side.z * 3.0 + d.z * 1.8), on(bot, 0, 1.4, 0))
				.key(bar(11), end.add(side.scale(7)).add(d.scale(-6)).add(0, 12, 0), end.add(d.scale(16)).add(0, 9, 0), 62)
				.key(bar(14.5), Vec3.atCenterOf(origin).add(d.scale(8)).add(0, up, 0), sky.add(0, -7, 0), 66)
				.key(duration, Vec3.atCenterOf(origin).add(d.scale(12)).add(0, up + 2, 0), sky.add(0, -8, 0), 64);
		cam.watch(on(bot, 0, 1.2, 0), 0, bar(9), 0.4);
	}

	/** The cardinal direction with the smoothest, driest strip (east, toward the sunrise, if it's close). */
	private Direction gentlest(Level level) {
		Direction best = Direction.EAST;
		double bestScore = Double.MAX_VALUE;
		for (Direction cand : new Direction[] {Direction.EAST, Direction.SOUTH, Direction.NORTH, Direction.WEST}) {
			Vec3 d = unit(cand);
			double score = cand == Direction.EAST ? -2 : 0;
			double prev = Double.NaN;
			for (int k = -HALF; k <= HALF; k++) {
				Vec3 p = along(level, d, k);
				BlockPos under = BlockPos.containing(p).below();
				if (!level.getBlockState(under).getFluidState().isEmpty()) score += 20;
				if (!Double.isNaN(prev)) score += Math.abs(p.y - prev) * (Math.abs(p.y - prev) > 1 ? 6 : 1);
				prev = p.y;
			}
			if (score < bestScore) {
				bestScore = score;
				best = cand;
			}
		}
		return best;
	}

	/** The standing point k blocks along d from where you stood (on the ground, under any canopy). */
	private Vec3 along(Level level, Vec3 d, double k) {
		int x = (int) Math.floor(origin.getX() + 0.5 + d.x * k), z = (int) Math.floor(origin.getZ() + 0.5 + d.z * k);
		return new Vec3(x + 0.5, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z + 0.5);
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
