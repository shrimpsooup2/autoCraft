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
import com.autocraft.behavior.GearBehavior;
import com.autocraft.behavior.GotoBehavior;
import com.autocraft.behavior.StorageBehavior;
import com.autocraft.control.Controls;
import com.autocraft.goal.Job;
import com.autocraft.goal.Requirement;
import com.autocraft.path.Goal;
import com.autocraft.path.Navigator;
import com.autocraft.world.WorldScanner;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/**
 * Owns the job and runs the behavior priority stack once per client tick.
 * Order matters: survival reflexes first, the actual job last.
 */
public final class Bot {
	private static @Nullable Bot instance;

	private final Minecraft mc;
	private final Controls controls;
	private final WorldScanner scanner;
	private final Navigator nav;
	private final ThreatNet threatNet;
	private final List<Behavior> behaviors;

	private @Nullable Job job;
	private @Nullable Goal gotoGoal;
	private @Nullable Requirement subTask;
	private @Nullable BlockPos base;
	private boolean running;
	private boolean paused;
	private boolean logoffWhenDone = true;
	private String activeBehavior = "idle";
	private String status = "";

	private Bot(Minecraft mc) {
		this.mc = mc;
		this.controls = new Controls(mc);
		this.scanner = new WorldScanner(mc);
		this.nav = new Navigator(mc, controls);
		this.threatNet = ThreatNet.loadOrTrain();
		this.behaviors = List.of(
				new FallReflex(),
				new DefenseReflex(),
				new EatReflex(),
				new EffectReflex(),
				new CombatBehavior(),
				new GotoBehavior(),
				new FinishBehavior(),
				new GearBehavior(),
				new StorageBehavior(),
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
		activeBehavior = "idle";
		for (Behavior b : behaviors) {
			if (b.tick(this)) {
				activeBehavior = b.name();
				break;
			}
		}
		if (running) controls.apply();
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
		subTask = null;
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
		subTask = null;
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
		subTask = null;
		resetBehaviors();
		scanner.clear();
		threatNet.save();
	}

	private void resetBehaviors() {
		nav.stop();
		for (Behavior b : behaviors) b.reset();
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

	/** Prerequisite the collector should gather before the job itself (set by the gear/tool planner). */
	public @Nullable Requirement subTask() { return subTask; }
	public void setSubTask(@Nullable Requirement r) { subTask = r; }

	public @Nullable BlockPos base() { return base; }
	public void setBase(@Nullable BlockPos pos) { base = pos; }

	public boolean logoffWhenDone() { return logoffWhenDone; }
	public void setLogoffWhenDone(boolean v) { logoffWhenDone = v; }

	public String activeBehavior() { return activeBehavior; }
	public String status() { return status; }
	/** Short description of what the active behavior is doing, shown by {@code !ac status}. */
	public void setStatus(String s) { status = s; }
}
