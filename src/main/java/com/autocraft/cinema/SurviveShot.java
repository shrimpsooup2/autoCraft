package com.autocraft.cinema;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Surviving the night. A zombie in a glass pen can't reach the bot (its reach stops at the glass) so it's left
 * alone. A creeper closes in: a jump, a critical hit, a second, poof. A skeleton's arrows knock hearts off; the
 * bot (light amber) runs out of its range, eats while its hearts come back — the skeleton stops at the edge of
 * its range — then turns back (light green), beats it, and goes back to mining.
 */
final class SurviveShot extends Shot {
	private static final int CYAN = 0x39D0FF, AMBER = 0xFFBF3C, GREEN = 0x5EE36A;
	private static final double RANGE = 10;

	private Actor bot, zombie, creeper, skeleton;
	private final Vec3[] mine = new Vec3[1];
	private Anim.Track zombieWalk, creeperWalk, flee, back, charge, skeletonWalk, home;
	private Vec3 eat, skelHome, skelStop, cStop, kb;
	private int health = 20;

	SurviveShot() {
		super("Survive", "Survive", 24, 18000);
	}

	@Override
	BlockPos offset() {
		return new BlockPos(0, 0, -70);
	}

	@Override
	void build(ServerLevel level, BlockPos at) {
		int y = Stage.pad(level, at.getX(), at.getZ(), 16);
		origin = new BlockPos(at.getX(), y + 1, at.getZ());
		// the stone outcrop being mined
		Stage.fill(level, origin.getX() + 3, y + 1, origin.getZ() - 1, origin.getX() + 4, y + 2, origin.getZ() + 1, Blocks.STONE.defaultBlockState());
		Stage.set(level, origin.getX() + 4, y + 3, origin.getZ(), Blocks.STONE.defaultBlockState());
		Stage.set(level, origin.getX() + 4, y + 2, origin.getZ() + 1, Blocks.COAL_ORE.defaultBlockState());
		// the glass pen
		for (int x = -8; x <= -3; x++) {
			for (int h = 1; h <= 3; h++) {
				Stage.set(level, origin.getX() + x, y + h, origin.getZ() + 4, Blocks.GLASS.defaultBlockState());
				Stage.set(level, origin.getX() + x, y + h, origin.getZ() + 8, Blocks.GLASS.defaultBlockState());
			}
		}
		for (int z = 5; z <= 7; z++) {
			for (int h = 1; h <= 3; h++) {
				Stage.set(level, origin.getX() - 8, y + h, origin.getZ() + z, Blocks.GLASS.defaultBlockState());
				Stage.set(level, origin.getX() - 3, y + h, origin.getZ() + z, Blocks.GLASS.defaultBlockState());
			}
		}
		// the skeleton's mound
		for (int x = -3; x <= 3; x++) {
			for (int z = -3; z <= 3; z++) {
				double d = Math.sqrt(x * x + z * z);
				int h = d < 1.8 ? 2 : d < 3.3 ? 1 : 0;
				for (int k = 1; k <= h; k++) Stage.set(level, origin.getX() + 8 + x, y + k, origin.getZ() + 7 + z, Blocks.GRASS_BLOCK.defaultBlockState());
			}
		}
		Stage.set(level, origin.getX(), y + 1, origin.getZ() + 2, Blocks.TORCH.defaultBlockState());
		Stage.set(level, origin.getX() - 2, y + 1, origin.getZ() + 3, Blocks.TORCH.defaultBlockState());
		Stage.set(level, origin.getX() - 2, y + 2, origin.getZ() + 3, Blocks.AIR.defaultBlockState());
		Stage.set(level, origin.getX() + 3, y + 2, origin.getZ() - 2, Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, Direction.NORTH));
		Sets.oak(level, origin.getX() - 13, y + 1, origin.getZ() + 12, 6);
		Sets.oak(level, origin.getX() + 1, y + 1, origin.getZ() + 14, 5);
		Sets.oak(level, origin.getX() + 13, y + 1, origin.getZ() - 6, 5);
	}

	@Override
	void start() {
		Vec3 work = v(2.5, 0, 0.5);
		mine[0] = work;
		bot = add(Actor.me(level, work, -90));
		bot.light = GREEN;
		bot.hold(Items.IRON_PICKAXE);
		zombie = add(Actor.zombie(level, v(-6.5, 0, 6.0), -90));
		zombieWalk = new Anim.Track().at(0, v(-6.5, 0, 6.0));
		for (int i = 0; i < 8; i++) zombieWalk.to(i % 2 == 0 ? v(-3.8, 0, 6.3) : v(-6.8, 0, 5.9), 1.4).hold(zombieWalk.end() + 0.5);
		cStop = v(3.6, 0, -1.6);
		creeper = add(Actor.creeper(level, v(10.5, 0, -5.5), 0));
		creeperWalk = new Anim.Track().at(4.4, v(10.5, 0, -5.5)).to(cStop, 2.6);
		kb = v(5.4, 0, -3.4);
		skelHome = v(8.5, 2, 7.5);
		skelStop = v(4.5, 0, 3.5);
		skeleton = add(Actor.skeleton(level, skelHome, 0));
		eat = v(-5.5, 0, -3.5);
		flee = new Anim.Track().at(13.4, work).to(v(0, 0, -1.5), 6.5).to(eat, 6.5);
		skeletonWalk = new Anim.Track().at(14.6, skelHome).to(v(7.0, 1, 6.0), 1.6).to(v(5.6, 0, 4.8), 1.6).to(skelStop, 1.6);
		Vec3 chargeTo = skelStop.add(eat.subtract(skelStop).normalize().scale(1.3));
		charge = new Anim.Track().at(18.8, eat).to(chargeTo, 7.0);
		home = new Anim.Track().at(21.2, chargeTo).to(work, 4.3);
		cam.key(0, v(-5, 7.5, -15), v(-1.5, 1.6, 3), 62)
				.key(3.8, v(-3.5, 6.5, -14), v(-0.5, 1.6, 2.5))
				.key(6.6, v(-3.4, 2.9, -7.4), v(3.0, 2.0, -0.8), 58)
				.key(9.2, v(-3.0, 2.8, -6.8), v(3.3, 1.9, -1.2))
				.key(11.0, v(-3, 10, -19), v(3.5, 1.5, 3), 62)
				.key(13.4, v(-2.5, 9.5, -18), v(3, 1.5, 2.5))
				.key(15.6, v(-0.8, 2.7, -5.6), v(-5.0, 1.7, -3.3), 56)
				.key(18.6, v(-1.0, 2.6, -5.2), v(-5.0, 1.7, -3.4))
				.key(20.4, v(-2, 5, -9), v(3.0, 1.5, 1.5), 60)
				.key(duration, v(6.5, 4.5, -8.5), v(2.5, 1.0, 1.0));
	}

	@Override
	void tick(double t) {
		// the zombie paces its pen, arms out; noticed, found unreachable, ignored
		zombie.follow(zombieWalk, t);
		zombie.aggressive(true);
		// mining while nothing needs doing
		boolean mining = t < 6.8 || (t > 9.4 && t < 13.3) || t > 22.4;
		if (mining) {
			bot.moveTo(mine[0]).face(-90).look(v(3.5, 1.5, 0.5));
			if (every(t, 0, 24, 0.32)) bot.swing();
			BlockPos rock = b(3, 1, 0);
			if (t < 2.4) crack(rock, (int) (t / 2.4 * 10));
			if (at(t, 2.4)) dig(rock);
		}
		// the creeper
		creeper.follow(creeperWalk, t);
		if (t > creeperWalk.end()) creeper.look(bot.eyes());
		if (at(t, 6.0)) creeper.fuse(1);
		if (at(t, 7.9)) creeper.fuse(-1);
		if (at(t, 6.8)) {
			bot.hold(Items.IRON_SWORD);
			bot.look(cStop.add(0, 1.2, 0));
		}
		if (t >= 6.8 && t < 9.4) {
			double jump = Math.sin(Math.PI * Anim.clamp01((t - 7.2) / 0.9)) * 1.0;
			Vec3 p = Anim.lerp(mine[0], v(2.9, 0, -0.4), Anim.ramp(t, 7.2, 8.1));
			bot.moveTo(p.add(0, Math.max(0, jump), 0)).look(creeper.eyes());
		}
		if (at(t, 7.85)) hit(creeper, true);
		if (t >= 7.9 && t < 8.6) creeper.moveTo(Anim.arc(cStop, kb, Anim.ramp(t, 7.9, 8.4), 0.5));
		if (at(t, 8.55)) hit(creeper, false);
		if (at(t, 8.7)) creeper.poof();
		if (at(t, 9.4)) bot.hold(Items.IRON_PICKAXE);
		// the skeleton: shoots three times, hearts drop
		skeleton.aggressive(t > 9.6 && t < 20.6);
		if (t < 14.6) skeleton.moveTo(skelHome).look(bot.eyes());
		else if (t < 20.6) skeleton.follow(skeletonWalk, t).look(bot.eyes());
		double[] hits = {10.6, 11.7, 12.8};
		for (int i = 0; i < hits.length; i++) {
			if (at(t, hits[i])) {
				bot.hurt();
				health -= 4;
				particles(ParticleTypes.DAMAGE_INDICATOR, bot.eyes(), 3, 0.4, 0.1);
			}
		}
		// recover: run out of range, eat, hearts come back
		if (at(t, 13.2)) bot.light = AMBER;
		if (t >= 13.4 && t < 18.8) {
			bot.follow(flee, t);
			if (t > flee.end()) bot.look(skeleton.eyes());
		}
		if (at(t, 15.2)) bot.hold(Items.BREAD);
		if (t > 15.4 && t < 18.4) {
			if (every(t, 15.4, 18.4, 0.24)) {
				bot.swing();
				particles(new ItemParticleOption(ParticleTypes.ITEM, Items.BREAD), bot.eyes().add(0, -0.2, 0), 4, 0.3, 0.08);
			}
			health = Math.min(20, 8 + (int) ((t - 15.4) / 3.0 * 12));
		}
		if (at(t, 18.5)) {
			bot.light = GREEN;
			bot.hold(Items.IRON_SWORD);
		}
		// beat the skeleton, back to work
		if (t >= 18.8 && t < 21.2) bot.follow(charge, t).look(skeleton.eyes());
		if (at(t, 19.9)) hit(skeleton, false);
		if (at(t, 20.5)) {
			hit(skeleton, true);
			skeleton.poof();
		}
		if (t >= 21.2 && t <= 22.4) bot.follow(home, t);
		if (at(t, 22.4)) bot.hold(Items.IRON_PICKAXE);
	}

	private void hit(Actor target, boolean crit) {
		bot.swing();
		target.hurt();
		particles(crit ? ParticleTypes.CRIT : ParticleTypes.SWEEP_ATTACK, target.eyes(), crit ? 14 : 1, 0.6, 0.3);
	}

	@Override
	void draw(Fx fx, double t) {
		// the zombie reaches for the bot; the glass stops it; it's ignored
		Vec3 z = zombie.entity.getPosition(fx.partial).add(0, 1.6, 0);
		Vec3 glass = v(-2.9, 1.6, 4.0);
		double reach = Anim.window(t, 1.0, 1.6, 3.6, 4.2);
		if (reach > 0) {
			fx.ribbon(List.of(z, z.add(glass.subtract(z).scale(0.5)).add(0, 0.25, 0), glass), 0.05, Fx.argb((int) (230 * reach), CYAN), Anim.ramp(t, 1.0, 1.6));
			if (t > 1.7) {
				double x = Anim.pop(t, 1.7, 0.3) * 0.25;
				fx.ribbon(List.of(glass.add(-x, -x, -0.05), glass.add(x, x, -0.05)), 0.05, Fx.argb((int) (255 * reach), 0xFFFFFF), 1);
				fx.ribbon(List.of(glass.add(-x, x, -0.05), glass.add(x, -x, -0.05)), 0.05, Fx.argb((int) (255 * reach), 0xFFFFFF), 1);
			}
		}
		if (t > 3.0) {
			Vec3 zp = zombie.entity.getPosition(fx.partial);
			fx.glassBox(new AABB(zp.add(-0.45, 0, -0.45), zp.add(0.45, 2.05, 0.45)), Fx.argb((int) (90 * Anim.ramp(t, 3.0, 3.8)), 0x9AA0A8));
			fx.ring(zp.add(0, 0.05, 0), 0.75, 0.05, Fx.argb(150, 0x8A8F96), 1);
		} else if (t > 0.6) {
			fx.ring(zombie.entity.getPosition(fx.partial).add(0, 0.05, 0), 0.75, 0.06, Fx.argb(220, AMBER), Anim.ramp(t, 0.6, 1.0));
		}
		// threat rings
		if (t > 5.0 && t < 8.7) fx.ring(creeper.entity.getPosition(fx.partial).add(0, 0.05, 0), 0.7, 0.07, Fx.argb(230, AMBER), Anim.ramp(t, 5.0, 5.4));
		if (t > 9.8 && t < 20.6) {
			Vec3 s = skeleton.entity.getPosition(fx.partial);
			fx.ring(new Vec3(s.x, origin.getY() + 0.06, s.z), RANGE, 0.1, Fx.argb((int) (200 * (1 - Anim.ramp(t, 20.4, 20.6))), AMBER), Anim.ramp(t, 9.8, 10.6));
		}
		// arrows
		double[] hits = {10.6, 11.7, 12.8};
		for (double h : hits) {
			Vec3 from = skelHome.add(0, 1.4, 0), to = mine[0].add(0, 1.3, 0);
			if (t >= h - 0.55 && t < h) {
				double u = (t - (h - 0.55)) / 0.55;
				Vec3 p = Anim.arc(from, to, u, 0.6);
				fx.item(p, new ItemStack(Items.ARROW), 0.35, Anim.yawTo(from, to) + 45, false);
			}
		}
		if (t >= 14.0) {   // the one that misses lands at the edge of the range
			Vec3 from = skelHome.add(0, 1.4, 0), miss = skelHome.add(eat.subtract(skelHome).normalize().scale(RANGE - 0.4)).add(0, 0.2 - 2, 0);
			double u = Anim.clamp01((t - 14.0) / 0.6);
			fx.item(Anim.arc(from, miss, u, 1.2), new ItemStack(Items.ARROW), 0.35, Anim.yawTo(from, miss) + 45, false);
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
		hud.hearts(health, Anim.window(t, 9.4, 9.9, 22.6, 23.4));
	}
}
