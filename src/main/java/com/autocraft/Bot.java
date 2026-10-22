package com.autocraft;

import com.autocraft.ai.ThreatNet;
import com.autocraft.behavior.Behavior;
import com.autocraft.behavior.CollectBehavior;
import com.autocraft.behavior.CombatBehavior;
import com.autocraft.behavior.DefenseReflex;
import com.autocraft.behavior.EatReflex;
import com.autocraft.behavior.EffectReflex;
import com.autocraft.behavior.FallReflex;
import com.autocraft.behavior.FinishBehavior;
import com.autocraft.behavior.FoodBehavior;
import com.autocraft.behavior.GearBehavior;
import com.autocraft.behavior.GotoBehavior;
import com.autocraft.behavior.LavaReflex;
import com.autocraft.behavior.MendingBehavior;
import com.autocraft.behavior.PortalBehavior;
import com.autocraft.behavior.StorageBehavior;
import com.autocraft.control.Controls;
import com.autocraft.goal.Job;
import com.autocraft.goal.Requirement;
import com.autocraft.goal.Requirement.Dim;
import com.autocraft.path.Goal;
import com.autocraft.path.Navigator;
import com.autocraft.travel.ElytraTravel;
import com.autocraft.world.WorldScanner;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Owns the job and runs the behavior priority stack once per client tick.
 * Order matters: survival reflexes first, the actual job last.
 */
public final class Bot {
	/** Planners that can ask the collector for prerequisite materials, highest priority first. */
	public enum NeedOwner {
		/** Tools, weapons, armor, nether kit. */
		GEAR,
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

	private @Nullable Job job;
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
		if (flight != null || ticks < flightRetryAt || !ElytraTravel.worthIt(this, target)) return false;
		flight = new ElytraTravel(target);
		nav.stop();
		Chat.info("Flying to " + target.toShortString() + ".");
		return true;
	}

	// ---- job control ----------------------------------------------------------------------------

	public void start(Job newJob) {
		begin(newJob, null);
		Chat.info("Job started: " + newJob.requirements());
	}

	/** Utility mode: only walk to {@code goal}; reflexes and combat still run. */
	public void startGoto(Goal goal) {
		begin(null, goal);
		Chat.info("Going to " + goal);
	}

	private void begin(@Nullable Job newJob, @Nullable Goal newGoto) {
		job = newJob;
		gotoGoal = newGoto;
		needs.clear();
		resetBehaviors();
		running = true;
		paused = false;
		mc.options.pauseOnLostFocus = false; // keep ticking while the window is in the background
	}

	public void stop(String reason) {
		if (!running) return;
		running = false;
		paused = false;
		gotoGoal = null;
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

	/** What the collector works on: the active need, else the job's next unmet requirement. */
	public @Nullable Requirement currentTarget() {
		Requirement need = activeNeed();
		if (need != null) return need;
		return job == null || mc.player == null ? null : job.nextUnmet(mc.player);
	}

	/** What the gear planner must equip for: the travel need if any, else the job's next unmet requirement. */
	public @Nullable Requirement targetForGear() {
		Requirement travel = needs.get(NeedOwner.TRAVEL);
		if (travel != null) return travel;
		return job == null || mc.player == null ? null : job.nextUnmet(mc.player);
	}

	// ---- accessors ------------------------------------------------------------------------------

	public Minecraft mc() { return mc; }
	public LocalPlayer player() { return mc.player; }
	public Controls controls() { return controls; }
	public WorldScanner scanner() { return scanner; }
	public Navigator nav() { return nav; }
	public ThreatNet threatNet() { return threatNet; }

	public boolean isRunning() { return running; }
	public boolean isPaused() { return paused; }
	public @Nullable Job job() { return job; }
	public @Nullable Goal gotoGoal() { return gotoGoal; }

	/** Counts toward the current job (never junk, never used as scaffolding while alternatives exist). */
	public boolean isJobItem(ItemStack s) {
		return job != null && !job.matching(s).isEmpty();
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

	public String activeBehavior() { return activeBehavior; }
	public String status() { return status; }
	/** Short description of what the active behavior is doing, shown by {@code !ac status}. */
	public void setStatus(String s) { status = s; }
}
