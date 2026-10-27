package com.autocraft.cinema;

import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Speedrunner moves, on the punchy bars: the bot steps off a ledge high in a cave and drops; just before the floor
 * it pours its water bucket and lands in it (the clutch, on the hit), scoops the water back up and sprints to a
 * lava pool. There it casts a nether portal frame one obsidian at a time with that one bucket — lava in, water on
 * it, obsidian — a block a beat, lights it and walks in. Purple, and it steps out among netherrack and lava.
 */
final class NetherShot extends Shot {
	/** The portal frame (no corners): offsets in the portal's plane (z along it, y up) from its bottom-left. */
	private static final int[][] FRAME = {{0, 0}, {1, 0}, {-1, 1}, {-1, 2}, {-1, 3}, {2, 1}, {2, 2}, {2, 3}, {0, 4}, {1, 4}};
	/** The way into the nether pocket, beside the cave (it's sealed off from it). */
	private static final int NETHER_X = 30;

	private final double STEP_OFF = bar(51.1), WATER = bar(51.9), LAND = bar(52), SCOOP = bar(52.25), SPRINT = bar(52.35), CAST = bar(53),
			LIGHT = bar(55.5), WALK_IN = bar(55.75), CUT = bar(56.25);
	private Actor bot;
	private Vec3 ledge, landing, workSpot, portalIn, portalOut;
	private Anim.Track sprint, walkIn, walkOut;

	NetherShot() {
		super("Nether", null, 5, 18000);
	}

	@Override
	BlockPos offset() {
		return new BlockPos(-70, 0, -70);
	}

	@Override
	void build(ServerLevel level, BlockPos at) {
		int floor = Math.max(level.getMinY() + 12, Stage.ground(level, at.getX(), at.getZ()) - 32);
		origin = new BlockPos(at.getX(), floor + 1, at.getZ());
		Random rng = new Random(11);
		BlockState[] rock = {Blocks.STONE.defaultBlockState(), Blocks.STONE.defaultBlockState(), Blocks.ANDESITE.defaultBlockState(),
			Blocks.TUFF.defaultBlockState(), Blocks.DEEPSLATE.defaultBlockState(), Blocks.GRAVEL.defaultBlockState()};
		// the cave: 23 × 13, 18 high
		for (int x = -13; x <= 11; x++) {
			for (int z = -7; z <= 7; z++) {
				for (int y = -2; y <= 18; y++) {
					boolean shell = x == -13 || x == 11 || z == -7 || z == 7 || y <= -1 || y == 18;
					BlockState s = !shell ? Blocks.AIR.defaultBlockState()
							: rng.nextInt(40) == 0 ? (rng.nextBoolean() ? Blocks.IRON_ORE : Blocks.COAL_ORE).defaultBlockState() : rock[rng.nextInt(rock.length)];
					if (y == -1 && shell && s.is(Blocks.GRAVEL)) s = Blocks.STONE.defaultBlockState();
					Stage.set(level, b(x, y, z), s);
				}
			}
		}
		// the ledge the bot drops from
		Stage.fill(level, origin.getX() - 12, origin.getY() + 13, origin.getZ() - 1, origin.getX() - 10, origin.getY() + 14, origin.getZ() + 1, Blocks.STONE.defaultBlockState());
		// the lava pool, two deep, to the side of where the portal goes
		for (int x = 2; x <= 7; x++) {
			for (int z = -5; z <= -2; z++) {
				Stage.set(level, b(x, -1, z), Blocks.LAVA.defaultBlockState());
				Stage.set(level, b(x, -2, z), Blocks.LAVA.defaultBlockState());
			}
		}
		// a little light where the lava doesn't reach (invisible light blocks)
		for (BlockPos p : List.of(b(-9, 2, 0), b(-11, 16, 0), b(-4, 3, 2))) Stage.set(level, p, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 10));
		// the nether pocket: netherrack, soul sand, magma, a lava lake, glowstone, and the portal the bot comes out of
		for (int x = NETHER_X - 8; x <= NETHER_X + 8; x++) {
			for (int z = -7; z <= 7; z++) {
				for (int y = -1; y <= 9; y++) {
					boolean shell = x == NETHER_X - 8 || x == NETHER_X + 8 || z == -7 || z == 7 || y == -1 || y == 9;
					BlockState s = !shell ? Blocks.AIR.defaultBlockState() : y == 9 && rng.nextInt(5) == 0 ? Blocks.GLOWSTONE.defaultBlockState()
							: y == -1 && rng.nextInt(6) == 0 ? Blocks.SOUL_SAND.defaultBlockState() : y == -1 && rng.nextInt(9) == 0 ? Blocks.MAGMA_BLOCK.defaultBlockState()
							: rng.nextInt(18) == 0 ? (rng.nextBoolean() ? Blocks.NETHER_QUARTZ_ORE : Blocks.NETHER_GOLD_ORE).defaultBlockState()
							: Blocks.NETHERRACK.defaultBlockState();
					Stage.set(level, b(x, y, z), s);
				}
			}
		}
		for (int x = NETHER_X + 3; x <= NETHER_X + 7; x++) {
			for (int z = -6; z <= -2; z++) Stage.set(level, b(x, -1, z), Blocks.LAVA.defaultBlockState());
		}
		for (int[] g : new int[][] {{NETHER_X - 4, 3}, {NETHER_X + 2, -4}, {NETHER_X + 5, 4}}) Stage.set(level, b(g[0], 8, g[1]), Blocks.GLOWSTONE.defaultBlockState());
		frame(level, NETHER_X - 5, true);
		// the cave portal starts empty (a replay casts it again)
		for (int[] f : FRAME) Stage.set(level, cell(3, f), Blocks.AIR.defaultBlockState());
		for (int z = 0; z <= 1; z++) {
			for (int y = 1; y <= 3; y++) Stage.set(level, b(3, y, z), Blocks.AIR.defaultBlockState());
		}
	}

	/** A finished portal (obsidian frame, lit) in the plane x = px. */
	private void frame(ServerLevel level, int px, boolean lit) {
		for (int[] f : FRAME) Stage.set(level, cell(px, f), Blocks.OBSIDIAN.defaultBlockState());
		if (!lit) return;
		for (int z = 0; z <= 1; z++) {
			for (int y = 1; y <= 3; y++) Stage.set(level, b(px, y, z), portal());
		}
	}

	private BlockPos cell(int px, int[] f) {
		return b(px, f[1], f[0]);
	}

	private static BlockState portal() {
		return Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS, Direction.Axis.Z);
	}

	@Override
	void start() {
		ledge = v(-9.6, 15, 0.5);
		landing = v(-8.5, 0, 0.5);
		workSpot = v(1.4, 0, 1.0);
		portalIn = v(3.5, 0, 1.0);
		portalOut = v(NETHER_X - 4.5, 0, 1.0);
		bot = add(Actor.me(level, ledge, -90));
		bot.light = 0x5EE36A;
		bot.hold(Items.WATER_BUCKET);
		sprint = new Anim.Track().at(SPRINT, landing).to(workSpot, 11);
		walkIn = new Anim.Track().at(WALK_IN, workSpot).to(portalIn, 4.5);
		walkOut = new Anim.Track().at(CUT + 0.15, portalOut).to(v(NETHER_X - 1.5, 0, 2.0), 4.3);
		Vec3 portal = v(3.5, 2.5, 1.0);
		cam.key(0, v(-3.5, 2.4, 6.5), v(-9, 12.5, 0.5), 64)
				.key(LAND - 0.1, v(-4.2, 1.6, 5.4), landing.add(0, 1.0, 0), 62)
				.key(SPRINT + 0.6, v(-3.0, 2.2, 5.6), v(-1.5, 1.2, 0.5))
				.key(CAST + 0.3, v(-1.6, 2.5, 4.6), portal.add(-0.6, -0.4, -1.2), 56)
				.key(LIGHT, v(-1.2, 2.9, 4.4), portal.add(-0.3, 0, -0.6), 56)
				.key(CUT - 0.05, v(-0.6, 1.9, 2.4), portal.add(0, -0.6, 0), 48)
				.cut(CUT)
				.key(CUT, v(NETHER_X + 3.5, 2.6, 5.0), portalOut.add(0.5, 1.5, 0), 60)
				.key(duration, v(NETHER_X + 2.5, 2.3, 4.2), portalOut.add(2.0, 1.3, 0.5), 58);
		cam.watch(on(bot, 0, 1.0, 0), 0, LAND + 0.3, 0.75);
	}

	/** When frame block i goes in (one a beat from CAST). */
	private double castAt(int i) {
		return CAST + i * Score.BEAT;
	}

	@Override
	void tick(double t) {
		// the drop: off the ledge, falling ever faster; water poured just before the floor; the landing in it
		bot.free = t >= STEP_OFF && t < LAND;   // over the edge and down
		if (t < STEP_OFF) {
			bot.moveTo(ledge);
		} else if (t < LAND) {   // over the edge in the first quarter of the fall, then straight down, faster and faster
			double u = Anim.clamp01((t - STEP_OFF) / (LAND - STEP_OFF));
			double x = ledge.x + (landing.x - ledge.x) * Math.min(1, u * 4);
			bot.moveTo(new Vec3(x, ledge.y + (landing.y - ledge.y) * u * u, landing.z)).pitch(70);
		}
		BlockPos pool = BlockPos.containing(landing);
		if (at(t, WATER)) {
			bot.swing();
			level.setBlock(pool, Blocks.WATER.defaultBlockState(), Stage.SET_FLAGS);   // only on this client: it never flows
			level.playLocalSound(pool, SoundEvents.BUCKET_EMPTY, SoundSource.PLAYERS, 0.8f, 1, false);
			bot.hold(Items.BUCKET);
		}
		if (at(t, LAND)) {
			bot.moveTo(landing).pitch(30);
			particles(ParticleTypes.SPLASH, landing.add(0, 0.4, 0), 40, 1.4, 0.3);
			level.playLocalSound(pool, SoundEvents.PLAYER_SPLASH, SoundSource.PLAYERS, 0.8f, 1, false);
		}
		if (at(t, SCOOP)) {
			bot.swing();
			level.setBlock(pool, Blocks.AIR.defaultBlockState(), Stage.SET_FLAGS);
			level.playLocalSound(pool, SoundEvents.BUCKET_FILL, SoundSource.PLAYERS, 0.8f, 1, false);
			bot.hold(Items.WATER_BUCKET);
			bot.pitch(0);
		}
		if (t >= SPRINT && t < WALK_IN) {
			bot.follow(sprint, t);
			if (t > sprint.end()) bot.face(-90);
		}
		// the portal, cast a block a beat: lava scooped and poured into the mould, water over it, obsidian
		for (int i = 0; i < FRAME.length; i++) {
			BlockPos c = cell(3, FRAME[i]);
			double at = castAt(i);
			if (at(t, at - Score.BEAT * 0.5)) {
				bot.hold(Items.LAVA_BUCKET);
				bot.look(v(4.5, -0.5, -3.5));
				level.playLocalSound(c, SoundEvents.BUCKET_FILL_LAVA, SoundSource.PLAYERS, 0.5f, 1, false);
			}
			if (at(t, at)) {
				bot.swing();
				bot.look(Vec3.atCenterOf(c));
				level.setBlock(c, Blocks.LAVA.defaultBlockState(), Stage.SET_FLAGS);   // client only: a flash of lava
				level.playLocalSound(c, SoundEvents.BUCKET_EMPTY_LAVA, SoundSource.PLAYERS, 0.5f, 1, false);
			}
			if (at(t, at + Score.BEAT * 0.4)) {
				bot.hold(Items.WATER_BUCKET);
				bot.swing();
				place(c, Blocks.OBSIDIAN.defaultBlockState());
				particles(ParticleTypes.LARGE_SMOKE, Vec3.atCenterOf(c), 8, 0.7, 0.04);
				level.playLocalSound(c, SoundEvents.LAVA_EXTINGUISH, SoundSource.BLOCKS, 0.4f, 1.2f, false);
			}
		}
		// lit, and in
		if (at(t, LIGHT - 0.25)) {
			bot.hold(Items.FLINT_AND_STEEL);
			bot.look(v(3.5, 1.2, 0.5));
		}
		if (at(t, LIGHT)) {
			bot.swing();
			for (int z = 0; z <= 1; z++) {
				for (int y = 1; y <= 3; y++) place(b(3, y, z), portal());
			}
			level.playLocalSound(b(3, 1, 0), SoundEvents.FLINTANDSTEEL_USE, SoundSource.PLAYERS, 0.8f, 1, false);
			particles(ParticleTypes.PORTAL, v(3.5, 2.5, 1.0), 40, 1.6, 0.4);
		}
		if (t >= WALK_IN && t < CUT) bot.follow(walkIn, t).look(null);
		if (at(t, CUT - 0.25)) level.playLocalSound(b(3, 2, 0), SoundEvents.PORTAL_TRIGGER, SoundSource.AMBIENT, 0.25f, 1.4f, false);
		// the other side
		if (at(t, CUT)) {
			bot.teleport(portalOut);
			bot.hold(Items.AIR);
		}
		if (t >= CUT) bot.follow(walkOut, t).look(t > walkOut.end() ? v(NETHER_X + 6, 1.5, -4) : null);
	}

	@Override
	void end() {
		if (level != null) {   // the next take casts the portal again
			for (int[] f : FRAME) place(cell(3, f), Blocks.AIR.defaultBlockState());
			for (int z = 0; z <= 1; z++) {
				for (int y = 1; y <= 3; y++) place(b(3, y, z), Blocks.AIR.defaultBlockState());
			}
		}
		super.end();
	}

	@Override
	void hud(FilmHud hud, double t) {
		hud.tint(0x7A2BD9, Math.max(Anim.ramp(t, CUT - 0.45, CUT), 0) * (1 - Anim.ramp(t, CUT, CUT + 0.5)) * 0.85);
	}
}
