package com.autocraft.cinema;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.phys.Vec3;

/**
 * Surviving the night, in three bars of the song: a creeper hisses right behind the bot — it jumps, crits it on
 * the beat, hits again, poof. An arrow from the dark knocks hearts off; the bot sprints at the skeleton and puts it
 * down on the next bar, then back to work. Fast, like the bot (and the music) at this point.
 */
final class SurviveShot extends Shot {
	private static final int AMBER = 0xFFBF3C, GREEN = 0x5EE36A;

	private final double CRIT = bar(48.5), HIT2 = bar(48.75), POOF = bar(49), ARROW = bar(49.25), CHARGE = bar(49.4), STRIKE = bar(50),
			KILL = bar(50.25), BACK = bar(50.4);
	private Actor bot, creeper, skeleton;
	private Vec3 work, cStart, kb, skel, reach;
	private Anim.Track charge, home;
	private int health = 20;

	SurviveShot() {
		super("Survive", "Survive", 4, 18000);
	}

	@Override
	BlockPos offset() {
		return new BlockPos(0, 0, -70);
	}

	@Override
	void build(ServerLevel level, BlockPos at) {
		int y = Stage.pad(level, at.getX(), at.getZ(), 16);
		origin = new BlockPos(at.getX(), y + 1, at.getZ());
		Stage.fill(level, origin.getX() + 3, y + 1, origin.getZ() - 1, origin.getX() + 4, y + 2, origin.getZ() + 1, Blocks.STONE.defaultBlockState());
		Stage.set(level, origin.getX() + 4, y + 3, origin.getZ(), Blocks.STONE.defaultBlockState());
		Stage.set(level, origin.getX() + 4, y + 2, origin.getZ() + 1, Blocks.COAL_ORE.defaultBlockState());
		for (int x = -3; x <= 3; x++) {   // the skeleton's mound
			for (int z = -3; z <= 3; z++) {
				double d = Math.sqrt(x * x + z * z);
				int h = d < 1.8 ? 2 : d < 3.3 ? 1 : 0;
				for (int k = 1; k <= h; k++) Stage.set(level, origin.getX() - 7 + x, y + k, origin.getZ() + 7 + z, Blocks.GRASS_BLOCK.defaultBlockState());
			}
		}
		Stage.set(level, origin.getX(), y + 1, origin.getZ() + 2, Blocks.TORCH.defaultBlockState());
		Stage.set(level, origin.getX() - 3, y + 1, origin.getZ() - 2, Blocks.TORCH.defaultBlockState());
		Stage.set(level, origin.getX() + 3, y + 2, origin.getZ() - 2, Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, Direction.NORTH));
		Sets.oak(level, origin.getX() + 9, y + 1, origin.getZ() + 8, 6);
		Sets.oak(level, origin.getX() - 12, y + 1, origin.getZ() - 4, 5);
	}

	@Override
	void start() {
		work = v(2.5, 0, 0.5);
		bot = add(Actor.me(level, work, -90));
		bot.light = GREEN;
		bot.hold(Items.IRON_SWORD);
		cStart = v(1.0, 0, -1.6);
		kb = v(-1.2, 0, -3.4);
		creeper = add(Actor.creeper(level, cStart, 20));
		creeper.fuse(1);
		skel = v(-7.5, 2, 7.5);
		skeleton = add(Actor.skeleton(level, skel, 150));
		skeleton.aggressive(true);
		Vec3 toBot = new Vec3(work.x - skel.x, 0, work.z - skel.z).normalize();
		reach = new Vec3(skel.x + toBot.x * 2.4, origin.getY() + 1, skel.z + toBot.z * 2.4);   // the mound's lower step, in reach
		charge = new Anim.Track().at(CHARGE, work).to(reach, 9);
		home = new Anim.Track().at(BACK, reach).to(work, 6);
		Vec3 mid = work.lerp(skel, 0.5);
		cam.key(0, v(4.6, 1.6, -4.4), v(1.6, 1.2, -0.8), 54)
				.key(POOF, v(4.2, 2.0, -5.0), v(0.8, 1.2, -1.4))
				.key(STRIKE, v(1.5, 2.6, -4.6), mid.add(0, 1.0, 0), 62)
				.key(duration, v(4.0, 2.4, -3.6), work.add(0, 1.2, 0), 58);
		cam.watch(on(bot, 0, 1.1, 0), CHARGE, duration, 0.55);
	}

	@Override
	void tick(double t) {
		// the creeper: fuse lit; the bot turns, jumps on the beat, crits, hits again, poof
		if (t < POOF) {
			creeper.look(bot.eyes());
			bot.look(creeper.eyes());
			double jump = Math.sin(Math.PI * Anim.clamp01((t - bar(48.25)) / (CRIT - bar(48.25) + 0.12))) * 0.9;
			bot.free = t >= bar(48.25) && t < CRIT + 0.15;   // the jump
			bot.moveTo(work.add(0, Math.max(0, jump), 0));
		}
		if (at(t, CRIT)) hit(creeper, true);
		creeper.free = t >= CRIT;   // knocked through the air
		if (t >= CRIT && t < POOF) creeper.moveTo(Anim.arc(cStart, kb, Anim.ramp(t, CRIT, POOF - 0.05), 0.4));
		if (at(t, HIT2)) hit(creeper, false);
		if (at(t, POOF)) creeper.poof();
		// the skeleton: an arrow knocks hearts off; the bot sprints at it and puts it down
		if (t < KILL) skeleton.look(bot.eyes());
		if (at(t, ARROW)) {
			bot.hurt();
			health = 14;
			particles(ParticleTypes.DAMAGE_INDICATOR, bot.eyes(), 3, 0.4, 0.1);
		}
		if (t >= CHARGE && t < BACK) bot.follow(charge, t).look(skeleton.eyes());
		if (at(t, STRIKE)) hit(skeleton, false);
		if (at(t, KILL)) {
			hit(skeleton, true);
			skeleton.poof();
		}
		if (t >= BACK) {
			bot.follow(home, t);
			if (t > home.end()) bot.face(-90).look(v(3.5, 1.5, 0.5));
		}
		if (at(t, home == null ? duration : home.end())) bot.hold(Items.IRON_PICKAXE);
	}

	private void hit(Actor target, boolean crit) {
		bot.swing();
		target.hurt();
		particles(crit ? ParticleTypes.CRIT : ParticleTypes.SWEEP_ATTACK, target.eyes(), crit ? 16 : 1, 0.6, 0.3);
	}

	@Override
	void draw(Fx fx, double t) {
		if (t < POOF) fx.ring(creeper.entity.getPosition(fx.partial).add(0, 0.05, 0), 0.7, 0.07, Fx.argb(230, AMBER), Anim.ramp(t, 0, 0.3));
		// the arrow
		Vec3 from = skel.add(0, 1.4, 0), to = work.add(0, 1.3, 0);
		double fly = 0.32;
		if (t >= ARROW - fly && t < ARROW) {
			double u = (t - (ARROW - fly)) / fly;
			fx.item(Anim.arc(from, to, u, 0.4), new ItemStack(Items.ARROW), 0.35, Anim.yawTo(from, to) + 45, false);
		}
		if (t > ARROW - fly && t < KILL) {
			Vec3 s = skeleton.entity.getPosition(fx.partial);
			fx.ring(new Vec3(s.x, origin.getY() + 2.06, s.z), 0.75, 0.07, Fx.argb(230, AMBER), Anim.ramp(t, ARROW - fly, ARROW));
		}
		// fireflies
		for (int i = 0; i < 18; i++) {
			double ph = i * 2.39;
			double glow = Math.max(0, Math.sin(t * 1.3 + ph));
			if (glow <= 0.05) continue;
			Vec3 p = v(-12 + (i * 7.3) % 24, 1.2 + (i % 5) * 0.5 + Math.sin(t * 0.7 + ph) * 0.3, -8 + (i * 5.1) % 20);
			fx.spark(p, 0.18 * glow, Fx.argb((int) (220 * glow), 0xD8FF6A));
		}
	}

	@Override
	void hud(FilmHud hud, double t) {
		hud.hearts(health, Anim.window(t, ARROW - 0.3, ARROW, duration - 0.6, duration));
	}
}
