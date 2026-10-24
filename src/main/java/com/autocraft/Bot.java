package com.autocraft;

import com.autocraft.ai.ThreatNet;
import com.autocraft.behavior.Behavior;
import com.autocraft.behavior.BuildBehavior;
import com.autocraft.behavior.CollectBehavior;
import com.autocraft.behavior.CombatBehavior;
import com.autocraft.behavior.DefenseReflex;
import com.autocraft.behavior.DrowningReflex;
import com.autocraft.behavior.EatReflex;
import com.autocraft.behavior.EffectReflex;
import com.autocraft.behavior.FallReflex;
import com.autocraft.behavior.FarmBehavior;
import com.autocraft.behavior.FinishBehavior;
import com.autocraft.behavior.FoodBehavior;
import com.autocraft.behavior.GearBehavior;
import com.autocraft.behavior.GotoBehavior;
import com.autocraft.behavior.LavaReflex;
import com.autocraft.behavior.MakeBehavior;
import com.autocraft.behavior.MendingBehavior;
import com.autocraft.behavior.PortalBehavior;
import com.autocraft.behavior.StorageBehavior;
import com.autocraft.build.BuildOrder;
import com.autocraft.build.BuildPlan;
import com.autocraft.control.Controls;
import com.autocraft.craft.RecipeBook;
import com.autocraft.goal.Job;
import com.autocraft.goal.Requirement;
import com.autocraft.goal.Requirement.Dim;
import com.autocraft.team.Team;
import com.autocraft.team.TeamRole;
import com.autocraft.path.Goal;
import com.autocraft.path.Navigator;
import com.autocraft.travel.ElytraTravel;
import com.autocraft.world.WorldScanner;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Mirror;
import org.jspecify.annotations.Nullable;

/**
 * Owns the work (a resource job, a build order, or both) and runs the behavior priority stack once per
 * client tick. Order matters: survival reflexes first, the actual job last.
 */
public final class Bot {
	/** Planners that can ask the collector for prerequisite materials, highest priority first. */
	public enum NeedOwner {
		/** Tools, weapons, armor, nether kit. */
		GEAR,
		/** Materials for the structure being built. */
		BUILD,
		/** Raw materials for crafted/smelted job items. */
		MAKE,
		/** Portal materials (obsidian). */
		TRAVEL
	}

	/** After a failed elytra takeoff, walk for a while before trying again. */
	private static final int FLIGHT_RETRY_TICKS = 20 * 60 * 2;

	private static @Nullable Bot instance;

	private final Minecraft mc;
	private final Controls controls;
	private final WorldScanner scanner;
	private final Navigator nav;
	private final ThreatNet threatNet;
	private final List<Behavior> behaviors;
	private final Map<NeedOwner, Requirement> needs = new EnumMap<>(NeedOwner.class);
	private final Map<String, Integer> fruitlessLegs = new HashMap<>();
	private final Set<Item> buildMaterials = new HashSet<>();
	/** Ingredients (a few recipe levels deep) of the job's crafted items: kept, not junked or used as scaffolding. */
	private final Set<Item> jobMaterials = new HashSet<>();
	private @Nullable BuildPlan buildPlan;
	private int buildPlaced;
	private int buildTotal;
	private TeamRole teamRole = TeamRole.SOLO;
	private @Nullable BlockPos depot;
	private Map<Item, Integer> teamNeeds = Map.of();

	private @Nullable Job job;
	private @Nullable BuildOrder build;
	private @Nullable Goal gotoGoal;
	private @Nullable BlockPos base;
	private Dim baseDim = Dim.OVERWORLD;
	private boolean running;
	private boolean paused;
	private boolean logoffWhenDone = true;
	private String activeBehavior = "idle";
	private String status = "";
	private @Nullable ElytraTravel flight;
	private long ticks;
	private long flightRetryAt;

	private Bot(Minecraft mc) {
		this.mc = mc;
		this.controls = new Controls(mc);
		this.scanner = new WorldScanner(mc);
		this.nav = new Navigator(mc, controls, this::isJobItem);
		this.threatNet = ThreatNet.loadOrTrain();
		this.behaviors = List.of(
				new LavaReflex(),
				new DrowningReflex(),
				new FallReflex(),
				new DefenseReflex(),
				new EatReflex(),
				new EffectReflex(),
				new CombatBehavior(),
				new MendingBehavior(),
				new GotoBehavior(),
				new FinishBehavior(),
				new GearBehavior(),
				new FoodBehavior(),
				new StorageBehavior(),
				new BuildBehavior(),
				new FarmBehavior(),
				new MakeBehavior(),
				new PortalBehavior(),
				new CollectBehavior());
	}

	public static Bot get() {
		if (instance == null) instance = new Bot(Minecraft.getInstance());
		return instance;
	}

	// ---- loop -----------------------------------------------------------------------------------

	public void tick() {
		controls.beginTick();
		if (!running || paused || mc.player == null || mc.level == null || mc.gameMode == null) {
			controls.releaseAll();
			return;
		}
		if (!mc.player.isAlive()) {
			stop("died");
			return;
		}
		scanner.tick();
		ticks++;
		activeBehavior = "idle";
		for (Behavior b : behaviors) {
			// mid-flight only the lava/fall reflexes may interrupt; everything else waits for landing
			if (flight != null && !(b instanceof LavaReflex || b instanceof FallReflex)) {
				tickFlight();
				break;
			}
			if (b.tick(this)) {
				activeBehavior = b.name();
				break;
			}
		}
		if (running) controls.apply();
	}

	private void tickFlight() {
		ElytraTravel.Status s = flight.tick(this);
		activeBehavior = "elytra";
		if (s == ElytraTravel.Status.RUNNING) return;
		if (s == ElytraTravel.Status.FAILED) flightRetryAt = ticks + FLIGHT_RETRY_TICKS;
		flight = null;
		nav.stop();
	}

	/**
	 * Fly to {@code target} instead of walking when an elytra trip is possible and worthwhile.
	 * Returns true if a flight started (the caller should stop navigating this tick).
	 */
	public boolean startFlight(BlockPos target) {
		return startFlight(target, null);
	}

	/** As {@link #startFlight(BlockPos)}; {@code lookout} ends the flight early when it reports true (exploring). */
	public boolean startFlight(BlockPos target, @Nullable BooleanSupplier lookout) {
		if (flight != null || ticks < flightRetryAt || !ElytraTravel.worthIt(this, target)) return false;
		flight = new ElytraTravel(target, lookout);
		nav.stop();
		Chat.info("Flying to " + target.toShortString() + ".");
		return true;
	}

	// ---- work control ---------------------------------------------------------------------------

	public void start(Job newJob) {
		Job mine = Team.shareJob(newJob); // with teammates, they take an even share
		begin(mine, null);
		List<Item> products = mine.requirements().stream().map(Requirement::product).filter(Objects::nonNull).toList();
		if (!products.isEmpty()) jobMaterials.addAll(RecipeBook.get().ingredientTree(products, 3));
		Chat.info("Job started: " + mine.requirements());
	}

	/** Utility mode: only walk to {@code goal}; reflexes and combat still run. */
	public void startGoto(Goal goal) {
		begin(null, goal);
		Chat.info("Going to " + goal);
	}

	/**
	 * Build something. With a resource job running (an auto farm) the job continues afterwards; otherwise
	 * the build is the whole task. A team leader shares user builds with its members.
	 */
	public void startBuild(BuildOrder order) {
		Team.Assignment share = order.purpose() == BuildOrder.Purpose.USER ? Team.shareBuild(order) : null;
		if (share != null) {
			startTeamBuild(order, share.role(), share.depot());
			return;
		}
		if (!running || job == null) begin(null, null);
		build = order;
		Chat.info("Building " + order.schematic().name() + " at " + order.origin().toShortString()
				+ (order.rotation().ordinal() == 0 ? "" : " rotated " + order.rotation().ordinal() * 90 + "°")
				+ (order.mirror() == Mirror.NONE ? "" : ", mirrored") + ".");
	}

	/** One part of a shared build (placer, digger or gatherer); {@code depot} is where materials are exchanged. */
	public void startTeamBuild(BuildOrder order, TeamRole role, BlockPos depot) {
		if (!running || job == null) begin(null, null);
		build = order;
		teamRole = role;
		this.depot = depot;
	}

	/** The current build order is finished or abandoned. */
	public void finishBuild() {
		clearBuild();
		needs.remove(NeedOwner.BUILD);
		nav.stop();
	}

	private void clearBuild() {
		build = null;
		buildPlan = null;
		buildMaterials.clear();
		buildPlaced = buildTotal = 0;
		teamRole = TeamRole.SOLO;
		depot = null;
		teamNeeds = Map.of();
	}

	private void begin(@Nullable Job newJob, @Nullable Goal newGoto) {
		job = newJob;
		gotoGoal = newGoto;
		clearBuild();
		jobMaterials.clear();
		needs.clear();
		fruitlessLegs.clear();
		resetBehaviors();
		running = true;
		paused = false;
		mc.options.pauseOnLostFocus = false; // keep ticking while the window is in the background
	}

	public void stop(String reason) {
		if (!running) return;
		if (teamRole != TeamRole.SOLO || job != null) Team.stopAll();
		running = false;
		paused = false;
		gotoGoal = null;
		clearBuild();
		jobMaterials.clear();
		needs.clear();
		resetBehaviors();
		controls.releaseAll();
		threatNet.save();
		Chat.info("Stopped: " + reason);
	}

	public void setPaused(boolean value) {
		paused = value;
		if (paused) {
			nav.stop();
			controls.releaseAll();
		}
	}

	public void onDisconnect() {
		running = false;
		paused = false;
		job = null;
		clearBuild();
		gotoGoal = null;
		needs.clear();
		resetBehaviors();
		scanner.clear();
		threatNet.save();
	}

	private void resetBehaviors() {
		nav.stop();
		flight = null;
		for (Behavior b : behaviors) b.reset();
	}

	// ---- prerequisites --------------------------------------------------------------------------

	/** Ask the collector for materials before the job ({@code null} clears the owner's need). */
	public void setNeed(NeedOwner owner, @Nullable Requirement r) {
		if (r == null) needs.remove(owner);
		else needs.put(owner, r);
	}

	public @Nullable Requirement need(NeedOwner owner) {
		return needs.get(owner);
	}

	/** Highest-priority outstanding need, or null. */
	public @Nullable Requirement activeNeed() {
		for (NeedOwner o : NeedOwner.values()) {
			Requirement r = needs.get(o);
			if (r != null) return r;
		}
		return null;
	}

	/**
	 * What the collector works on: the highest-priority need that can be gathered in this dimension (else the
	 * highest-priority need, which the portal behavior travels for), else the job's next unmet requirement.
	 */
	public @Nullable Requirement currentTarget() {
		Requirement need = needHereFirst();
		if (need != null) return need;
		return job == null || mc.player == null ? null : job.nextUnmet(mc.player);
	}

	/** What the gear planner must equip for: build/make/travel needs first, else the job's next unmet requirement. */
	public @Nullable Requirement targetForGear() {
		Requirement need = needHereFirst(NeedOwner.GEAR);
		if (need != null) return need;
		return job == null || mc.player == null ? null : job.nextUnmet(mc.player);
	}

	/** Highest-priority need gatherable in this dimension, else the highest-priority need; {@code skip} owners ignored. */
	private @Nullable Requirement needHereFirst(NeedOwner... skip) {
		Dim here = dimension();
		Requirement elsewhere = null;
		for (NeedOwner o : NeedOwner.values()) {
			Requirement r = needs.get(o);
			if (r == null || List.of(skip).contains(o)) continue;
			if (r.dimension() == here) return r;
			if (elsewhere == null) elsewhere = r;
		}
		return elsewhere;
	}

	/** Gear/build/make need (materials a planner waits for; portal obsidian excluded), this dimension first. */
	public @Nullable Requirement materialNeed() {
		return needHereFirst(NeedOwner.TRAVEL);
	}

	/** Dimension the next gathering happens in (a material need, else the job's next mined requirement), or null. */
	public @Nullable Dim nextDimension() {
		return dimensionOf(materialNeed());
	}

	/**
	 * Like {@link #nextDimension()} but ignoring gear needs: what the gear planner prepares for (asking where its
	 * own materials are would make it chase its tail).
	 */
	public @Nullable Dim workDimension() {
		return dimensionOf(needHereFirst(NeedOwner.GEAR, NeedOwner.TRAVEL));
	}

	private @Nullable Dim dimensionOf(@Nullable Requirement need) {
		if (need != null) return need.dimension();
		Requirement next = job == null || mc.player == null ? null : job.nextUnmet(mc.player);
		return next == null || next.isCrafted() ? null : next.dimension();
	}

	/** An exploration leg for {@code requirement} ended without finding any source. */
	public void noteFruitlessLeg(String requirement) {
		fruitlessLegs.merge(requirement, 1, Integer::sum);
	}

	public void noteFound(String requirement) {
		fruitlessLegs.remove(requirement);
	}

	public int fruitlessLegs(String requirement) {
		return fruitlessLegs.getOrDefault(requirement, 0);
	}

	// ---- accessors ------------------------------------------------------------------------------

	public Minecraft mc() { return mc; }
	public LocalPlayer player() { return mc.player; }
	public ClientLevel level() { return mc.level; }
	public Controls controls() { return controls; }
	public WorldScanner scanner() { return scanner; }
	public Navigator nav() { return nav; }
	public ThreatNet threatNet() { return threatNet; }

	public boolean isRunning() { return running; }
	public boolean isPaused() { return paused; }
	public @Nullable Job job() { return job; }
	public @Nullable BuildOrder buildOrder() { return build; }
	public @Nullable Goal gotoGoal() { return gotoGoal; }

	/** There is a job or a build to keep supplies (gear, food, storage) up for. */
	public boolean hasWork() {
		return job != null || build != null;
	}

	/** Counts toward the current job or goes into it (never junk, never used as scaffolding while alternatives exist). */
	public boolean isJobItem(ItemStack s) {
		return job != null && !job.matching(s).isEmpty() || buildMaterials.contains(s.getItem()) || jobMaterials.contains(s.getItem());
	}

	/** Counts toward the job itself (the loot side work must never use up). */
	public boolean isJobTarget(ItemStack s) {
		return job != null && !job.matching(s).isEmpty();
	}

	/** Items the current build still needs (kept out of the junk pile). */
	public void setBuildMaterials(Set<Item> items) {
		buildMaterials.clear();
		buildMaterials.addAll(items);
	}

	public Dim dimension() { return Dim.of(mc.level); }

	public @Nullable BlockPos base() { return base; }
	public Dim baseDimension() { return baseDim; }
	public void setBase(@Nullable BlockPos pos) {
		base = pos;
		baseDim = dimension();
	}

	public boolean logoffWhenDone() { return logoffWhenDone; }
	public void setLogoffWhenDone(boolean v) { logoffWhenDone = v; }

	/** The plan being built (rotated, wood-swapped world positions), for the ghost overlay; null when not building. */
	public @Nullable BuildPlan buildPlan() { return buildPlan; }
	public void setBuildPlan(@Nullable BuildPlan plan) { buildPlan = plan; }

	/** Blocks placed / total of the current build (0/0 when not building). */
	public int buildPlaced() { return buildPlaced; }
	public int buildTotal() { return buildTotal; }
	public void setBuildProgress(int placed, int total) {
		buildPlaced = placed;
		buildTotal = total;
	}

	/** This bot's part of a shared build ({@link TeamRole#SOLO} when working alone). */
	public TeamRole teamRole() { return teamRole; }
	public void setTeamRole(TeamRole role) { teamRole = role; }
	/** Chest spot where a team exchanges materials; null when solo. */
	public @Nullable BlockPos depot() { return depot; }
	/** Gatherer: items to have on hand before the next delivery (sent by the leader). */
	public Map<Item, Integer> teamNeeds() { return teamNeeds; }
	public void setTeamNeeds(Map<Item, Integer> needs) { teamNeeds = Map.copyOf(needs); }

	public String activeBehavior() { return activeBehavior; }
	public String status() { return status; }
	/** Short description of what the active behavior is doing, shown by {@code !ac status}. */
	public void setStatus(String s) { status = s; }
}
