package com.autocraft.procedure;

import com.autocraft.loot.LootSites;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;

/** Every special procedure the planner can choose from; ones that keep failing for an item are dropped for the job. */
public final class Procedures {
	private static final int MAX_FAILURES = 2;

	private static @Nullable List<Procedure> all;
	private static final Map<Item, Integer> failures = new HashMap<>();

	private Procedures() {}

	private static List<Procedure> all() {
		if (all == null) {
			all = List.of(new BeeNestProcedure(), new AnimalProcedure(), new BlockWorkProcedure(), new OxidizeProcedure(), new TrialChamberProcedure(),
					new ScoopProcedure(), new ChestLootProcedure());
		}
		return all;
	}

	/** The cheapest procedure that makes {@code item}, or null (also once it failed too often this job). */
	public static @Nullable Procedure forItem(Item item) {
		if (failures.getOrDefault(item, 0) >= MAX_FAILURES) return null;
		Procedure best = null;
		for (Procedure p : all()) {
			if (p.makes(item) && (best == null || p.cost(item) < best.cost(item))) best = p;
		}
		return best;
	}

	/** The guarded structure the cheapest procedure for {@code item} works in, or null ({@link Procedure#dangerousSite}). */
	public static LootSites.@Nullable Site dangerousSite(Item item) {
		Procedure p = forItem(item);
		return p == null ? null : p.dangerousSite(item);
	}

	public static void failed(Item item) {
		failures.merge(item, 1, Integer::sum);
	}

	/** New job: give every procedure another chance. */
	public static void reset() {
		failures.clear();
	}
}
