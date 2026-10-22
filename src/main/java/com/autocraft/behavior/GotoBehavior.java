package com.autocraft.behavior;

import com.autocraft.Bot;
import com.autocraft.path.Goal;

/** {@code !ac goto}: walk to a position, then stop. Also the simplest way to exercise the pathfinder. */
public final class GotoBehavior implements Behavior {
	@Override
	public String name() {
		return "goto";
	}

	@Override
	public boolean tick(Bot bot) {
		Goal goal = bot.gotoGoal();
		if (goal == null) return false;
		bot.nav().setGoal(goal);
		bot.setStatus("walking to " + goal);
		switch (bot.nav().tick()) {
			case ARRIVED -> bot.stop("arrived at " + goal);
			case FAILED -> bot.stop("no path to " + goal);
			default -> {
			}
		}
		return true;
	}
}
