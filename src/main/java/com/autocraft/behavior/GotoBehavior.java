package com.autocraft.behavior;

import com.autocraft.Bot;
import com.autocraft.Chat;
import com.autocraft.goal.Destination;
import com.autocraft.loot.LootSites;
import com.autocraft.path.Goal;
import com.autocraft.path.Navigator;
import com.autocraft.procedure.StructureSearch;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/**
 * A go-to task ({@link Bot#startGoto}): walk (or fly) to coordinates — any standable height on the column when no Y
 * was given — or a base; or find the nearest structure of a kind (in sight, else exploring the way structure loot
 * searches do, {@link StructureSearch}) and walk to it. Last in the chain, so gear, food, storage and the portal
 * behavior (a destination in the other dimension) come first, as for any job; arriving hands over to the end-of-job
 * flow ({@link FinishBehavior}).
 */
public final class GotoBehavior implements Behavior {
	private static final int BASE_ARRIVE = 3;
	/** Close enough to a structure's tell block: they sit in walls and floors. */
	private static final int STRUCTURE_ARRIVE = 4;
	/** Searches that may come up empty (a world still loading after a portal) before the trip is given up. */
	private static final int MAX_NAV_FAILURES = 4;
	private static final int RETRY_TICKS = 40;
	/** Getting this much closer since the last failed search starts the failure count over (long trips). */
	private static final int PROGRESS = 16;

	private @Nullable Destination current;
	private @Nullable StructureSearch search;
	/** The structure being walked to (one of its tell blocks), once seen. */
	private @Nullable BlockPos found;
	private int navFailures;
	/** How far off the last failed search left us. */
	private double failedAt = Double.MAX_VALUE;
	private int wait;

	@Override
	public String name() {
		return "goto";
	}

	@Override
	public void reset() {
		current = null;
		search = null;
		found = null;
		navFailures = 0;
		failedAt = Double.MAX_VALUE;
		wait = 0;
	}

	@Override
	public boolean tick(Bot bot) {
		Destination d = bot.destination();
		if (d == null || bot.finishing() != null || d.dim() != bot.dimension()) return false; // other dimension: the portal behavior
		if (bot.tripPickaxe() != null) return false; // gear makes a pickaxe first (Bot.considerTripPickaxe)
		if (d != current) {
			reset();
			current = d;
		}
		if (wait > 0) {
			wait--;
			bot.setStatus("going to " + d.describe() + " (waiting for the area to load)");
			return true;
		}
		Goal goal;
		BlockPos at;
		switch (d.kind()) {
			case COORDS -> {
				at = new BlockPos(d.x(), d.y() != null ? d.y() : Navigator.feet(bot.player()).getY(), d.z());
				goal = d.y() != null ? new Goal.Block(at) : new Goal.XZ(d.x(), d.z());
			}
			case BASE -> {
				at = new BlockPos(d.x(), d.y() != null ? d.y() : 64, d.z());
				goal = new Goal.Near(at, BASE_ARRIVE);
			}
			default -> {
				if (found == null && !findStructure(bot, d)) return true;
				at = found;
				goal = new Goal.Near(found, STRUCTURE_ARRIVE);
			}
		}
		bot.setStatus("going to " + (d.kind() == Destination.Kind.STRUCTURE ? "the " + d.name() + " at " + at.toShortString() : d.describe()));
		if (bot.startFlight(at)) return true;
		bot.nav().setGoal(goal);
		switch (bot.nav().tick()) {
			case ARRIVED -> {
				bot.nav().stop();
				Chat.info("Arrived at " + (d.kind() == Destination.Kind.STRUCTURE ? "the " + d.name() + " at " + at.toShortString() : d.describe()) + ".");
				bot.goalMet(Bot.ARRIVED);
			}
			case FAILED -> {
				bot.nav().stop();
				double off = Math.sqrt(Navigator.feet(bot.player()).distSqr(at));
				if (off < failedAt - PROGRESS) navFailures = 0;
				failedAt = off;
				if (bot.pickaxeForStuckTrip()) return true; // planned again once it's made
				if (++navFailures < MAX_NAV_FAILURES) {
					wait = RETRY_TICKS;
					return true;
				}
				Chat.warn("Can't find a way to " + d.describe() + ".");
				bot.stop("no way to " + d.describe());
			}
			default -> {
			}
		}
		return true;
	}

	/**
	 * Look for the structure: in sight (anywhere loaded), else explore within the search's budget. True once one is
	 * found ({@link #found}); false while still looking, or when the search gave up (the task then stops).
	 */
	private boolean findStructure(Bot bot, Destination d) {
		if (search == null) {
			LootSites.Site site = LootSites.structure(d.name());
			if (site == null) {
				Chat.warn("Don't know a structure called \"" + d.name() + "\".");
				bot.stop("unknown structure " + d.name());
				return false;
			}
			search = new StructureSearch(site, pos -> false);
		}
		found = search.find(bot);
		if (found != null) {
			bot.nav().stop();
			Chat.info("Found a " + d.name() + " at " + found.toShortString() + ".");
			return true;
		}
		if (search.explore(bot, search.mapTarget(bot))) return false;
		Chat.warn("Can't find a " + d.name() + ": explored " + StructureSearch.MAX_LEGS + " legs without seeing one.");
		bot.stop("can't find a " + d.name());
		return false;
	}
}
