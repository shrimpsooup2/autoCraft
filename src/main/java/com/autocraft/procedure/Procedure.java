package com.autocraft.procedure;

import com.autocraft.Bot;
import com.autocraft.goal.Requirement.Dim;
import java.util.Map;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;

/**
 * A way to get items that isn't mining, crafting, smelting or hunting: using a tool on a block or animal (honey,
 * shearing, milking, stripping logs), letting the world do the work (copper oxidizing, concrete hardening,
 * chickens laying), or a whole expedition (trial chamber vaults, structure chests). The recipe planner picks a
 * procedure when it's the cheapest route and obtains its {@link #needs} first.
 */
public interface Procedure {
	/** True if this procedure produces {@code item}. */
	boolean makes(Item item);

	/** Items to carry before starting (absolute counts: tools 1, containers per item), for {@code total} items. */
	Map<Item, Integer> needs(Item item, int total);

	/** Planner effort per item, comparable with gathering (~8-20) and crafting (~1 per step). */
	double cost(Item item);

	/** Begin producing until {@code total} of {@code item} are carried. */
	Task start(Item item, int total);

	/** How the item is got, for the item picker's tooltip. */
	default String label() {
		return "Special procedure (tools on blocks/animals, waiting, trial chambers)";
	}

	/** One running procedure. */
	interface Task {
		enum Status {
			RUNNING,
			DONE,
			FAILED,
			/** Started but needs time (copper oxidizing): come back later, do other work meanwhile. */
			WAITING
		}

		Status tick(Bot bot);

		/** The dimension this needs to be in (the Nether for a fortress); null = wherever we are. */
		default @Nullable Dim wantsDimension() {
			return null;
		}
	}
}
