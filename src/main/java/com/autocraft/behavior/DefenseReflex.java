package com.autocraft.behavior;

import com.autocraft.Bot;
import com.autocraft.combat.Guard;
import com.autocraft.control.InvUtil;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Immediate danger from things that hit from range or explode: back away from lit creepers, punch
 * ghast fireballs back (or sidestep them), and block or dodge arrows and blaze fireballs.
 */
public final class DefenseReflex implements Behavior {
	private static final double CREEPER_PANIC = 5;
	private static final double CREEPER_SAFE = 7;
	private static final double PROJECTILE_RADIUS = 20;
	private static final double HIT_RADIUS = 1.6;
	private static final double HORIZON_TICKS = 30;
	private static final double AIMER_RADIUS = 16;

	private @Nullable Creeper fleeingFrom;
	private int strafeDir = 1;

	@Override
	public String name() {
		return "defense";
	}

	@Override
	public void reset() {
		fleeingFrom = null;
	}

	@Override
	public boolean tick(Bot bot) {
		LocalPlayer p = bot.player();
		if (backOffCreeper(bot, p)) return true;
		Entity incoming = incomingProjectile(bot, p);
		if (incoming != null) return handleProjectile(bot, p, incoming);
		Entity aimer = aimingShooter(bot, p);
		return aimer != null && Guard.raiseShield(bot, aimer.getEyePosition());
	}

	private boolean backOffCreeper(Bot bot, LocalPlayer p) {
		if (fleeingFrom != null && (!fleeingFrom.isAlive() || fleeingFrom.distanceTo(p) > CREEPER_SAFE)) fleeingFrom = null;
		if (fleeingFrom == null) {
			for (Entity e : bot.scanner().entities(CREEPER_PANIC, e -> e instanceof Creeper)) {
				Creeper c = (Creeper) e;
				if (c.getSwellDir() > 0 || c.getSwelling(1.0F) > 0.1F) fleeingFrom = c;
			}
		}
		if (fleeingFrom == null) return false;
		if (!InvUtil.inventoryMenuActive(p)) p.closeContainer();
		if (!Guard.raiseShield(bot, fleeingFrom.position())) bot.controls().lookAt(fleeingFrom.position());
		bot.controls().back();
		if (p.horizontalCollision && p.onGround()) bot.controls().jump();
		bot.setStatus("backing away from a creeper");
		return true;
	}

	/** Arrow / fireball that will pass within {@link #HIT_RADIUS} of us soon, or null. */
	private static @Nullable Entity incomingProjectile(Bot bot, LocalPlayer p) {
		Vec3 me = p.getBoundingBox().getCenter();
		Entity worst = null;
		double soonest = Double.MAX_VALUE;
		for (Entity e : bot.scanner().entities(PROJECTILE_RADIUS, e -> e instanceof AbstractArrow || e instanceof SmallFireball || e instanceof LargeFireball)) {
			Vec3 v = e.getDeltaMovement();
			double speedSq = v.lengthSqr();
			if (speedSq < 0.01) continue; // stuck in the ground
			Vec3 rel = me.subtract(e.position());
			double t = rel.dot(v) / speedSq; // ticks until closest approach
			if (t < 0 || t > HORIZON_TICKS) continue;
			double miss = rel.subtract(v.scale(t)).length();
			if (miss < HIT_RADIUS && t < soonest) {
				soonest = t;
				worst = e;
			}
		}
		return worst;
	}

	private boolean handleProjectile(Bot bot, LocalPlayer p, Entity projectile) {
		bot.setStatus("defending against " + projectile.getType().getDescription().getString());
		if (projectile instanceof LargeFireball && p.distanceTo(projectile) <= p.entityInteractionRange()) {
			bot.controls().attack(projectile); // punching a ghast fireball sends it back
			return true;
		}
		if (Guard.raiseShield(bot, projectile.position())) return true;
		// no shield: sidestep perpendicular to the projectile's path
		Vec3 v = projectile.getDeltaMovement();
		float yaw = (float) (Mth.atan2(-v.z, -v.x) * Mth.RAD_TO_DEG) - 90.0F; // face the shooter
		bot.controls().face(yaw, 0);
		if (p.horizontalCollision) strafeDir = -strafeDir;
		if (strafeDir > 0) bot.controls().left();
		else bot.controls().right();
		bot.controls().sprint();
		return true;
	}

	/** A skeleton drawing its bow or a pillager loading its crossbow at us, with line of sight. */
	private static @Nullable Entity aimingShooter(Bot bot, LocalPlayer p) {
		if (!Guard.hasShield(p)) return null;
		for (Entity e : bot.scanner().entities(AIMER_RADIUS, e -> e instanceof AbstractSkeleton || e instanceof Pillager)) {
			boolean drawing = e instanceof AbstractSkeleton s ? s.isAggressive() && s.isUsingItem() : ((Pillager) e).isChargingCrossbow();
			if (drawing && p.hasLineOfSight(e)) return e;
		}
		return null;
	}
}
