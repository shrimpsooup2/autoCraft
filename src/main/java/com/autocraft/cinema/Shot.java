package com.autocraft.cinema;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * One shot of the film. The server builds its set ({@link #build}); then on the client it spawns its actors and
 * camera keys ({@link #start}), drives them every tick ({@link #tick}) and draws its floating graphics and 2D
 * overlays every frame ({@link #draw}, {@link #hud}). Times are seconds into the shot; {@link #bar} turns a bar of
 * the song ({@link Score}) into a time in this shot, so what happens lands on the music.
 */
abstract class Shot {
	final String name;
	/** Chapter label shown at the start ("Plan", "Find" …), or null. */
	final String tag;
	/** Its place in the film (and so in the song). */
	final int slot;
	final double duration;
	/** Minecraft time of day for this shot (0 = sunrise, 6000 = noon, 12000 = sunset, 18000 = midnight). */
	final long dayTime;
	final Cam cam = new Cam();
	final List<Actor> actors = new ArrayList<>();
	/** The set's anchor in the world and its ground height (filled in by build). */
	BlockPos origin = BlockPos.ZERO;
	ClientLevel level;
	private double prev = -1;

	Shot(String name, String tag, int slot, long dayTime) {
		this.name = name;
		this.tag = tag;
		this.slot = slot;
		this.duration = Score.length(slot);
		this.dayTime = dayTime;
	}

	/** The time in this shot of bar b of the song (16.5 = two beats into bar 16). */
	final double bar(double b) {
		return Score.bar(b) - Score.start(slot);
	}

	/** Where this shot's set goes relative to the film's hub (sets stay within render distance of each other). */
	BlockPos offset() {
		return BlockPos.ZERO;
	}

	/** Server thread: build the set around `at`; set {@link #origin}. */
	abstract void build(ServerLevel level, BlockPos at);

	/** Client: spawn actors, lay out the camera. */
	abstract void start();

	/** Client, 20 times a second. */
	abstract void tick(double t);

	void draw(Fx fx, double t) {}

	/** Where an actor is drawn this frame, plus an offset: for camera keys and watches that move with it. */
	static Supplier<Vec3> on(Actor a, double dx, double dy, double dz) {
		return () -> a.entity.getPosition(partialTick()).add(dx, dy, dz);
	}

	static float partialTick() {
		return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
	}

	/**
	 * Before the shot shows: take away leaves, logs and plants (never the sets' own blocks) where the camera will
	 * pass and in the few blocks in front of it, so it never flies through a tree or looks through a bush.
	 */
	final void clearCameraPath() {
		if (cam.isEmpty()) return;
		Set<Long> gone = new HashSet<>();
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (double t = 0; t <= duration; t += 0.1) {
			Cam.Pose p = cam.at(t);
			Vec3 dir = p.look().subtract(p.pos());
			double len = dir.length();
			Vec3 ahead = len > 1e-6 ? dir.scale(Math.min(5, len) / len) : Vec3.ZERO;
			for (int k = 0; k <= 5; k++) {
				Vec3 c = p.pos().add(ahead.scale(k / 5.0));
				double r = k == 0 ? 1.7 : 1.0;
				int ri = (int) Math.ceil(r);
				for (int x = -ri; x <= ri; x++) {
					for (int y = -ri; y <= ri; y++) {
						for (int z = -ri; z <= ri; z++) {
							m.set(Math.floor(c.x) + x, Math.floor(c.y) + y, Math.floor(c.z) + z);
							if (Vec3.atCenterOf(m).distanceToSqr(c) > r * r) continue;
							long key = m.asLong();
							if (!gone.contains(key) && softNatural(m)) gone.add(key);
						}
					}
				}
			}
		}
		for (long key : gone) place(BlockPos.of(key), Blocks.AIR.defaultBlockState());
	}

	/** Foliage the camera may clear: leaves, logs and see-through plants that no set put there. */
	private boolean softNatural(BlockPos p) {
		if (Stage.BUILT.contains(p.asLong())) return false;
		BlockState s = level.getBlockState(p);
		if (s.isAir() || !s.getFluidState().isEmpty() || s.hasBlockEntity()) return false;
		return s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS) || s.getCollisionShape(level, p).isEmpty();
	}

	/**
	 * The status light over each actor that has one — red idle, green running, amber recovering: a small cube that
	 * bobs and turns, in a faint glow that flares when it flashes.
	 */
	final void lights(Fx fx, double t) {
		for (Actor a : actors) {
			if (a.light == 0 || a.entity.isRemoved()) continue;
			double phase = a.entity.getId() * 1.7;   // so several bots don't bob in step
			Vec3 p = a.entity.getPosition(fx.partial).add(0, a.entity.getBbHeight() + 0.45 + 0.05 * Math.sin(t * 2.4 + phase), 0);
			double f = a.lightFlash;
			fx.halo(p, 1.5 * (1 + 1.2 * f), Fx.argb((int) (120 + 100 * f), a.light));                // wide bloom
			fx.halo(p, 0.55 * (1 + 0.8 * f), Fx.argb(235, Fx.mix(a.light, 0xFFFFFF, 0.35 + 0.4 * f)));  // hot rim round the cube
			fx.pixelCube(p, 0.2 * (1 + 0.5 * f), (float) (t * 75 + phase * 40), a.light, f);
		}
	}

	/** An item flying from a to b over [t0, t1] on an arc, spinning. */
	static void flight(Fx fx, net.minecraft.world.item.ItemStack stack, Vec3 a, Vec3 b, double t, double t0, double t1, double size, double arc) {
		if (t < t0 || t > t1) return;
		double u = Anim.smooth((t - t0) / (t1 - t0));
		fx.item(Anim.arc(a, b, u, arc), stack, size, (float) (t * 240), false);
	}

	void hud(FilmHud hud, double t) {}

	final void begin(ClientLevel level) {
		this.level = level;
		prev = -1;
		start();
	}

	/**
	 * The shot's own story clock at film time t (what tick gets, and what {@link #at}/{@link #every} count in): the
	 * same by default; a shot told faster, slower or in slow motion maps it.
	 */
	double clock(double t) {
		return t;
	}

	/**
	 * A story told faster or slower in places: pairs of (bars into the shot, story seconds), straight between them.
	 * A shot's {@link #clock} can run on one so its moments land on chosen bars.
	 */
	static final class Warp {
		private final double[][] pts;

		Warp(double[][] barsToStory) {
			this.pts = barsToStory;
		}

		double story(double filmTime) {
			return map(filmTime / Score.BAR, 0, 1);
		}

		/** The film time at which the story reaches `story` seconds. */
		double film(double story) {
			return map(story, 1, 0) * Score.BAR;
		}

		private double map(double v, int from, int to) {
			if (v <= pts[0][from]) return pts[0][to] + (v - pts[0][from]) * slope(0, from, to);
			for (int i = 1; i < pts.length; i++) {
				if (v <= pts[i][from]) {
					double u = (v - pts[i - 1][from]) / (pts[i][from] - pts[i - 1][from]);
					return pts[i - 1][to] + (pts[i][to] - pts[i - 1][to]) * u;
				}
			}
			return pts[pts.length - 1][to] + (v - pts[pts.length - 1][from]) * slope(pts.length - 2, from, to);
		}

		private double slope(int i, int from, int to) {
			return (pts[i + 1][to] - pts[i][to]) / (pts[i + 1][from] - pts[i][from]);
		}
	}

	final void step(double t) {
		double c = clock(t);
		tick(c);
		prev = c;
	}

	void end() {
		for (Actor a : actors) a.remove();
		actors.clear();
	}

	/** True on the one tick that passes `when`. */
	final boolean at(double t, double when) {
		return prev < when && t >= when;
	}

	/** True every `period` seconds within [from, to). */
	final boolean every(double t, double from, double to, double period) {
		if (t < from || t >= to) return false;
		return Math.floor((t - from) / period) != Math.floor((prev - from) / period) || prev < from;
	}

	Actor add(Actor a) {
		actors.add(a);
		return a;
	}

	// ---- positions relative to the set ------------------------------------------------------------------------------

	Vec3 v(double x, double y, double z) {
		return new Vec3(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
	}

	BlockPos b(int x, int y, int z) {
		return origin.offset(x, y, z);
	}

	/** Standing height (feet) at a set position, from the client's world. */
	Vec3 feet(double x, double z) {
		int wx = (int) Math.floor(origin.getX() + x), wz = (int) Math.floor(origin.getZ() + z);
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wx, wz);
		return new Vec3(origin.getX() + x, y, origin.getZ() + z);
	}

	// ---- changing the world mid-shot ------------------------------------------------------------------------------

	/** Set a block now on the client (seen this frame) and on the server (so it stays). */
	void place(BlockPos p, BlockState s) {
		level.setBlock(p, s, Stage.SET_FLAGS);
		MinecraftServer server = Stage.server();
		if (server != null) server.execute(() -> Stage.set(server.overworld(), p, s));
	}

	/** Crack overlay on a block, stage 0..9 (−1 removes it). */
	void crack(BlockPos p, int stage) {
		level.destroyBlockProgress(p.hashCode(), p, stage);
	}

	/** Break a block: debris and sound, gone. */
	void dig(BlockPos p) {
		BlockState s = level.getBlockState(p);
		if (s.isAir()) return;
		crack(p, -1);
		level.levelEvent(null, LevelEvent.PARTICLES_DESTROY_BLOCK, p, Block.getId(s));
		place(p, Blocks.AIR.defaultBlockState());
	}

	/** Put a block with the placing sound. */
	void put(BlockPos p, BlockState s) {
		place(p, s);
		level.playLocalSound(p, s.getSoundType().getPlaceSound(), net.minecraft.sounds.SoundSource.BLOCKS, 1, 1, false);
	}

	void particles(ParticleOptions type, Vec3 at, int n, double spread, double speed) {
		var r = level.getRandom();
		for (int i = 0; i < n; i++) {
			level.addParticle(type, at.x + (r.nextDouble() - 0.5) * spread, at.y + (r.nextDouble() - 0.5) * spread, at.z + (r.nextDouble() - 0.5) * spread,
					(r.nextDouble() - 0.5) * speed, r.nextDouble() * speed, (r.nextDouble() - 0.5) * speed);
		}
	}

	static Minecraft mc() {
		return Minecraft.getInstance();
	}
}
