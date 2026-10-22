package com.autocraft.path;

import com.autocraft.control.Controls;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * Plans with A* and walks the path. Behaviors call {@link #setGoal} (no-op for an equal goal) and then
 * {@link #tick} every tick they own. ARRIVED/FAILED are sticky until {@link #stop} or a different goal.
 */
public final class Navigator {
	public enum Status { IDLE, MOVING, ARRIVED, FAILED }

	private final Minecraft mc;
	private final Controls controls;
	private @Nullable Goal goal;

	public Navigator(Minecraft mc, Controls controls) {
		this.mc = mc;
		this.controls = controls;
	}

	public void setGoal(Goal newGoal) {
		goal = newGoal;
	}

	public Status tick() {
		return goal == null ? Status.IDLE : Status.FAILED;
	}

	public void stop() {
		goal = null;
	}

	public @Nullable Goal goal() {
		return goal;
	}
}
