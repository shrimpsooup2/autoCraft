package com.autocraft.craft;

import com.autocraft.Bot;
import org.jspecify.annotations.Nullable;

/**
 * Runs crafting and smelting steps from the {@link Planner} and cleans up afterwards: crafting tables and
 * furnaces the bot placed nearby are picked back up when {@link #packUp} is called. Shared by the gear planner,
 * the maker and the builder.
 */
public final class Workshop {
	public enum Status { IDLE, RUNNING, DONE, FAILED }

	private @Nullable Crafter crafter;
	private @Nullable Smelter smelter;
	private final Stations.Cleanup cleanup = new Stations.Cleanup();
	private boolean cleaning;

	public boolean busy() {
		return crafter != null || smelter != null || cleaning;
	}

	/** Begin a Craft or Smelt step; other steps are ignored. */
	public void start(Planner.Step step) {
		switch (step) {
			case Planner.Craft c -> crafter = new Crafter(c.recipe());
			case Planner.Smelt s -> smelter = new Smelter(s.input(), s.count());
			default -> {
			}
		}
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
		cleaning = false;
		cleanup.reset();
	}
}
