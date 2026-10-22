package com.autocraft.ai;

import com.autocraft.Bot;
import com.autocraft.combat.Guard;
import com.autocraft.combat.Threats;
import com.autocraft.control.InvUtil;
import com.autocraft.goal.Requirement;
import java.util.List;
import java.util.Random;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.item.ItemStack;

/**
 * Turns a combat situation into the ThreatNet input vector (all roughly 0..1), and provides the
 * hand-written teacher used to bootstrap the network.
 */
public final class Features {
	public static final int COUNT = 12;

	private static final int HEALTH = 0, ARMOR = 1, WEAPON = 2, SHIELD = 3, FOOD = 4, COUNT_MOBS = 5, NEAREST = 6,
			DANGER = 7, CREEPER = 8, RANGED = 9, FLYING_SHOOTER = 10, DARK = 11;

	private Features() {}

	public static double[] of(Bot bot, List<Mob> threats) {
		LocalPlayer p = bot.player();
		double[] x = new double[COUNT];
		x[HEALTH] = p.getHealth() / p.getMaxHealth();
		x[ARMOR] = p.getArmorValue() / 20.0;
		x[WEAPON] = Math.min(1, bestWeaponDamage(p) / 10.0);
		x[SHIELD] = Guard.hasShield(p) ? 1 : 0;
		x[FOOD] = p.getFoodData().getFoodLevel() / 20.0;
		x[COUNT_MOBS] = Math.min(1, threats.size() / 6.0);
		double danger = 0;
		int ranged = 0;
		for (Mob m : threats) {
			double d = m.distanceTo(p);
			danger += Threats.danger(m) * (d < 8 ? 1 : 0.5);
			if (Threats.isRanged(m)) ranged++;
			if (m instanceof Creeper && d < 6) x[CREEPER] = 1;
			if (m instanceof Ghast || m instanceof Blaze) x[FLYING_SHOOTER] = 1;
		}
		x[NEAREST] = threats.isEmpty() ? 1 : Math.min(1, threats.getFirst().distanceTo(p) / 16.0);
		x[DANGER] = Math.min(1.5, danger / 10.0);
		x[RANGED] = Math.min(1, ranged / 4.0);
		x[DARK] = bot.dimension() == Requirement.Dim.NETHER || !p.level().isBrightOutside() ? 1 : 0;
		return x;
	}

	public static double bestWeaponDamage(LocalPlayer p) {
		double best = 1;
		for (int i = 0; i < InvUtil.MAIN_SIZE; i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (!s.isEmpty()) best = Math.max(best, Threats.attackDamage(s));
		}
		return best;
	}

	/** Hand-tuned judgement: our offence/defence versus the combined threat. */
	static double teacher(double[] x) {
		double strength = x[HEALTH] * 1.0 + x[ARMOR] * 0.9 + x[WEAPON] * 1.2 + x[SHIELD] * 0.25 + x[FOOD] * 0.1;
		double threat = x[DANGER] * 1.8 + x[COUNT_MOBS] * 0.6 + x[CREEPER] * 0.5 + x[RANGED] * 0.4
				+ x[FLYING_SHOOTER] * 0.8 + x[DARK] * 0.1 - x[NEAREST] * 0.2;
		return 1 / (1 + Math.exp(-4 * (strength - threat)));
	}

	/** Plausible random situation for bootstrapping. */
	static double[] randomSituation(Random r) {
		double[] x = new double[COUNT];
		int mobs = 1 + r.nextInt(6);
		x[HEALTH] = 0.1 + r.nextDouble() * 0.9;
		x[ARMOR] = r.nextDouble() < 0.3 ? 0 : r.nextDouble() * 0.8;
		x[WEAPON] = 0.1 + r.nextDouble() * 0.7;
		x[SHIELD] = r.nextBoolean() ? 1 : 0;
		x[FOOD] = r.nextDouble();
		x[COUNT_MOBS] = mobs / 6.0;
		x[NEAREST] = r.nextDouble();
		x[DANGER] = Math.min(1.5, mobs * (0.8 + r.nextDouble() * 2.2) / 10.0);
		x[CREEPER] = r.nextDouble() < 0.25 ? 1 : 0;
		x[RANGED] = Math.min(1, r.nextInt(mobs + 1) / 4.0);
		x[FLYING_SHOOTER] = r.nextDouble() < 0.15 ? 1 : 0;
		x[DARK] = r.nextBoolean() ? 1 : 0;
		return x;
	}
}
