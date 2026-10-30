package com.autocraft.behavior;

import com.autocraft.Bot;
import com.autocraft.combat.Guard;
import com.autocraft.combat.Threats;
import com.autocraft.control.InvUtil;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.zombie.Drowned;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Immediate danger from things that hit from range or explode: back away from lit creepers — or, with other mobs
 * crowding around one, hold just outside its deadly radius and let it blow them up — punch ghast fireballs back
 * (or sidestep them), and block or dodge arrows and blaze fireballs.
 */
public final class DefenseReflex implements Behavior {
	private static final double CREEPER_PANIC = 5;
	/** A lit creeper calms down once we're this far; it blows up if we stay within. */
	private static final double CREEPER_SAFE = 7;
	/**
	 * Using a creeper on a crowd: from here its blast barely scratches us (it fades out at 6 blocks) while it keeps
	 * its fuse lit (that takes us within 7); mobs within a few blocks of it take 10-25 damage — most die.
	 */
	private static final double BAIT_MIN = 5.6;
	private static final double BAIT_MAX = 6.6;
	/** Other hostile mobs this close to a lit creeper are worth its blast. */
	private static final double BAIT_CROWD = 4;
	private static final double PROJECTILE_RADIUS = 20;
	private static final double HIT_RADIUS = 1.6;
	private static final double HORIZON_TICKS = 30;
	/** Shooters this far off are watched (ghasts and blazes shoot from well beyond a skeleton's range). */
	private static final double AIMER_RADIUS = 32;
	/**
	 * The shield stays up this long after the last sign of a shot. A raised shield blocks only after 5 ticks, and the
	 * moment the shooter fires is the moment it stops aiming: lowering then and raising again for the arrow in the
	 * air restarted those 5 ticks, and every arrow (crossbow bolts most of all) landed through the "raised" shield.
	 */
	private static final int SHIELD_HOLD_TICKS = 20;

	private @Nullable Creeper fleeingFrom;
	private int strafeDir = 1;
	private int ticks;
	private int holdUntil;
	private @Nullable Entity holdToward;

	@Override
	public String name() {
		return "defense";
	}

	@Override
	public void reset() {
		fleeingFrom = null;
		holdToward = null;
		holdUntil = 0;
	}

	@Override
	public boolean tick(Bot bot) {
		LocalPlayer p = bot.player();
		ticks++;
		if (backOffCreeper(bot, p)) return true;
		// a shooter in sword reach (a blaze hovering at our side) is a melee fight, combat's: behind the shield, two
		// blazes at arm's length burned the bot from 19 to 0 in fifteen seconds without it swinging once
		Entity incoming = incomingProjectile(bot, p);
		if (incoming != null && !inReach(p, shooter(incoming))) {
			if (!(incoming instanceof LargeFireball)) hold(incoming);
			return handleProjectile(bot, p, incoming);
		}
		Entity aimer = aimingShooter(bot, p);
		if (aimer != null && !inReach(p, aimer)) hold(aimer);
		if (holdToward != null && ticks < holdUntil && holdToward.isAlive() && meleeAtHand(bot, p)) {
			// a shield covers one side: held toward a crossbow piglin five blocks off, a hoglin at the bot's back took it
			// from 20 to 12, then a lone zombie down to 6. One at hand is combat's: it fights it (shield up toward it
			// between swings) or gets away
			holdToward = null;
			return false;
		}
		if (holdToward != null && ticks < holdUntil && holdToward.isAlive()) {
			// one we can hit, standing on the ground (not a ghast, not a blaze in the air): blocking alone was a stalemate —
			// a crossbow piglin reloaded every two seconds and the bot stood behind its shield until something else came.
			// Its reload is the opening, in reach or not: a crossbow piglin backs off from anything within 5 blocks, and
			// creeping after it behind a raised shield (a fifth of walking speed) the bot went 80 s without landing a hit.
			// Combat closes in at full speed and swings; the shield goes back up the moment it's loaded again
			boolean hittable = holdToward instanceof Mob m && m.onGround() && !Threats.isUnreachableShooter(m);
			if (hittable && !aboutToShoot((Mob) holdToward)) return false;
			if (Guard.raiseShield(bot, holdToward.getEyePosition())) {
				Vec3 to = holdToward.position().subtract(p.position());
				if (hittable && p.distanceTo(holdToward) > 2.5 && CombatBehavior.safeStep(p, to.x, to.z)) bot.controls().forward(); // close in behind it
				bot.setStatus("shield up against " + holdToward.getType().getDescription().getString());
				return true;
			}
		}
		holdToward = null;
		return false;
	}

	/** Keep the shield on {@code toward} (the shooter, or its projectile) for {@link #SHIELD_HOLD_TICKS} more. */
	private void hold(Entity toward) {
		holdToward = shooter(toward);
		holdUntil = ticks + SHIELD_HOLD_TICKS;
	}

	/** Who fired {@code e} (itself when it isn't a projectile, or its owner is unknown). */
	private static Entity shooter(Entity e) {
		return e instanceof net.minecraft.world.entity.projectile.Projectile proj && proj.getOwner() != null ? proj.getOwner() : e;
	}

	/** Close enough to strike. */
	static boolean inReach(LocalPlayer p, Entity e) {
		return p.isWithinEntityInteractionRange(e, 0.5);
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
		Creeper creeper = fleeingFrom;
		// hunting it (the job wants gunpowder): a ready swing in reach goes first — combat strikes, the knockback buys
		// time, and from the next tick (weapon recharging) we back off; otherwise it never takes a hit at all
		if (bot.huntTargets().contains(creeper.getType()) && p.getHealth() > 10 && p.getAttackStrengthScale(0.5F) >= 0.95F
				&& p.isWithinEntityInteractionRange(creeper, 0) && creeper.getSwelling(1.0F) < 0.6F) {
			return false;
		}
		if (!InvUtil.inventoryMenuActive(p)) p.closeContainer();
		if (!Guard.raiseShield(bot, fleeingFrom.position())) bot.controls().lookAt(fleeingFrom.position());
		int crowd = bot.scanner().entities(CREEPER_SAFE + BAIT_CROWD, e -> e instanceof Mob m && e != creeper && m.isAlive()
				&& Threats.isThreat(p, m) && !(m instanceof Creeper) && m.distanceTo(creeper) < BAIT_CROWD).size();
		double d = creeper.distanceTo(p);
		if (crowd > 0 && creeper.getSwelling(1.0F) > 0) {
			// hold at the edge: its fuse stays lit, its blast catches the mobs around it rather than us
			if (d < BAIT_MIN) bot.controls().back();
			else if (d > BAIT_MAX) bot.controls().forward();
			bot.setStatus("letting a creeper blow up " + crowd + " mob" + (crowd == 1 ? "" : "s") + " around it");
		} else {
			bot.controls().back();
			bot.setStatus("backing away from a creeper");
		}
		if (p.horizontalCollision && p.onGround()) bot.controls().jump();
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
		if (CombatBehavior.onPillar(p)) bot.controls().sneak(); // dodge on the spot rather than off the pillar
		else bot.controls().sprint();
		return true;
	}

	/**
	 * A shooter about to fire at us, in sight: drawing a bow, charging or holding a loaded crossbow (pillagers,
	 * piglins), a blaze lit up for its burst, a ghast opening its mouth, a drowned raising its trident.
	 */
	private static @Nullable Entity aimingShooter(Bot bot, LocalPlayer p) {
		if (!Guard.hasShield(p)) return null;
		for (Entity e : bot.scanner().entities(AIMER_RADIUS, e -> e instanceof Mob m && aboutToShoot(m))) {
			if (p.hasLineOfSight(e)) return e;
		}
		return null;
	}

	private static boolean aboutToShoot(Mob m) {
		if (m instanceof Ghast g) return g.isCharging();
		if (m instanceof Blaze) return m.isOnFire(); // a blaze lights up before each burst of fireballs
		if (m instanceof Drowned) return m.isUsingItem() && m.getMainHandItem().is(Items.TRIDENT);
		if (m instanceof Witch || !Threats.isRanged(m)) return false; // a shield doesn't stop splash potions
		return Threats.drawing(m);
	}

	/**
	 * A mob close enough to hit us within a second — a blaze at our side burns us in melee too: holding the shield
	 * toward a shooter leaves it our open side.
	 */
	private static boolean meleeAtHand(Bot bot, LocalPlayer p) {
		return Threats.collect(bot, 4).stream().anyMatch(m -> !Threats.isRanged(m) ? m.distanceTo(p) < 3.5 : m instanceof Blaze && inReach(p, m));
	}
}
