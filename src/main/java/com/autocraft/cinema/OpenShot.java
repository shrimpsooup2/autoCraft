package com.autocraft.cinema;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Cold open at dawn, on the land around you: the bot (in your skin, its status light red) strolls across the
 * grass; the camera starts low among it, rides alongside, then rises into the sky where the logo assembles from
 * blocks, with the title under it.
 */
final class OpenShot extends Shot {
	private static final double LOGO_T0 = 10.8;

	private Direction dir = Direction.EAST;
	private Anim.Track walk;
	private Actor bot;
	private Logo logo;

	OpenShot() {
		super("Open", null, 17, 23300);
	}

	@Override
	void build(ServerLevel level, BlockPos at) {
		origin = new BlockPos(at.getX(), Stage.ground(level, at.getX(), at.getZ()) + 1, at.getZ());
	}

	@Override
	void start() {
		dir = gentlest();
		Vec3 d = unit(dir), side = unit(dir.getClockWise());
		walk = new Anim.Track().at(1.0, along(d, -7));
		for (int k = -6; k <= 7; k++) walk.to(along(d, k), 2.4);
		bot = add(Actor.me(level, walk.pos(0), Anim.yaw(d)));
		bot.light = FilmHud.RED;
		logo = new Logo(origin.relative(dir, 60).above(58), dir.getOpposite());
		Vec3 end = walk.last();
		Vec3 sky = Vec3.atCenterOf(logo.centre);
		cam.key(0, walk.pos(0).add(side.scale(3.2)).add(d.scale(-3)).add(0, 0.6, 0), walk.pos(2.0).add(0, 1.2, 0), 58)
				.key(5.5, walk.pos(5.5).add(side.scale(4.0)).add(d.scale(-0.5)).add(0, 1.2, 0), walk.pos(6.0).add(0, 1.3, 0))
				.key(9.5, end.add(side.scale(9)).add(d.scale(-9)).add(0, 15, 0), end.add(d.scale(16)).add(0, 9, 0), 62)
				.key(13.5, Vec3.atCenterOf(origin).add(d.scale(8)).add(0, 44, 0), sky.add(0, -7, 0), 66)
				.key(duration, Vec3.atCenterOf(origin).add(d.scale(12)).add(0, 46, 0), sky.add(0, -8, 0), 64);
	}

	/** The cardinal direction with the smoothest, driest 15-block strip (east, toward the sunrise, if it's close). */
	private Direction gentlest() {
		Direction best = Direction.EAST;
		double bestScore = Double.MAX_VALUE;
		for (Direction cand : new Direction[] {Direction.EAST, Direction.SOUTH, Direction.NORTH, Direction.WEST}) {
			Vec3 d = unit(cand);
			double score = cand == Direction.EAST ? -2 : 0;
			double prev = Double.NaN;
			for (int k = -7; k <= 7; k++) {
				Vec3 p = along(d, k);
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

	private Vec3 along(Vec3 d, double k) {
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
		double landed = Logo.landed(LOGO_T0);
		bot.lightFlash = Anim.window(t, landed - 0.3, landed, landed + 0.1, landed + 0.8);
		if (at(t, landed)) logo.place(this);
	}

	@Override
	void draw(Fx fx, double t) {
		logo.draw(fx, t, LOGO_T0);
		double landed = Logo.landed(LOGO_T0);
		logo.title(fx, t, "autoCraft", landed + 0.2, "Tell it what you want.", landed + 0.9);
	}

	@Override
	void end() {
		if (logo != null) logo.remove(this);   // the next take builds it from nothing
		super.end();
	}
}
