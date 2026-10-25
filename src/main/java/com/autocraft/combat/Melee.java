package com.autocraft.combat;

import com.autocraft.Bot;
import com.autocraft.control.Controls;
import com.autocraft.control.InvUtil;
import com.autocraft.path.Goal;
import com.autocraft.path.Navigator;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;

/**
 * Hitting things properly: best weapon in hand, swing only at full attack strength (no spam), jump crits
 * (1.5x damage) whenever the bot can jump, never sprinting into the hit (that's knockback, not a crit).
 */
public final class Melee {
	private Melee() {}

	/** Swing at {@code target} if it's in reach and visible. False when it isn't (the caller closes in). */
	public static boolean strike(Bot bot, LivingEntity target) {
		LocalPlayer p = bot.player();
		if (!p.isWithinEntityInteractionRange(target, 0) || !p.hasLineOfSight(target)) return false;
		Controls c = bot.controls();
		selectWeapon(p);
		bot.nav().stop();
		c.lookAt(target.getBoundingBox().getCenter());
		p.setSprinting(false); // sprint hits are knockback, not crits
		if (p.getAttackStrengthScale(0.5F) < 0.95F) return true; // wait for a full-strength swing
		boolean crit = canCrit(p) && !(target instanceof Creeper);
		if (crit && p.onGround()) {
			c.jump(); // hit on the way down
			return true;
		}
		if (crit && !(p.fallDistance > 0 && p.getDeltaMovement().y < 0)) return true; // still rising
		c.attack(target);
		return true;
	}

	/** Close in: sprint straight at a visible target on our level, otherwise path to it. False when unreachable. */
	public static boolean approach(Bot bot, LivingEntity target, BlockPos[] chaseAt) {
		LocalPlayer p = bot.player();
		Controls c = bot.controls();
		if (p.hasLineOfSight(target) && Math.abs(target.getY() - p.getY()) < 1.5 && p.distanceTo(target) < 12) {
			bot.nav().stop();
			c.faceHorizontally(target.position());
			c.forward();
			c.sprint();
			if (p.horizontalCollision && p.onGround()) c.jump();
			return true;
		}
		BlockPos at = target.blockPosition();
		if (chaseAt[0] == null || chaseAt[0].distSqr(at) > 4) chaseAt[0] = at;
		bot.nav().setGoal(new Goal.Near(chaseAt[0], 1));
		if (bot.nav().tick() == Navigator.Status.FAILED) {
			bot.nav().stop();
			return false;
		}
		return true;
	}

	public static boolean canCrit(LocalPlayer p) {
		return !p.isInWater() && !p.onClimbable() && !p.isPassenger();
	}

	/** Best melee weapon into the main hand. */
	public static void selectWeapon(LocalPlayer p) {
		int best = -1;
		double bestDamage = 1;
		for (int i = 0; i < InvUtil.MAIN_SIZE; i++) {
			double d = Threats.attackDamage(p.getInventory().getItem(i));
			if (d > bestDamage && !InvUtil.nearlyBroken(p.getInventory().getItem(i))) {
				bestDamage = d;
				best = i;
			}
		}
		if (best >= 0) {
			int slot = best;
			InvUtil.select(p, s -> s == p.getInventory().getItem(slot));
		}
	}
}
