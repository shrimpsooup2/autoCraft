package com.autocraft.behavior;

import com.autocraft.Bot;

/**
 * One layer of the bot's priority stack. Every tick the bot asks behaviors from highest to lowest
 * priority; the first to return true owns the tick and the rest are skipped. Behaviors keep their own
 * state between ticks and must tolerate being skipped (preempted) at any time.
 */
public interface Behavior {
	String name();

	/** Do this tick's work. Return true to claim the tick, false to let lower priorities run. */
	boolean tick(Bot bot);

	/** Forget all state (job started or stopped). */
	default void reset() {}
}
