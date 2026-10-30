package com.autocraft.behavior;

import com.autocraft.Bot;
import com.autocraft.ai.CombatObs;
import com.autocraft.ai.CombatPolicy;
import com.autocraft.ai.Features;
import com.autocraft.ai.ThreatNet;
import com.autocraft.combat.Guard;
import com.autocraft.combat.Melee;
import com.autocraft.combat.Threats;
import com.autocraft.control.Controls;
import com.autocraft.control.InvUtil;
import com.autocraft.path.Goal;
import com.autocraft.path.Navigator;
import com.autocraft.world.BlockUtil;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Deals with hostile mobs, and once it has started, finishes: mobs coming for us are engaged when they get within
 * {@link #ENGAGE} and stay engaged until they're dead, well away or out of sight for a while — the job waits
 * meanwhile, instead of walking back into them the moment they drop out of arm's reach (it used to flip between
 * fighting and working every few seconds). What to do is a damage race: the time to kill everything near, against
 * the damage the mobs deal over that time (armor counted, melee hits mostly avoided by spacing), and ThreatNet
 * must not object. A won race is fought: full-strength hits at the edge of reach (jump crits when possible), and
 * between hits the bot steps back out of the mob's reach — away from the group when several come, so they arrive
 * one at a time and never from behind. A lost race against mobs that can't climb or shoot is fought from a 2-block
 * pillar (zombies can't reach up there, the sword reaches down) — the bot eats up there too; otherwise it runs, far
 * enough to lose them.
 * Switching between fighting and running needs a reason that lasts (or low health). Each finished fight is fed
 * back to the network as a training example.
 */
public final class CombatBehavior implements Behavior {
	private static final double SCAN = 24;
	/** Melee mobs coming for us this close are engaged (fight, pillar or run) — and kept until dealt with. */
	private static final double ENGAGE = 8;
	/** Engaged mobs farther than this are let go. */
	private static final double DISENGAGE = 20;
	/** Engaged mobs out of sight this long are let go. */
	private static final int LOST_SIGHT_TICKS = 60;
	/** Mobs within this distance count toward the damage race. */
	private static final double GROUP = 8;
	/** Creepers explode 1.5 s after starting to hiss: only fight one that dies faster. */
	private static final double CREEPER_KILL_TICKS = 25;
	private static final float LOW_HEALTH = 8;
	/** Health a won fight should leave us at least (estimated). */
	private static final double SAFETY = 6;
	/** Share of a melee mob's hits that land while we keep spacing (it has to close the gap each time). */
	private static final double SPACED_EXPOSURE = 0.35;
	/** ... and with the shield up between swings (in a test, two zombies landed nothing). */
	private static final double SHIELDED_EXPOSURE = 0.12;
	/** The movement speed {@link #SPACED_EXPOSURE} was measured against (a zombie's attribute). */
	private static final double ZOMBIE_SPEED = 0.23;
	/** A fight wanders at most this far from where it started before a fleeing mob is let go. */
	private static final double CHASE_LIMIT = 16;
	private static final int QUIET_TICKS_TO_END = 40;
	/** After running away hurt: stay away until this healthy (or until healing isn't possible). */
	private static final float RECOVERED_HEALTH = 14;
	/** After running away, the job waits this long at most for the mobs to go (see {@link #lieLow}). */
	private static final int LIE_LOW_TICKS = 20 * 45;
	/** Mobs we ran from count as gone beyond this distance. */
	private static final double LIE_LOW_DISTANCE = 28;
	/** While recovering, mobs that can reach us are kept at least this far (eating needs 10). */
	private static final double KEEP_AWAY = 12;
	private static final double LURK_RADIUS = 20;
	private static final int CREEPER_BACKOFF = 15;
	/** Sprinting speed in blocks per tick, for the approach part of the estimate. */
	private static final double SPRINT_SPEED = 0.28;
	/** Between hits, keep the target at least this far (a zombie's hits reach about 1.6 blocks, our sword 3). */
	private static final double SPACING = 2.5;
	/** A change of plan (fight ↔ run ↔ pillar) must be wanted this many ticks in a row, unless health is low. */
	private static final int SWITCH_TICKS = 15;
	/** Blocks under the feet that put zombie-like mobs out of reach while the sword still reaches them. */
	private static final int PILLAR_HEIGHT = 2;
	private static final int PILLAR_BLOCKS = 3;
	/** A fall longer than this is a drop not to fight beside (it takes most of our health with the hits). */
	private static final int DEADLY_FALL = 8;
	/** On a pillar this long with no melee mob in reach: they aren't coming (see {@link #pillarIdle}). */
	private static final int PILLAR_WAIT_TICKS = 20 * 20;
	/**
	 * A pillar takes most of a second to build, and one hit knocks us off it: only start one with no melee mob
	 * closer than this (a zombie covers about 3 blocks meanwhile and hits from 1.4).
	 */
	private static final double PILLAR_CLEARANCE = 4;
	/** Melee mobs this close get the shield between swings (it takes 5 ticks to come up). */
	private static final double SHIELD_RANGE = 3.2;
	/** The shield comes down at this much attack strength, so it's down when the swing is ready. */
	private static final float SHIELD_DOWN_AT = 0.85F;
	private enum Mode { FIGHT, FLEE, PILLAR }

	private final Set<Integer> givenUp = new HashSet<>();
	/** Engaged mobs (by id) and the tick each was last seen. */
	private final Map<Integer, Long> engaged = new HashMap<>();
	/** The engaged mobs themselves, to tell a kill from one that got away. */
	private final Map<Integer, Mob> engagedMobs = new HashMap<>();
	private int kills;
	/** Mobs we ran from (see {@link #lieLow}) and when we last did. */
	private final Map<Integer, Mob> fledFrom = new HashMap<>();
	private long fledAt;
	/** Lying low: running from them (until well clear) rather than holding still; ticks they've been out of the way. */
	private boolean lieLowRunning;
	private int calmTicks;
	private long tick;
	private boolean engagement;
	private boolean fought;
	/** This tick's fight moves came from the trained policy (shown in the status). */
	private boolean usedPolicy;
	private Mode mode = Mode.FIGHT;
	private int wantedFor;
	private double @Nullable [] startFeatures;
	private float startHealth;
	private Vec3 startPos = Vec3.ZERO;
	private int quietTicks;
	private int backoff;
	private @Nullable Goal fleeGoal;
	private int fleeGoalAge;
	private final BlockPos[] chaseAt = new BlockPos[1];
	/** Fled at low health: hold the bot away from danger until it has healed (no running back to a furnace by the mob). */
	private boolean recovering;
	/** Pillar mode: the feet position on top of the pillar, and the block placed from the current jump. */
	private @Nullable BlockPos pillarTop;
	private @Nullable BlockPos pillarPlace;
	/** Ticks on top of the pillar with no melee mob within reach. */
	private int pillarIdle;
	/** The last decision written to the debug log (only changes are logged). */
	private String lastDecision = "";

	@Override
	public String name() {
		return "combat";
	}

	@Override
	public void reset() {
		LocalPlayer p = Bot.get().player();
		if (engagement && fought && startFeatures != null && p != null && !p.isAlive()) {
			Bot.get().threatNet().learnOutcome(startFeatures, 0); // died in this fight
		}
		engagement = false;
		fought = false;
		givenUp.clear();
		engaged.clear();
		fledFrom.clear();
		fleeGoal = null;
		chaseAt[0] = null;
		recovering = false;
		pillarTop = null;
		pillarPlace = null;
		wantedFor = 0;
	}

	@Override
	public boolean tick(Bot bot) {
		tick++;
		LocalPlayer p = bot.player();
		List<Mob> threats = engagedThreats(bot, p);
		if (threats.isEmpty()) {
			if (recovering && recover(bot, p)) return true;
			if (lieLow(bot, p)) return true;
			if (engagement && ++quietTicks > QUIET_TICKS_TO_END) endEngagement(bot, p);
			return engagement && mode == Mode.PILLAR && onPillar(p); // stay up there until it's quiet
		}
		quietTicks = 0;
		if (!InvUtil.inventoryMenuActive(p)) p.closeContainer();

		ThreatNet net = bot.threatNet();
		double[] x = Features.of(bot, threats);
		double netSays = net.predict(x);
		Mob target = pickTarget(p, threats);
		double killTicks = target == null ? Double.MAX_VALUE : killTicks(p, threats, target);
		boolean creeper = target instanceof Creeper;
		double damage = damageTaken(p, threats, target, killTicks);
		boolean winnable = creeper ? killTicks <= CREEPER_KILL_TICKS : damage < p.getHealth() - SAFETY;
		boolean oneHit = target != null && hitsToKill(p, target) <= 1;
		// two of its hits from death is low, not a fixed 8: a piglin brute's axe took 9 a hit through iron armor, and at
		// 11 health — "not low" — the bot fought on with one adjacent and was dead two hits later
		boolean low = p.getHealth() <= Math.max(LOW_HEALTH, biggestHit(p, threats) + LOW_HEALTH / 2);
		boolean hunted = target != null && bot.huntTargets().contains(target.getType()); // the job is killing these
		boolean warden = threats.stream().anyMatch(m -> m instanceof Warden);
		if (warden) bot.setQuiet(false); // it's onto us: running beats sneaking now
		// once pillaring, it's judged from where the pillar started, not from wherever the last hit knocked us
		String noPillar = pillarProblem(p, threats);
		boolean pillarable = noPillar == null;
		// archers about: running from them in the open is a back full of arrows (it fled a skeleton and two zombies
		// round an arena until shot dead) — close in and finish them when the fight is survivable at all
		boolean archers = threats.stream().anyMatch(m -> Threats.isRanged(m) && !Threats.isUnreachableShooter(m));
		boolean onlyArchers = threats.stream().allMatch(m -> Threats.isRanged(m) && !Threats.isUnreachableShooter(m));
		// up there, nothing that can reach us: climbing down to "win" faster is how it died. But a mob that never comes
		// (a piglin brute keeps to its post) is waited for for good: twenty minutes on a pillar eleven blocks from one.
		// Nothing in reach for PILLAR_WAIT_TICKS: down, and away from them
		pillarIdle = mode == Mode.PILLAR && onPillar(p) && threats.stream().noneMatch(m -> !Threats.isRanged(m) && m.distanceTo(p) < 4)
				? pillarIdle + 1 : 0;
		boolean waitedOut = pillarIdle > PILLAR_WAIT_TICKS;
		boolean upThere = mode == Mode.PILLAR && pillarable && onPillar(p) && !waitedOut;
		// hurt with a melee mob already at our side: turning to run takes its hits in the back; the shield takes them —
		// from one or two in front, not from a crowd all round (five hoglins and piglins killed it standing its ground)
		Mob closest = closestMelee(p, threats);
		long meleeClose = threats.stream().filter(m -> !Threats.isRanged(m) && m.distanceTo(p) < 4).count();
		boolean caught = Guard.hasShield(p) && closest != null && !Threats.breaksShields(closest) && closest.distanceTo(p) < SHIELD_RANGE && meleeClose <= 2;
		Mode wanted = upThere ? Mode.PILLAR
				: target == null || warden ? Mode.FLEE
				: low && !oneHit ? pillarable ? Mode.PILLAR : caught ? Mode.FIGHT : Mode.FLEE
				: hunted && netSays >= 0.2 ? Mode.FIGHT
				: archers && (onlyArchers || damage < p.getHealth()) && netSays >= 0.2 ? Mode.FIGHT
				: winnable && netSays >= (engagement && mode == Mode.FIGHT ? 0.3 : 0.4) ? Mode.FIGHT
				: pillarable && !waitedOut ? Mode.PILLAR
				: Mode.FLEE;
		if (!engagement) {
			engagement = true;
			fought = false;
			startFeatures = x;
			startHealth = p.getHealth();
			startPos = p.position();
			mode = wanted;
		} else {
			switchTo(wanted, low);
		}
		// the debug log gets each change of plan and what it rested on (not every tick's numbers)
		String decision = mode + " " + wanted + " low " + low + " winnable " + winnable + " pillar " + (noPillar == null ? "ok" : noPillar)
				+ " hunted " + hunted + " archers " + archers + " caught " + caught + " " + threats.size();
		if (!decision.equals(lastDecision)) {
			lastDecision = decision;
			com.autocraft.Chat.LOG.debug("combat {}: {} (wanted {} ticks) hp {} damage {} kill {}t net {} | {}", tick, decision, wantedFor,
					String.format("%.1f", p.getHealth()), String.format("%.1f", damage), Math.round(killTicks), String.format("%.2f", netSays),
					threats.stream().map(m -> String.format("%s@%.1f", m.getType().getDescription().getString(), m.distanceTo(p))).toList());
		}

		switch (mode) {
			case FIGHT -> {
				// nothing in reach to swing at this tick (a blaze drifted off): no fight to make — the hold of the mode
				// (switchTo) mustn't swing at nobody
				if (target == null) return false;
				if (p.position().distanceTo(startPos) > CHASE_LIMIT && target.distanceTo(p) > ENGAGE) {
					givenUp.add(target.getId()); // it ran; don't get dragged across the map
					engaged.remove(target.getId());
					return true;
				}
				fight(bot, p, target, threats);
				bot.setStatus(String.format("fighting %s (%d near, ~%.1fs)%s", target.getType().getDescription().getString(), threats.size(),
						killTicks / 20, usedPolicy ? ", trained moves" : ""));
			}
			case PILLAR -> {
				if (!pillar(bot, p, threats)) { // out of blocks, or knocked off with a mob at our side
					pillarTop = null;
					// the fight judgment decides, the right way round: it read "winnable || low ? flee : fight", so a
					// fight judged unwinnable became a fight — knocked off its pillar, it charged a piglin brute
					mode = winnable && !low ? Mode.FIGHT : Mode.FLEE;
				}
			}
			case FLEE -> {
				flee(bot, threats);
				if (low) recovering = true;
				remember(threats);
				// too strong even for full health (not merely for what's left of ours): work keeps clear of them
				if (damage >= p.getMaxHealth() - SAFETY) {
					for (Mob m : threats) {
						if (!Threats.isRanged(m) && !Threats.isUnreachableShooter(m) && m.distanceTo(p) <= GROUP) bot.dread(m);
					}
				}
				bot.setStatus((low ? "low health, getting away" : "getting away from " + threats.size() + " mob" + (threats.size() == 1 ? "" : "s"))
						+ " (no pillar: " + noPillar + ")");
			}
		}
		return true;
	}

	/**
	 * Change plans only for a reason that lasts {@link #SWITCH_TICKS} (at once when health is low). Every tick of
	 * wanting anything but the current mode counts, toward the latest choice: counted only while one alternative held,
	 * a fight judged lost went on for as long as "pillar" and "run" took turns (room to build, then none as the mob
	 * closed in), each turn restarting the count.
	 */
	private void switchTo(Mode wanted, boolean low) {
		if (wanted == mode) {
			wantedFor = 0;
			return;
		}
		if (++wantedFor >= SWITCH_TICKS || low) {
			if (mode == Mode.PILLAR && wanted != Mode.PILLAR) pillarTop = null;
			mode = wanted;
			wantedFor = 0;
			fleeGoal = null;
		}
	}

	/**
	 * Mobs being dealt with: newly engaged ones (see {@link #engages}) join, and engaged ones stay while they're
	 * within {@link #DISENGAGE} and were in sight within {@link #LOST_SIGHT_TICKS}.
	 */
	private List<Mob> engagedThreats(Bot bot, LocalPlayer p) {
		boolean hurt = p.getHealth() <= LOW_HEALTH;
		List<Mob> out = new ArrayList<>();
		Set<Integer> present = new HashSet<>();
		for (Mob m : Threats.collect(bot, SCAN)) {
			int id = m.getId();
			present.add(id);
			if (givenUp.contains(id)) continue;
			double d = m.distanceTo(p);
			boolean seen = p.hasLineOfSight(m);
			// a flying shooter is fought only while it's in reach (engages): kept on after drifting off, it left combat
			// without a target — and that means running away, from the very blaze being hunted
			boolean flier = Threats.isUnreachableShooter(m);
			if (engages(p, m, d, seen, hurt) || !flier && engaged.containsKey(id) && seen && d < DISENGAGE) {
				engaged.put(id, tick);
				engagedMobs.put(id, m);
			}
			Long last = engaged.get(id);
			if (last != null && (flier ? last == tick : tick - last < LOST_SIGHT_TICKS && d < DISENGAGE)) out.add(m);
		}
		engaged.keySet().removeIf(id -> !present.contains(id) || tick - engaged.get(id) >= LOST_SIGHT_TICKS);
		engagedMobs.entrySet().removeIf(e -> {
			if (engaged.containsKey(e.getKey())) return false;
			if (e.getValue().isDeadOrDying()) {
				kills++;
				com.autocraft.Chat.LOG.info("Killed a {} (kills: {})", e.getValue().getType().getDescription().getString(), kills);
			}
			return true;
		});
		return out;
	}

	/**
	 * Worth reacting to now: melee mobs coming for us within {@link #ENGAGE} (in sight), anything within 4, ranged
	 * mobs shooting at us, creepers getting close, flying shooters when hurt. Farther ones are ignored (the job goes
	 * on); they're engaged if they come.
	 */
	private static boolean engages(LocalPlayer p, Mob m, double d, boolean seen, boolean hurt) {
		// the defense reflex handles their fireballs — but one in sword reach (a blaze at our side) is fought
		if (Threats.isUnreachableShooter(m)) return seen && (p.getHealth() < 10 || DefenseReflex.inReach(p, m));
		if (m instanceof Creeper) return d < 6;
		if (Threats.isRanged(m)) return m.isAggressive() && seen && d < DISENGAGE;
		return d < 4 || d < ENGAGE && seen && m.isAggressive() || hurt && m.isAggressive() && d < 10;
	}

	/**
	 * The nearest threat in reach — but a creeper only when it's the last one: with other mobs about, it's left to
	 * come and blow them up (the defense reflex holds us at the edge of its blast).
	 */
	private @Nullable Mob pickTarget(LocalPlayer p, List<Mob> threats) {
		Mob best = null;
		double bestDist = Double.MAX_VALUE;
		boolean others = threats.stream().anyMatch(m -> !(m instanceof Creeper) && !Threats.isUnreachableShooter(m));
		for (Mob m : threats) {
			if (Threats.isUnreachableShooter(m) && !DefenseReflex.inReach(p, m)) continue; // the reach it's engaged at (engages)
			if (others && m instanceof Creeper) continue;
			double d = m.distanceToSqr(p);
			if (d < bestDist) {
				bestDist = d;
				best = m;
			}
		}
		return best;
	}

	/**
	 * Estimated ticks to kill the target and every other reachable threat within {@link #GROUP} of us, nearest first.
	 * The target counts however far it is: fighting it means walking up to it. Left out beyond 8 blocks, a piglin
	 * brute made the fight winnable at 8.1 blocks and lost at 7.9 — the bot ran, turned back to fight, and ran again,
	 * over and over.
	 */
	private static double killTicks(LocalPlayer p, List<Mob> threats, Mob target) {
		double delay = Features.bestWeaponDelay(p); // not what's in hand while walking: the sword it'll swing
		double total = 0;
		double nearest = Double.MAX_VALUE;
		for (Mob m : threats) {
			if (Threats.isUnreachableShooter(m)) continue;
			double d = m.distanceTo(p);
			nearest = Math.min(nearest, d);
			if (d > GROUP && !Threats.isRanged(m) && m != target) continue;
			total += Math.max(0, hitsToKill(p, m) - 1) * delay + delay * 0.5;
		}
		double approach = nearest == Double.MAX_VALUE ? 0 : Math.max(0, nearest - 3) / SPRINT_SPEED;
		return total + approach;
	}

	/**
	 * Damage we'd take over {@code ticks} of fighting: each melee mob's attack every second (most of them dodged by
	 * spacing — fewer the faster it is — more still with a shield, but not against an axe, which disables it), each
	 * shooter's arrow every two; every hit cut by our armor as vanilla does, which big hits mostly go through.
	 */
	private static double damageTaken(LocalPlayer p, List<Mob> threats, @Nullable Mob target, double ticks) {
		boolean shield = Guard.hasShield(p);
		double armor = p.getArmorValue(), toughness = p.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
		double perTick = 0;
		for (Mob m : threats) {
			if (m.distanceTo(p) > GROUP && !Threats.isRanged(m) && m != target) continue;
			if (Threats.isRanged(m)) {
				perTick += afterArmor(3.0, armor, toughness) / 40;
				continue;
			}
			double exposure = shield && !Threats.breaksShields(m) ? SHIELDED_EXPOSURE : spacedExposure(m);
			perTick += afterArmor(Math.max(1, Threats.meleeHit(m)), armor, toughness) / 20 * exposure;
		}
		return perTick * ticks;
	}

	/** {@link #SPACED_EXPOSURE} was a zombie's; spacing buys less against a faster mob (a brute or a piglin outruns it). */
	private static double spacedExposure(Mob m) {
		double speed = m.getAttributeValue(Attributes.MOVEMENT_SPEED) / ZOMBIE_SPEED;
		return Math.min(1, SPACED_EXPOSURE * speed * speed);
	}

	/** Vanilla armor absorption (CombatRules): armor counts for less against a big hit. */
	private static double afterArmor(double damage, double armor, double toughness) {
		double effective = Math.clamp(armor - damage / (2 + toughness / 4), armor * 0.2, 20);
		return damage * (1 - effective / 25);
	}

	/** The hardest hit a melee mob within reach can land on us, after our armor. */
	private static double biggestHit(LocalPlayer p, List<Mob> threats) {
		double armor = p.getArmorValue(), toughness = p.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
		double best = 0;
		for (Mob m : threats) {
			if (Threats.isRanged(m) || Threats.isUnreachableShooter(m) || m.distanceTo(p) > 4) continue;
			best = Math.max(best, afterArmor(Threats.meleeHit(m), armor, toughness));
		}
		return best;
	}

	/** Full-strength hits (crits when we can jump) to kill {@code m} with the best weapon carried. */
	private static int hitsToKill(LocalPlayer p, Mob m) {
		double damage = Math.max(1, Features.bestWeaponDamage(p)) * (Melee.canCrit(p) ? 1.5 : 1);
		double armor = m.getArmorValue();
		damage *= 1 - Math.min(20, armor) / 25.0; // vanilla armor reduction, ignoring toughness
		return (int) Math.ceil(m.getHealth() / damage);
	}

	/**
	 * Full-strength hits at the edge of reach; while the weapon recharges, back off to {@link #SPACING} — away from
	 * the middle of the group when several are coming, so none gets behind us — with the shield up at whatever is
	 * about to hit us (it takes the hit and knocks the mob back), lowered just before the next swing. Creepers: hit,
	 * then back right off. With a trained policy bundled (see {@link CombatPolicy}), it makes those moves instead
	 * whenever the fight looks like the simulator's: close, on level ground, in sight.
	 */
	private void fight(Bot bot, LocalPlayer p, Mob target, List<Mob> threats) {
		fought = true;
		Controls c = bot.controls();
		Melee.selectWeapon(p);
		CombatPolicy policy = CombatPolicy.get();
		usedPolicy = policy != null && backoff == 0 && policyFits(p, target, threats);
		if (usedPolicy) {
			policyFight(bot, p, target, threats, policy);
			return;
		}
		if (backoff > 0) {
			backoff--;
			c.lookAt(target.getBoundingBox().getCenter());
			if (safeToBackOff(p, target.position())) c.back();
			return;
		}
		boolean ready = p.getAttackStrengthScale(0.5F) >= 0.95F;
		double d = p.distanceTo(target);
		if (ready || d > p.entityInteractionRange() + 1) {
			// a jump crit leaves us in the air, shield down, for half a second: with a shield, plain hits behind it
			if (Melee.strike(bot, target, !Guard.hasShield(p))) {
				if (target instanceof Creeper && p.getAttackStrengthScale(0.5F) < 0.2F) backoff = CREEPER_BACKOFF; // just hit it
				return;
			}
			if (!Melee.approach(bot, target, chaseAt)) {
				givenUp.add(target.getId());
				engaged.remove(target.getId());
			}
			return;
		}
		// recharging: keep out of its reach, stay inside ours
		bot.nav().stop();
		Vec3 from = crowdCentre(p, threats, target);
		Mob closest = closestMelee(p, threats);
		if (closest != null && closest.distanceTo(p) < SHIELD_RANGE && p.getAttackStrengthScale(0.5F) < SHIELD_DOWN_AT
				&& Guard.raiseShield(bot, closest.getBoundingBox().getCenter())) {
			if (d < SPACING && safeToBackOff(p, from)) c.back();
			return;
		}
		c.lookAt(target.getBoundingBox().getCenter());
		if (d < SPACING && safeToBackOff(p, from)) {
			c.back();
		} else if (d > p.entityInteractionRange() - 0.3) {
			c.forward(); // walk, don't sprint: arrive with the swing ready
		}
	}

	/**
	 * The simulator's arena: open, level ground at close range, against mobs it models. A target up a ledge, out of
	 * sight or far off is the pathfinder's; a group with a piglin brute in it is the rules' ({@link CombatObs#simulated});
	 * so is a fight beside a drop that kills ({@link #deadlyDropNear}) — the arena has no edges.
	 */
	private static boolean policyFits(LocalPlayer p, Mob target, List<Mob> threats) {
		return target.distanceTo(p) < 10 && Math.abs(target.getY() - p.getY()) < 1.5 && p.hasLineOfSight(target)
				&& threats.stream().allMatch(m -> m.distanceTo(p) > GROUP || CombatObs.simulated(m)) && !deadlyDropNear(p);
	}

	/**
	 * Within two blocks of us, a drop into lava or a fall of more than {@link #DEADLY_FALL} blocks. A hoglin's hit
	 * throws a player up and back: jumping for crits with the trained moves (shield down) on a ledge above the Nether's
	 * lava sea, the bot was thrown in. Beside such a drop the hand-written fight takes over: no jumps, the shield up
	 * between swings (a blocked hit throws no one).
	 */
	static boolean deadlyDropNear(LocalPlayer p) {
		Level level = p.level();
		BlockPos feet = Navigator.feet(p);
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				if (dx == 0 && dz == 0) continue;
				BlockPos col = feet.offset(dx, 0, dz);
				if (!level.getBlockState(col).getCollisionShape(level, col).isEmpty()) continue; // a wall: nothing to go over
				for (int down = 1; ; down++) {
					BlockPos at = col.below(down);
					var s = level.getBlockState(at);
					if (s.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) return true;
					if (!s.getFluidState().isEmpty()) break; // water breaks the fall
					if (!s.getCollisionShape(level, at).isEmpty()) {
						if (down - 1 > DEADLY_FALL) return true;
						break;
					}
					if (down > DEADLY_FALL + 8 || at.getY() <= level.getMinY()) return true;
				}
			}
		}
		return false;
	}

	/** One tick of the trained policy's moves, facing the target (its frame), never stepping off an edge or into lava. */
	private void policyFight(Bot bot, LocalPlayer p, Mob target, List<Mob> threats, CombatPolicy policy) {
		Controls c = bot.controls();
		CombatPolicy.Action a = policy.act(CombatObs.of(bot, p, target, threats));
		bot.nav().stop();
		c.lookAt(target.getBoundingBox().getCenter());
		boolean shieldUp = p.isUsingItem() && p.getUsedItemHand() == InteractionHand.OFF_HAND;
		if (a.attack()) {
			// as in the simulator: a swing needs the shield down since the last tick (not holding "use" lowers it)
			if (!shieldUp) {
				if (p.isWithinEntityInteractionRange(target, 0) && p.hasLineOfSight(target)) c.attack(target);
				else p.resetAttackStrengthTicker(); // a swing at the air costs the charge, there as here
			}
		} else if (a.shield()) {
			Guard.raiseShield(bot, target.getBoundingBox().getCenter());
		}
		double fx = target.getX() - p.getX(), fz = target.getZ() - p.getZ();
		double n = Math.max(1e-6, Math.sqrt(fx * fx + fz * fz));
		fx /= n;
		fz /= n;
		double ix = a.forward() * fx - a.right() * fz, iz = a.forward() * fz + a.right() * fx;
		if ((a.forward() != 0 || a.right() != 0) && safeStep(p, ix, iz)) {
			if (a.forward() > 0) c.forward();
			else if (a.forward() < 0) c.back();
			if (a.right() > 0) c.right();
			else if (a.right() < 0) c.left();
			if (a.sprint() && a.forward() > 0) c.sprint();
		}
		if (a.jump() && p.onGround()) c.jump();
	}

	/** The nearest mob that hits in melee (not an archer), or null. */
	private static @Nullable Mob closestMelee(LocalPlayer p, List<Mob> threats) {
		Mob best = null;
		for (Mob m : threats) {
			if (Threats.isRanged(m) || Threats.isUnreachableShooter(m)) continue;
			if (best == null || m.distanceToSqr(p) < best.distanceToSqr(p)) best = m;
		}
		return best;
	}

	/** Where to back away from: the target, or the middle of the melee mobs close to us. */
	private static Vec3 crowdCentre(LocalPlayer p, List<Mob> threats, Mob target) {
		Vec3 sum = Vec3.ZERO;
		int n = 0;
		for (Mob m : threats) {
			if (Threats.isRanged(m) || m.distanceTo(p) > GROUP) continue;
			sum = sum.add(m.position());
			n++;
		}
		return n < 2 ? target.position() : sum.scale(1.0 / n);
	}

	/** A step backwards (away from {@code from}) lands on safe ground: no wall, ledge, lava or cactus there. */
	private static boolean safeToBackOff(LocalPlayer p, Vec3 from) {
		return safeStep(p, p.getX() - from.x, p.getZ() - from.z);
	}

	/** A step along (dx, dz) lands on safe ground: no wall, ledge (a one-block step down is fine), lava or cactus. */
	static boolean safeStep(LocalPlayer p, double dx, double dz) {
		Vec3 dir = new Vec3(dx, 0, dz);
		if (dir.lengthSqr() < 1e-4) return false;
		Level level = p.level();
		BlockPos step = BlockPos.containing(p.position().add(dir.normalize().scale(0.9)));
		if (!BlockUtil.isPassable(level, step, level.getBlockState(step)) || !BlockUtil.isPassable(level, step.above(), level.getBlockState(step.above()))) {
			return false;
		}
		for (int down = 1; down <= 2; down++) { // level ground, or a one-block step down
			BlockPos floor = step.below(down);
			if (BlockUtil.canStandOn(level, floor, level.getBlockState(floor))) return true;
			if (!BlockUtil.isPassable(level, floor, level.getBlockState(floor))) return false;
		}
		return false;
	}

	/**
	 * Why a pillar won't do here, or null when it will: it works against mobs that can neither climb nor shoot
	 * (zombies, husks, drowned, vindicators...) — every engaged one is such a mob — with blocks carried, room
	 * overhead, and time to build it before the nearest one arrives. Once pillaring, it's judged from the pillar's
	 * foot (blocks still to place, room at the top).
	 */
	private @Nullable String pillarProblem(LocalPlayer p, List<Mob> threats) {
		// on the ground or not (hits knock us into the air every second), just not swimming or climbing
		BlockPos base = pillarTop != null ? pillarTop.below(PILLAR_HEIGHT) : Navigator.feet(p);
		int blocks = pillarTop != null ? Math.max(0, pillarTop.getY() - Navigator.feet(p).getY()) : PILLAR_BLOCKS;
		if (InvUtil.count(p, InvUtil::isThrowaway) < blocks) return "no blocks";
		if (p.isInWater() || p.isInLava() || p.onClimbable()) return "can't build here";
		for (Mob m : threats) {
			String breaker = pillarBreaker(m);
			if (breaker != null) return breaker;
		}
		Mob closest = closestMelee(p, threats);
		if (pillarTop == null && closest != null && closest.distanceTo(p) < PILLAR_CLEARANCE) return "no time to build one";
		BlockPos head = base.above(2);
		Level level = p.level();
		for (int i = 0; i < PILLAR_HEIGHT; i++) {
			if (!BlockUtil.isPassable(level, head.above(i), level.getBlockState(head.above(i)))) return "no headroom";
		}
		// a block at our feet's level beside the pillar is a step a mob stands on — and from there it reaches the top
		// (a hoglin on a ledge next to the pillar hit the bot up there and knocked it off)
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				BlockPos side = base.offset(dx, 0, dz);
				if ((dx != 0 || dz != 0) && BlockUtil.canStandOn(level, side, level.getBlockState(side))) return "high ground beside";
			}
		}
		return null;
	}

	/** Why {@code m} gets at us on a pillar anyway, or null: it shoots, explodes or climbs. */
	static @Nullable String pillarBreaker(Mob m) {
		if (Threats.isRanged(m) || Threats.isUnreachableShooter(m) || m instanceof Creeper || m instanceof Spider || m instanceof Warden) {
			return "a " + m.getType().getDescription().getString().toLowerCase();
		}
		return null;
	}

	/** Mobs we ran from: remembered until dead, far or {@link #LIE_LOW_TICKS} old (see {@link #lieLow}). */
	private void remember(List<Mob> threats) {
		for (Mob m : threats) {
			if (!Threats.isUnreachableShooter(m)) fledFrom.put(m.getId(), m);
		}
		fledAt = tick;
		calmTicks = 0;
	}

	/**
	 * After running: while mobs we ran from are still after us (alive, within {@link #LIE_LOW_DISTANCE}, and
	 * aggressive or in sight), keep away from them instead of letting the job — or a hunt for food — walk straight
	 * back among them (it went back and forth between a chicken and the husks it had just run from). A mob that has
	 * lost interest doesn't hold the job up; neither does this, for more than {@link #LIE_LOW_TICKS}.
	 */
	private boolean lieLow(Bot bot, LocalPlayer p) {
		if (fledFrom.isEmpty()) return false;
		if (tick - fledAt > LIE_LOW_TICKS) {
			fledFrom.clear();
			return false;
		}
		fledFrom.values().removeIf(m -> !m.isAlive() || m.isRemoved() || m.distanceTo(p) > LIE_LOW_DISTANCE);
		// after us: coming (aggressive), in sight, or close enough to be coming round the corner
		List<Mob> still = fledFrom.values().stream().filter(m -> m.isAggressive() || p.hasLineOfSight(m) || m.distanceTo(p) < KEEP_AWAY)
				.toList();
		if (still.isEmpty()) {
			// a few seconds out of sight before the job resumes (a mob behind a tree is not a mob gone)
			if (++calmTicks < QUIET_TICKS_TO_END) {
				holdStill(bot, p);
				return true;
			}
			fledFrom.clear();
			return false;
		}
		calmTicks = 0;
		double nearest = still.stream().mapToDouble(m -> m.distanceTo(p)).min().orElse(Double.MAX_VALUE);
		// keep going until well clear, then hold — not back and forth on one distance
		if (nearest < KEEP_AWAY) lieLowRunning = true;
		else if (nearest > KEEP_AWAY + 6) lieLowRunning = false;
		if (lieLowRunning) {
			flee(bot, still);
			bot.setStatus("keeping away from " + still.size() + " mob" + (still.size() == 1 ? "" : "s") + " we ran from");
		} else {
			holdStill(bot, p);
			bot.setStatus("lying low: " + still.size() + " mob" + (still.size() == 1 ? "" : "s") + " we ran from still after us");
		}
		return true;
	}

	/** Stay put — treading water when in it (holding still there sinks us until the drowning reflex takes over). */
	private static void holdStill(Bot bot, LocalPlayer p) {
		bot.nav().stop();
		if (p.isInWater()) bot.controls().jump();
	}

	/**
	 * Standing on a 1x1 column at least {@link #PILLAR_HEIGHT} high: nothing for a mob to stand on beside it at
	 * either of the two levels under our feet, so nothing walking can reach us.
	 */
	static boolean onPillar(LocalPlayer p) {
		BlockPos under = standingOn(p);
		Level level = p.level();
		if (!BlockUtil.canStandOn(level, under, level.getBlockState(under))) return false;
		for (int down = 0; down < PILLAR_HEIGHT; down++) {
			for (Direction d : Direction.Plane.HORIZONTAL) {
				BlockPos side = under.below(down).relative(d);
				if (BlockUtil.canStandOn(level, side, level.getBlockState(side))) return false;
			}
		}
		return true;
	}

	/**
	 * The block holding us up — on its edge too, where the feet's centre is over the next column (a hit leaves us
	 * there half the time) — or the one under the feet while in the air.
	 */
	private static BlockPos standingOn(LocalPlayer p) {
		return p.onGround() ? p.mainSupportingBlockPos.orElse(Navigator.feet(p).below()) : Navigator.feet(p).below();
	}

	/**
	 * Up {@link #PILLAR_HEIGHT} blocks (jump, block under the feet), then strike down at whatever comes within
	 * reach — they can't hit back up there. Built is when its top block is down (not when a jump lifts our feet to
	 * that height: that's mid-jump, before the last block can go in). The column is the one holding us up, so a hit
	 * that leaves us on the edge of the pillar's top doesn't start another one beside it; knocked off it onto the
	 * ground, it starts over where we landed if the mobs leave time for that. False when it can't be done (no blocks
	 * left, or knocked off with a mob at our side).
	 */
	private boolean pillar(Bot bot, LocalPlayer p, List<Mob> threats) {
		fought = true;
		Controls c = bot.controls();
		BlockPos cell = standingOn(p).above();
		boolean offColumn = pillarTop != null && (cell.getX() != pillarTop.getX() || cell.getZ() != pillarTop.getZ());
		if (offColumn && p.onGround()) {
			Mob closest = closestMelee(p, threats);
			if (closest != null && closest.distanceTo(p) < PILLAR_CLEARANCE) {
				com.autocraft.Chat.LOG.debug("pillar given up: at {} (onGround {}, vel {}), column {}, {} at {}", p.position(), p.onGround(),
						p.getDeltaMovement(), pillarTop, closest.getType().getDescription().getString(), closest.position());
				return false;
			}
			pillarTop = null;
		}
		if (pillarTop == null) pillarTop = cell.above(PILLAR_HEIGHT);
		bot.nav().stop();
		BlockPos topBlock = pillarTop.below();
		Level level = p.level();
		if (BlockUtil.isPassable(level, topBlock, level.getBlockState(topBlock))) {
			if (InvUtil.count(p, InvUtil::isThrowaway) == 0) return false;
			bot.setStatus("pillaring up out of reach of " + threats.size() + " mob" + (threats.size() == 1 ? "" : "s"));
			c.face(p.getYRot(), 90.0F);
			if (p.onGround()) {
				pillarPlace = cell;
				c.jump();
			} else if (pillarPlace != null && p.getY() >= pillarPlace.getY() + 1.02 && c.place(pillarPlace, InvUtil::isThrowaway)) {
				pillarPlace = null;
			}
			return true;
		}
		c.sneak(); // never off the edge
		Mob target = pickTarget(p, threats);
		if (target != null && Melee.strike(bot, target, false)) {
			bot.setStatus("fighting " + target.getType().getDescription().getString() + " from a pillar");
		} else {
			bot.setStatus("on a pillar, waiting for " + threats.size() + " mob" + (threats.size() == 1 ? "" : "s") + " to come into reach"
					+ (pillarIdle > PILLAR_WAIT_TICKS / 2 ? " (" + (PILLAR_WAIT_TICKS - pillarIdle) / 20 + " s more)" : ""));
		}
		return true;
	}

	private void flee(Bot bot, List<Mob> threats) {
		if (fleeGoal == null || ++fleeGoalAge > 60) {
			List<BlockPos> from = threats.stream().map(Mob::blockPosition).toList();
			fleeGoal = new Goal.RunAway(from, (int) DISENGAGE + 4);
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

	/**
	 * After getting away hurt: keep mobs that can reach us at a distance and stand still meanwhile (the eat reflex
	 * eats, health comes back), instead of letting the job walk straight back to them. Ends once healed, or when
	 * healing can't happen (no food and too hungry to regenerate) — then the food behavior has to fix that first.
	 */
	private boolean recover(Bot bot, LocalPlayer p) {
		boolean canHeal = p.getFoodData().getFoodLevel() >= 18 || EatReflex.hasFood(p);
		if (p.getHealth() >= RECOVERED_HEALTH || !canHeal) {
			recovering = false;
			return false;
		}
		// mobs that can reach us within KEEP_AWAY, and any mob at all within 6 (the eat reflex won't eat next to one)
		Mob near = null;
		for (Mob m : Threats.collect(bot, LURK_RADIUS)) {
			double d = m.distanceTo(p);
			if ((d < 6 || d < KEEP_AWAY && (m.isAggressive() || p.hasLineOfSight(m))) && (near == null || d < near.distanceTo(p))) near = m;
		}
		if (near != null) {
			flee(bot, List.of(near));
			bot.setStatus("low health: keeping away from " + near.getType().getDescription().getString());
			return true;
		}
		bot.nav().stop();
		bot.setStatus(String.format("low health (%.0f): recovering before going back to work", p.getHealth()));
		return true;
	}

	private void endEngagement(Bot bot, LocalPlayer p) {
		if (fought && startFeatures != null) {
			boolean wentWell = p.isAlive() && startHealth - p.getHealth() < startHealth * 0.5F;
			bot.threatNet().learnOutcome(startFeatures, wentWell ? 1 : 0);
		}
		engagement = false;
		fought = false;
		startFeatures = null;
		fleeGoal = null;
		chaseAt[0] = null;
		backoff = 0;
		pillarTop = null;
		pillarPlace = null;
		wantedFor = 0;
		givenUp.clear();
		engaged.clear();
		bot.nav().stop();
	}
}
