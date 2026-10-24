package com.autocraft.behavior;

import com.autocraft.Bot;
import com.autocraft.ai.Features;
import com.autocraft.ai.ThreatNet;
import com.autocraft.combat.Threats;
import com.autocraft.control.Controls;
import com.autocraft.control.InvUtil;
import com.autocraft.path.Goal;
import com.autocraft.path.Navigator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Deals with hostile mobs without wasting time on them. A fight is picked only when it ends quickly: the
 * estimated time to kill everything nearby (weapon damage with crits, attack cooldown, approach) must be short
 * and ThreatNet must not object. Mobs that aren't close or shooting at us are ignored so the job continues;
 * close ones that would take long to kill, or any fight at low health, mean running away. Hits land only at
 * full attack strength, as jump crits when possible; creepers get hit-and-back-off. Each finished fight is fed
 * back to the network as a training example.
 */
public final class CombatBehavior implements Behavior {
	private static final double SCAN = 16;
	/** Melee mobs this close are dealt with (fight or flee); farther ones are left alone. */
	private static final double CLOSE = 4;
	/** Mobs within this distance count toward the kill-time estimate. */
	private static final double GROUP = 6;
	/** A fight that should take longer than this (ticks) isn't worth it. */
	private static final double QUICK_KILL_TICKS = 20 * 4;
	/** Creepers explode 1.5 s after starting to hiss: only fight one that dies faster. */
	private static final double CREEPER_KILL_TICKS = 25;
	private static final float LOW_HEALTH = 8;
	private static final double CHASE_LIMIT = 8;
	private static final int QUIET_TICKS_TO_END = 40;
	private static final int CREEPER_BACKOFF = 15;
	/** Sprinting speed in blocks per tick, for the approach part of the estimate. */
	private static final double SPRINT_SPEED = 0.28;

	private enum Mode { FIGHT, FLEE }

	private final Set<Integer> givenUp = new HashSet<>();
	private boolean engaged;
	private boolean fought;
	private Mode mode = Mode.FIGHT;
	private double @Nullable [] startFeatures;
	private float startHealth;
	private Vec3 startPos = Vec3.ZERO;
	private int quietTicks;
	private int backoff;
	private @Nullable Goal fleeGoal;
	private int fleeGoalAge;
	private @Nullable BlockPos chaseAt;

	@Override
	public String name() {
		return "combat";
	}

	@Override
	public void reset() {
		LocalPlayer p = Bot.get().player();
		if (engaged && fought && startFeatures != null && p != null && !p.isAlive()) {
			Bot.get().threatNet().learnOutcome(startFeatures, 0); // died in this fight
		}
		engaged = false;
		fought = false;
		givenUp.clear();
		fleeGoal = null;
		chaseAt = null;
	}

	@Override
	public boolean tick(Bot bot) {
		LocalPlayer p = bot.player();
		List<Mob> threats = relevantThreats(bot, p);
		if (threats.isEmpty()) {
			if (engaged && ++quietTicks > QUIET_TICKS_TO_END) endEngagement(bot, p);
			return false;
		}
		quietTicks = 0;
		if (!InvUtil.inventoryMenuActive(p)) p.closeContainer();

		ThreatNet net = bot.threatNet();
		double[] x = Features.of(bot, threats);
		double netSays = net.predict(x);
		Mob target = pickTarget(p, threats);
		double killTicks = target == null ? Double.MAX_VALUE : killTicks(p, threats);
		boolean quick = target instanceof Creeper ? killTicks <= CREEPER_KILL_TICKS : killTicks <= QUICK_KILL_TICKS;
		boolean oneHit = target != null && hitsToKill(p, target) <= 1;
		boolean low = p.getHealth() <= LOW_HEALTH;
		Mode wanted = target == null || threats.stream().anyMatch(m -> m instanceof Warden) ? Mode.FLEE
				: low && !oneHit ? Mode.FLEE
				: quick && netSays >= (engaged && mode == Mode.FIGHT ? 0.3 : 0.4) ? Mode.FIGHT
				: Mode.FLEE;
		if (!engaged) {
			engaged = true;
			fought = false;
			startFeatures = x;
			startHealth = p.getHealth();
			startPos = p.position();
		}
		mode = wanted;

		if (mode == Mode.FIGHT) {
			if (p.position().distanceTo(startPos) > CHASE_LIMIT && target.distanceTo(p) > CLOSE) {
				givenUp.add(target.getId()); // it ran; don't get dragged across the map
				return false;
			}
			fight(bot, p, target);
			bot.setStatus(String.format("fighting %s (~%.1fs)", target.getType().getDescription().getString(), killTicks / 20));
		} else {
			flee(bot, threats);
			bot.setStatus(low ? "low health, getting away" : "avoiding " + threats.size() + " mob" + (threats.size() == 1 ? "" : "s"));
		}
		return true;
	}

	/**
	 * Threats worth reacting to: melee mobs within {@link #CLOSE}, ranged mobs shooting at us, flying shooters
	 * when hurt. Everything else is ignored (the job goes on; they're dealt with if they come close).
	 */
	private List<Mob> relevantThreats(Bot bot, LocalPlayer p) {
		List<Mob> out = new ArrayList<>();
		boolean hurt = p.getHealth() <= LOW_HEALTH;
		for (Mob m : Threats.collect(bot, SCAN)) {
			if (givenUp.contains(m.getId())) continue;
			double d = m.distanceTo(p);
			if (Threats.isUnreachableShooter(m)) {
				// ghasts/flying blazes: the defense reflex handles their fireballs; only run when hurting
				if (p.getHealth() < 10 && p.hasLineOfSight(m)) out.add(m);
				continue;
			}
			boolean shooting = Threats.isRanged(m) && m.isAggressive() && p.hasLineOfSight(m);
			if (d < CLOSE || shooting || hurt && m.isAggressive() && d < 10 || m instanceof Creeper && d < CLOSE + 2) out.add(m);
		}
		return out;
	}

	private @Nullable Mob pickTarget(LocalPlayer p, List<Mob> threats) {
		Mob best = null;
		double bestDist = Double.MAX_VALUE;
		for (Mob m : threats) {
			if (Threats.isUnreachableShooter(m) && !p.isWithinEntityInteractionRange(m, 0)) continue;
			double d = m.distanceToSqr(p);
			if (d < bestDist) {
				bestDist = d;
				best = m;
			}
		}
		return best;
	}

	/** Estimated ticks to kill every reachable threat within {@link #GROUP} of us, nearest first. */
	private static double killTicks(LocalPlayer p, List<Mob> threats) {
		double delay = p.getCurrentItemAttackStrengthDelay();
		double total = 0;
		double nearest = Double.MAX_VALUE;
		for (Mob m : threats) {
			if (Threats.isUnreachableShooter(m)) continue;
			double d = m.distanceTo(p);
			nearest = Math.min(nearest, d);
			if (d > GROUP && !Threats.isRanged(m)) continue;
			total += Math.max(0, hitsToKill(p, m) - 1) * delay + delay * 0.5;
		}
		double approach = nearest == Double.MAX_VALUE ? 0 : Math.max(0, nearest - 3) / SPRINT_SPEED;
		return total + approach;
	}

	/** Full-strength hits (crits when we can jump) to kill {@code m} with the best weapon carried. */
	private static int hitsToKill(LocalPlayer p, Mob m) {
		double damage = Math.max(1, Features.bestWeaponDamage(p)) * (canCrit(p) ? 1.5 : 1);
		double armor = m.getArmorValue();
		damage *= 1 - Math.min(20, armor) / 25.0; // vanilla armor reduction, ignoring toughness
		return (int) Math.ceil(m.getHealth() / damage);
	}

	private static boolean canCrit(LocalPlayer p) {
		return !p.isInWater() && !p.onClimbable() && !p.isPassenger();
	}

	private void fight(Bot bot, LocalPlayer p, Mob target) {
		fought = true;
		Controls c = bot.controls();
		selectWeapon(p);
		if (backoff > 0) {
			backoff--;
			c.lookAt(target.getBoundingBox().getCenter());
			c.back();
			return;
		}
		if (p.isWithinEntityInteractionRange(target, 0) && p.hasLineOfSight(target)) {
			bot.nav().stop();
			c.lookAt(target.getBoundingBox().getCenter());
			p.setSprinting(false); // sprint hits are knockback, not crits
			if (p.getAttackStrengthScale(0.5F) < 0.95F) return; // no spam: wait for a full-strength swing
			boolean crit = canCrit(p) && !(target instanceof Creeper);
			if (crit && p.onGround()) {
				c.jump(); // hit on the way down for 1.5x damage
				return;
			}
			if (crit && !(p.fallDistance > 0 && p.getDeltaMovement().y < 0)) return; // still rising
			c.attack(target);
			if (target instanceof Creeper) backoff = CREEPER_BACKOFF;
			return;
		}
		if (p.hasLineOfSight(target) && Math.abs(target.getY() - p.getY()) < 1.5) {
			bot.nav().stop();
			c.faceHorizontally(target.position());
			c.forward();
			c.sprint();
			if (p.horizontalCollision && p.onGround()) c.jump();
			return;
		}
		BlockPos at = target.blockPosition();
		if (chaseAt == null || chaseAt.distSqr(at) > 4) chaseAt = at;
		bot.nav().setGoal(new Goal.Near(chaseAt, 1));
		if (bot.nav().tick() == Navigator.Status.FAILED) {
			givenUp.add(target.getId());
			bot.nav().stop();
		}
	}

	private void flee(Bot bot, List<Mob> threats) {
		if (fleeGoal == null || ++fleeGoalAge > 60) {
			List<BlockPos> from = threats.stream().map(Mob::blockPosition).toList();
			fleeGoal = new Goal.RunAway(from, 24);
			fleeGoalAge = 0;
		}
		bot.nav().setGoal(fleeGoal);
		Navigator.Status s = bot.nav().tick();
		bot.controls().sprint();
		if (s == Navigator.Status.ARRIVED || s == Navigator.Status.FAILED) {
			fleeGoal = null;
			bot.nav().stop();
			if (s == Navigator.Status.FAILED) mode = Mode.FIGHT; // cornered
		}
	}

	/** Best melee weapon into the main hand (shared with hunting). */
	static void selectWeapon(LocalPlayer p) {
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

	private void endEngagement(Bot bot, LocalPlayer p) {
		if (fought && startFeatures != null) {
			boolean wentWell = p.isAlive() && startHealth - p.getHealth() < startHealth * 0.5F;
			bot.threatNet().learnOutcome(startFeatures, wentWell ? 1 : 0);
		}
		engaged = false;
		fought = false;
		startFeatures = null;
		fleeGoal = null;
		chaseAt = null;
		backoff = 0;
		givenUp.clear();
		bot.nav().stop();
	}
}
