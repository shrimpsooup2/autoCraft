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
 * Deals with hostile mobs. ThreatNet decides fight or flee (with hysteresis so it doesn't flip-flop);
 * the fight itself is hardcoded: best weapon, close in, swing only at full attack strength, hit-and-back
 * off creepers. Each finished fight is fed back to the network as a training example.
 */
public final class CombatBehavior implements Behavior {
	private static final double SCAN = 16;
	private static final double ENGAGE = 8;
	private static final double CHASE_LIMIT = 24;
	private static final int QUIET_TICKS_TO_END = 40;
	private static final int CREEPER_BACKOFF = 15;

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
		double fight = net.predict(x);
		if (!engaged) {
			engaged = true;
			fought = false;
			startFeatures = x;
			startHealth = p.getHealth();
			startPos = p.position();
			mode = fight >= 0.5 ? Mode.FIGHT : Mode.FLEE;
		} else if (mode == Mode.FIGHT && fight < 0.4) {
			mode = Mode.FLEE;
		} else if (mode == Mode.FLEE && fight > 0.6) {
			mode = Mode.FIGHT;
		}

		Mob target = pickTarget(p, threats);
		boolean mustFlee = target == null || threats.stream().anyMatch(m -> m instanceof Warden);
		if (mode == Mode.FIGHT && !mustFlee) {
			if (p.position().distanceTo(startPos) > CHASE_LIMIT) {
				givenUp.add(target.getId()); // don't get dragged across the map
				return true;
			}
			fight(bot, p, target);
			bot.setStatus(String.format("fighting %s (p=%.2f)", target.getType().getDescription().getString(), fight));
		} else {
			flee(bot, threats);
			bot.setStatus(String.format("fleeing %d mobs (p=%.2f)", threats.size(), fight));
		}
		return true;
	}

	/** Threats worth reacting to: close, already after us, or shooting at us. */
	private List<Mob> relevantThreats(Bot bot, LocalPlayer p) {
		List<Mob> out = new ArrayList<>();
		for (Mob m : Threats.collect(bot, SCAN)) {
			if (givenUp.contains(m.getId())) continue;
			double d = m.distanceTo(p);
			if (Threats.isUnreachableShooter(m)) {
				// ghasts/flying blazes: the defense reflex handles their fireballs; only run when hurting
				if (p.getHealth() < 10 && p.hasLineOfSight(m)) out.add(m);
				continue;
			}
			if (d < ENGAGE || m.isAggressive() && d < SCAN || Threats.isRanged(m) && p.hasLineOfSight(m)) out.add(m);
		}
		return out;
	}

	private @Nullable Mob pickTarget(LocalPlayer p, List<Mob> threats) {
		for (Mob m : threats) {
			if (!Threats.isUnreachableShooter(m) || p.isWithinEntityInteractionRange(m, 0)) return m;
		}
		return null;
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
			if (p.getAttackStrengthScale(0.5F) >= 0.9F) {
				c.attack(target);
				if (target instanceof Creeper) backoff = CREEPER_BACKOFF;
			}
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

	private static void selectWeapon(LocalPlayer p) {
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
