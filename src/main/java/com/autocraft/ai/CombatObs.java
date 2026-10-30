package com.autocraft.ai;

import com.autocraft.Bot;
import com.autocraft.combat.Guard;
import com.autocraft.combat.Threats;
import com.autocraft.path.Navigator;
import com.autocraft.world.BlockUtil;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.skeleton.WitherSkeleton;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * What {@link CombatPolicy} sees: the same 70 numbers, in the same order and scale, as Sim.observe in
 * tools/combatsim/combatsim.py (keep the two in step). Directions are in the frame of facing the target:
 * "forward" toward it, "right" 90 degrees clockwise seen from above (east-facing: right is south, +z).
 */
public final class CombatObs {
	public static final int SIZE = 9 + 4 * 13 + 4 + 4 + 1;
	private static final int NEAREST = 4;
	private static final double SWORD_TICKS = 12.5;
	private static final int SHIELD_DELAY = 5;
	private static final double ROOM = 8;

	private CombatObs() {}

	public static double[] of(Bot bot, LocalPlayer p, Mob target, List<Mob> threats) {
		double[] o = new double[SIZE];
		double tx = target.getX() - p.getX(), tz = target.getZ() - p.getZ();
		double tn = Math.max(1e-6, Math.sqrt(tx * tx + tz * tz));
		double fx = tx / tn, fz = tz / tn;
		Vec3 pv = p.getDeltaMovement();
		int i = 0;
		// self
		o[i++] = p.getHealth() / 20.0;
		o[i++] = p.getAttackStrengthScale(0.5F);
		boolean shieldUp = p.isUsingItem() && Guard.hasShield(p) && p.getUsedItemHand() == net.minecraft.world.InteractionHand.OFF_HAND;
		int raised = shieldUp ? p.getTicksUsingItem() : 0;
		o[i++] = shieldUp && raised >= SHIELD_DELAY ? 1 : 0;
		o[i++] = Math.min(raised, SHIELD_DELAY) / (double) SHIELD_DELAY;
		o[i++] = p.onGround() ? 1 : 0;
		o[i++] = p.onGround() ? 0 : pv.y * 2; // the sim holds vy at 0 on the ground; the game keeps gravity's -0.08 there
		o[i++] = Guard.hasShield(p) ? 1 : 0;
		o[i++] = p.getArmorValue() / 20.0;
		o[i++] = Features.bestWeaponDamage(p) / 10.0;
		// nearest mobs
		List<Mob> near = new ArrayList<>(threats);
		near.removeIf(m -> !m.isAlive());
		near.sort(Comparator.comparingDouble(m -> horizontalSq(m, p)));
		for (int k = 0; k < NEAREST; k++) {
			if (k >= near.size()) {
				i += 13;
				continue;
			}
			Mob m = near.get(k);
			double rx = m.getX() - p.getX(), rz = m.getZ() - p.getZ();
			double rvx = (m.getX() - m.xo) - pv.x, rvz = (m.getZ() - m.zo) - pv.z;
			int kind = kind(m);
			o[i++] = 1;
			o[i++] = kind == 1 ? 1 : 0;
			o[i++] = kind == 2 ? 1 : 0;
			o[i++] = kind == 3 ? 1 : 0;
			o[i++] = kind == 4 ? 1 : 0;
			o[i++] = (rx * fx + rz * fz) / 16;
			o[i++] = (-rx * fz + rz * fx) / 16;
			o[i++] = Math.sqrt(rx * rx + rz * rz) / 16;
			o[i++] = (rvx * fx + rvz * fz) * 5;
			o[i++] = (-rvx * fz + rvz * fx) * 5;
			o[i++] = m.getHealth() / 20.0;
			o[i++] = m instanceof Creeper c ? c.getSwelling(1.0F) : kind == 2 && Threats.drawing(m) ? 1 : 0;
			o[i++] = m.hurtTime > 0 ? 1 : 0; // the client's 10-tick hurt animation = the server's half of the hurt timer
		}
		// nearest arrow coming at us
		AbstractArrow arrow = null;
		double best = Double.MAX_VALUE;
		for (Entity e : bot.scanner().entities(16, e -> e instanceof AbstractArrow a && !a.onGround())) {
			Vec3 v = e.getDeltaMovement();
			double ax = e.getX() - p.getX(), az = e.getZ() - p.getZ();
			if (ax * v.x + az * v.z >= 0) continue; // moving away
			double d = ax * ax + az * az;
			if (d < best) {
				best = d;
				arrow = (AbstractArrow) e;
			}
		}
		if (arrow != null) {
			double ax = arrow.getX() - p.getX(), az = arrow.getZ() - p.getZ();
			Vec3 v = arrow.getDeltaMovement();
			double speed = Math.max(1e-6, Math.sqrt(v.x * v.x + v.z * v.z));
			o[i++] = 1;
			o[i++] = (ax * fx + az * fz) / 16;
			o[i++] = (-ax * fz + az * fx) / 16;
			o[i++] = Math.min(1, Math.sqrt(best) / speed / 20);
		} else {
			i += 4;
		}
		// room to move: forward, back, left, right
		Level level = p.level();
		o[i++] = room(level, p, fx, fz);
		o[i++] = room(level, p, -fx, -fz);
		o[i++] = room(level, p, fz, -fx);
		o[i++] = room(level, p, -fz, fx);
		o[i++] = Math.min(near.size(), 6) / 6.0;
		return o;
	}

	/** 1 zombie-like (melee), 2 archer, 3 creeper, 4 spider — the sim's mob kinds. */
	private static int kind(Mob m) {
		if (m instanceof Creeper) return 3;
		if (m instanceof Spider) return 4;
		if (Threats.isRanged(m)) return 2;
		return 1;
	}

	/**
	 * A mob the simulator fights (seen as its kind plus its health): zombies and their kin, skeletons, creepers,
	 * spiders, hoglins, piglins. Not a piglin brute (50 health, an axe that disables the shield) or a wither skeleton:
	 * the trained moves had never met one, and a brute fought with them killed the bot in a bastion.
	 */
	public static boolean simulated(Mob m) {
		if (m instanceof WitherSkeleton) return false;
		return m instanceof Zombie || m instanceof AbstractSkeleton || m instanceof Creeper || m instanceof Spider || m instanceof Hoglin
				|| m instanceof Piglin; // a brute is no Piglin (both are AbstractPiglins)
	}

	private static double horizontalSq(Entity a, Entity b) {
		double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
		return dx * dx + dz * dz;
	}

	/** Walkable ground along (ux, uz): body room at feet and head, something to stand on (a step down is fine). */
	private static double room(Level level, LocalPlayer p, double ux, double uz) {
		BlockPos feet = Navigator.feet(p);
		for (double d = 0.5; d <= ROOM; d += 0.5) {
			BlockPos at = BlockPos.containing(p.getX() + ux * d, feet.getY(), p.getZ() + uz * d);
			boolean body = BlockUtil.isPassable(level, at, level.getBlockState(at)) && BlockUtil.isPassable(level, at.above(), level.getBlockState(at.above()));
			BlockPos floor = at.below(), floor2 = at.below(2);
			boolean ground = BlockUtil.canStandOn(level, floor, level.getBlockState(floor))
					|| BlockUtil.isPassable(level, floor, level.getBlockState(floor)) && BlockUtil.canStandOn(level, floor2, level.getBlockState(floor2));
			if (!body || !ground) return Math.max(0, d - 0.5) / ROOM;
		}
		return 1;
	}
}
