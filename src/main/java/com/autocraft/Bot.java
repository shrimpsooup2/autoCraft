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
import com.autocraft.behavior.FetchBehavior;
import com.autocraft.behavior.FinishBehavior;
import com.autocraft.behavior.FoodBehavior;
import com.autocraft.behavior.GearBehavior;
import com.autocraft.behavior.GotoBehavior;
import com.autocraft.behavior.LavaReflex;
import com.autocraft.behavior.MakeBehavior;
import com.autocraft.behavior.MendingBehavior;
import com.autocraft.behavior.PortalBehavior;
import com.autocraft.behavior.RecoverBehavior;
import com.autocraft.behavior.StorageBehavior;
import com.autocraft.build.BuildOrder;
import com.autocraft.build.BuildPlan;
import com.autocraft.control.Controls;
import com.autocraft.craft.RecipeBook;
import com.autocraft.goal.Job;
import com.autocraft.goal.Requirement;
import com.autocraft.goal.Requirement.Dim;
import com.autocraft.procedure.Procedures;
import com.autocraft.tasks.TaskTree;
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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
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
	private int @Nullable [] buildDig;
	private TeamRole teamRole = TeamRole.SOLO;
	private @Nullable BlockPos depot;
	private Map<Item, Integer> teamNeeds = Map.of();
	/** Mobs the collector is hunting right now (combat fights these instead of avoiding them). */
	private Set<EntityType<?>> huntTargets = Set.of();
	/** Where to go back for our dropped items after a death; null when not recovering. */
	private @Nullable Recovery recovery;
	private boolean respawnRequested;

	/** Death spot to return to. */
	public record Recovery(BlockPos pos, Dim dim) {}

	/** Take {@code amount} of {@code item} out of the chests at the base at {@code base} (a task-tree step). */
	public record FetchOrder(BlockPos base, Dim dim, Item item, int amount) {}

	private @Nullable FetchOrder fetchOrder;
	private @Nullable Dim procedureDimension;

	/** Progress is saved this often while running (and whenever work starts or stops). */
	private static final int SAVE_EVERY = 20 * 5;
	/** Stop reasons for finished work (the task tree moves on after these). */
	public static final String JOB_COMPLETE = "job complete";
	public static final String BUILD_COMPLETE = "build complete";
	public static final String FETCH_COMPLETE = "fetch complete";

	private String lastStop = "";

	private @Nullable Job job;
	private @Nullable BuildOrder build;
	private @Nullable Goal gotoGoal;
	private boolean running;
	private boolean paused;
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
				new RecoverBehavior(),
				new MendingBehavior(),
				new GotoBehavior(),
				new FetchBehavior(),
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
			onDeath();
			return;
		}
		respawnRequested = false;
		if (playerAlert()) return;
		scanner.tick();
		ticks++;
		if (ticks % SAVE_EVERY == 0) TaskSave.save(this, true);
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

	/** "Log off near players": another player (not a teammate) within the radius ends the session. */
	private boolean playerAlert() {
		Settings s = Settings.get();
		if (!s.logoffNearPlayers) return false;
		Set<String> team = new HashSet<>();
		for (Team.MemberView m : Team.roster()) team.add(m.name());
		for (Player other : mc.level.players()) {
			if (other == mc.player || team.contains(other.getName().getString())) continue;
			if (other.distanceTo(mc.player) > s.playerAlertRadius) continue;
			Chat.warn(other.getName().getString() + " came within " + (int) other.distanceTo(mc.player) + " blocks; logging off.");
			stop("player nearby");
			mc.schedule(() -> mc.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE)); // outside the tick
			return true;
		}
		return false;
	}

	/** Log where we died; then respawn and go back for the items (setting), or stop. */
	private void onDeath() {
		if (respawnRequested) return; // the server hasn't respawned us yet
		LocalPlayer p = mc.player;
		BlockPos pos = p.blockPosition();
		Dim dim = dimension();
		String cause = p.getLastDamageSource() == null ? "unknown" : p.getLastDamageSource().getMsgId();
		DeathLog.add(pos, dim, cause);
		Chat.warn("Died at " + pos.toShortString() + " in the " + dim.name().toLowerCase() + " (" + cause + ").");
		if (!Settings.get().recoverAfterDeath || mc.level.getLevelData().isHardcore()) {
			stop("died");
			return;
		}
		boolean burnt = p.isInLava() || cause.equals("lava") || cause.equals("onFire") || cause.equals("inFire");
		resetBehaviors();
		controls.releaseAll();
		needs.clear();
		recovery = burnt ? null : new Recovery(pos, dim);
		Chat.info(burnt ? "The items burnt; respawning and carrying on with the job." : "Respawning; going back for the items, then carrying on.");
		p.respawn();
		mc.gui.setScreen(null);
		respawnRequested = true;
	}

	public @Nullable Recovery recovery() {
		return recovery;
	}

	public void setRecovery(@Nullable Recovery r) {
		recovery = r;
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
		TaskSave.save(this, true);
	}

	/** Utility mode: only walk to {@code goal}; reflexes and combat still run. */
	public void startGoto(Goal goal) {
		begin(null, goal);
		Chat.info("Going to " + goal);
	}

	/** Utility mode: get items from a base's chests (through a portal if the base is in the other dimension). */
	public void startFetch(FetchOrder order) {
		begin(null, null);
		fetchOrder = order;
		Chat.info("Getting " + order.amount() + " " + order.item().getDefaultInstance().getHoverName().getString() + " from the base at "
				+ order.base().toShortString() + ".");
	}

	public @Nullable FetchOrder fetchOrder() {
		return fetchOrder;
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
		TaskSave.save(this, true);
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
		buildDig = null;
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
		Procedures.reset();
		resetBehaviors();
		recovery = null;
		fetchOrder = null;
		running = true;
		paused = false;
		mc.options.pauseOnLostFocus = false; // keep ticking while the window is in the background
	}

	public void stop(String reason) {
		if (!running) return;
		lastStop = reason;
		if (teamRole != TeamRole.SOLO || job != null) Team.stopAll();
		running = false;
		paused = false;
		gotoGoal = null;
		clearBuild();
		jobMaterials.clear();
		needs.clear();
		resetBehaviors();
		recovery = null;
		fetchOrder = null;
		controls.releaseAll();
		threatNet.save();
		TaskSave.save(this, false);
		Chat.info("Stopped: " + reason);
		if (reason.equals(JOB_COMPLETE) || reason.equals(BUILD_COMPLETE) || reason.equals(FETCH_COMPLETE)) TaskTree.completed();
	}

	/** Why the bot last stopped ({@link #JOB_COMPLETE}, {@link #BUILD_COMPLETE}, or something else). */
	public String lastStop() {
		return lastStop;
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
		huntTargets = Set.of();
		setQuiet(false);
		procedureDimension = null;
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
		if (procedureDimension != null) return procedureDimension; // a fortress or bastion chest: kit up for the Nether
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

	/** The nearest base in this dimension (else home); null when no base is set. */
	public @Nullable BlockPos base() {
		Bases.Base b = nearestBase();
		return b == null ? null : b.pos();
	}

	public Dim baseDimension() {
		Bases.Base b = nearestBase();
		return b == null ? Dim.OVERWORLD : b.dim();
	}

	private Bases.@Nullable Base nearestBase() {
		return mc.level == null ? null : Bases.nearest(dimension(), mc.player == null ? null : mc.player.blockPosition());
	}

	/** Add a base here (or move the one at this spot); returns its name. */
	public String setBase(BlockPos pos) {
		return Bases.set(null, pos, dimension());
	}

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

	/** Blocks the current build must dig out per tool (pickaxe, shovel, axe); null when not building. */
	public int @Nullable [] buildDig() { return buildDig; }
	public void setBuildDig(int @Nullable [] dig) { buildDig = dig; }

	/** This bot's part of a shared build ({@link TeamRole#SOLO} when working alone). */
	public TeamRole teamRole() { return teamRole; }
	public void setTeamRole(TeamRole role) { teamRole = role; }
	/** Chest spot where a team exchanges materials; null when solo. */
	public @Nullable BlockPos depot() { return depot; }
	/** Gatherer: items to have on hand before the next delivery (sent by the leader). */
	public Map<Item, Integer> teamNeeds() { return teamNeeds; }
	public void setTeamNeeds(Map<Item, Integer> needs) { teamNeeds = Map.copyOf(needs); }
	public Set<EntityType<?>> huntTargets() { return huntTargets; }
	public void setHuntTargets(Set<EntityType<?>> mobs) { huntTargets = mobs; }

	/** Ancient city mode: sneak everywhere, no sprinting, no mining or placing (except wool), never step on sculk. */
	public void setQuiet(boolean value) {
		controls.setQuiet(value);
		nav.setQuiet(value);
	}

	public boolean quiet() { return controls.quiet(); }

	/** The dimension a running procedure needs to be in (a fortress chest in the Nether); null = here. */
	public @Nullable Dim procedureDimension() { return procedureDimension; }
	public void setProcedureDimension(@Nullable Dim dim) { procedureDimension = dim; }

	public String activeBehavior() { return activeBehavior; }
	public String status() { return status; }
	/** Short description of what the active behavior is doing, shown by {@code !ac status}. */
	public void setStatus(String s) { status = s; }
}
