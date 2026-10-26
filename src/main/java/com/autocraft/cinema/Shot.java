package com.autocraft.cinema;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * One shot of the film. The server builds its set ({@link #build}); then on the client it spawns its actors and
 * camera keys ({@link #start}), drives them every tick ({@link #tick}) and draws its floating graphics and 2D
 * overlays every frame ({@link #draw}, {@link #hud}). Times are seconds into the shot.
 */
abstract class Shot {
	final String name;
	/** Chapter label shown at the start ("Plan", "Find" …), or null. */
	final String tag;
	final double duration;
	/** Minecraft time of day for this shot (0 = sunrise, 6000 = noon, 12000 = sunset, 18000 = midnight). */
	final long dayTime;
	final Cam cam = new Cam();
	final List<Actor> actors = new ArrayList<>();
	/** The set's anchor in the world and its ground height (filled in by build). */
	BlockPos origin = BlockPos.ZERO;
	ClientLevel level;
	private double prev = -1;

	Shot(String name, String tag, double duration, long dayTime) {
		this.name = name;
		this.tag = tag;
		this.duration = duration;
		this.dayTime = dayTime;
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

	/** The status light over each actor that has one: the bot's red (idle), green (running), amber (recovering). */
	final void lights(Fx fx) {
		for (Actor a : actors) {
			if (a.light == 0 || a.entity.isRemoved()) continue;
			Vec3 p = a.entity.getPosition(fx.partial).add(0, a.entity.getBbHeight() + 0.4, 0);
			double s = 0.09 * (1 + a.lightFlash);
			fx.glowBox(new net.minecraft.world.phys.AABB(p.subtract(s, s, s), p.add(s, s, s)), Fx.argb(255, a.light));
			fx.spark(p, 0.5 + 1.2 * a.lightFlash, Fx.argb((int) (90 + 120 * a.lightFlash), a.light));
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

	final void step(double t) {
		tick(t);
		prev = t;
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
