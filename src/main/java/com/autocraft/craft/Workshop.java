package com.autocraft.craft;

import com.autocraft.Bot;
import com.autocraft.goal.Requirement;
import com.autocraft.procedure.Procedure;
import com.autocraft.procedure.Procedures;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;

/**
 * Runs crafting, smelting and special-procedure steps from the {@link Planner} and cleans up afterwards: crafting
 * tables and furnaces the bot placed nearby are picked back up when {@link #packUp} is called. Shared by the gear
 * planner, the maker and the builder.
 */
public final class Workshop {
	public enum Status {
		IDLE,
		RUNNING,
		DONE,
		FAILED,
		/** A procedure needs the world to take its time ({@link #waitingFor()}): do other work, come back later. */
		WAITING,
		/** A procedure needs the other dimension ({@link Bot#procedureDimension()}): let the portal behavior run. */
		TRAVEL
	}

	private @Nullable Crafter crafter;
	private @Nullable Smelter smelter;
	private @Nullable Cutter cutter;
	private Procedure.@Nullable Task special;
	private @Nullable Item specialItem;
	private final Stations.Cleanup cleanup = new Stations.Cleanup();
	private boolean cleaning;

	public boolean busy() {
		return crafter != null || smelter != null || cutter != null || special != null || cleaning;
	}

	/** Begin a Craft, Smelt, Cut or Special step; other steps are ignored. */
	public void start(Planner.Step step) {
		switch (step) {
			case Planner.Craft c -> crafter = new Crafter(c.recipe());
			case Planner.Smelt s -> smelter = new Smelter(s.input(), s.count());
			case Planner.Cut c -> cutter = new Cutter(c.input(), c.output(), c.cuts());
			case Planner.Special sp -> {
				special = sp.procedure().start(sp.item(), sp.total());
				specialItem = sp.item();
			}
			default -> {
			}
		}
	}

	/** The item of the procedure that last reported {@link Status#WAITING}. */
	public @Nullable Item waitingFor() {
		return specialItem;
	}

	public Status tick(Bot bot) {
		if (crafter != null) {
			Crafter.Status s = crafter.tick(bot);
			bot.setStatus("crafting " + crafter.recipe().output().getDefaultInstance().getHoverName().getString());
			if (s == Crafter.Status.RUNNING) return Status.RUNNING;
			crafter = null;
			return s == Crafter.Status.DONE ? Status.DONE : Status.FAILED;
		}
		if (smelter != null) {
			Smelter.Status s = smelter.tick(bot);
			if (s == Smelter.Status.RUNNING) return Status.RUNNING;
			smelter = null;
			return s == Smelter.Status.DONE ? Status.DONE : Status.FAILED;
		}
		if (cutter != null) {
			Cutter.Status s = cutter.tick(bot);
			if (s == Cutter.Status.RUNNING) return Status.RUNNING;
			cutter = null;
			return s == Cutter.Status.DONE ? Status.DONE : Status.FAILED;
		}
		if (special != null) {
			Procedure.Task.Status s = special.tick(bot);
			Requirement.Dim wants = special.wantsDimension();
			bot.setProcedureDimension(s == Procedure.Task.Status.RUNNING && wants != null && wants != bot.dimension() ? wants : null);
			if (s == Procedure.Task.Status.RUNNING) return bot.procedureDimension() != null ? Status.TRAVEL : Status.RUNNING;
			special = null;
			if (s == Procedure.Task.Status.FAILED) Procedures.failed(specialItem);
			return switch (s) {
				case DONE -> Status.DONE;
				case WAITING -> Status.WAITING;
				default -> Status.FAILED;
			};
		}
		if (cleaning) {
			if (cleanup.tick(bot)) return Status.RUNNING;
			cleaning = false;
			return Status.DONE;
		}
		return Status.IDLE;
	}

	/** Close any station screen and pick up the tables/furnaces we placed around here. True if that started. */
	public boolean packUp(Bot bot) {
		if (!com.autocraft.control.InvUtil.inventoryMenuActive(bot.player())) bot.player().closeContainer();
		if (cleaning) return true;
		cleaning = cleanup.tick(bot);
		return cleaning;
	}

	public void reset() {
		crafter = null;
		smelter = null;
		cutter = null;
		special = null;
		specialItem = null;
		cleaning = false;
		cleanup.reset();
	}
}
