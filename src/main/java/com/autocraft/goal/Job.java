package com.autocraft.goal;

import com.autocraft.control.InvUtil;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** The user's order: a list of requirements plus what has already been deposited at the base. */
public final class Job {
	private final List<Requirement> requirements;
	private final Map<String, Integer> stored = new HashMap<>();
	/** Requirements given up on (nothing can produce them); the rest of the job continues. */
	private final Set<String> skipped = new HashSet<>();
	/** Requirements waiting on the world (copper oxidizing): attended once nothing else is left. */
	private final Set<String> deferred = new HashSet<>();

	public Job(List<Requirement> requirements) {
		this.requirements = List.copyOf(requirements);
	}

	public List<Requirement> requirements() {
		return requirements;
	}

	/** Items gathered so far: carried (including shulkers/bundles) plus deposited. */
	public int have(Player p, Requirement r) {
		return InvUtil.countIncludingContainers(p, r.counts()) + stored.getOrDefault(r.name(), 0);
	}

	public int missing(Player p, Requirement r) {
		return Math.max(0, r.amount() - have(p, r));
	}

	public boolean isComplete(Player p) {
		return nextUnmet(p) == null;
	}

	/** First requirement still short (and not skipped; deferred ones only when nothing else is), or null when done. */
	public Requirement nextUnmet(Player p) {
		Requirement firstDeferred = null;
		for (Requirement r : requirements) {
			if (skipped.contains(r.name()) || missing(p, r) <= 0) continue;
			if (!deferred.contains(r.name())) return r;
			if (firstDeferred == null) firstDeferred = r;
		}
		return firstDeferred;
	}

	public void skip(Requirement r) {
		skipped.add(r.name());
	}

	/** Put {@code r} behind everything else still to do. */
	public void defer(Requirement r) {
		deferred.add(r.name());
	}

	public boolean isSkipped(Requirement r) {
		return skipped.contains(r.name());
	}

	/** Deposited counts per requirement name (for saving progress). */
	public Map<String, Integer> stored() {
		return Map.copyOf(stored);
	}

	public Set<String> skippedNames() {
		return Set.copyOf(skipped);
	}

	/** Put saved progress back (after a relog). */
	public void restore(Map<String, Integer> savedStored, Collection<String> savedSkipped) {
		stored.putAll(savedStored);
		skipped.addAll(savedSkipped);
	}

	/** Requirements that would be counted by an item stack, used when depositing. */
	public List<Requirement> matching(ItemStack stack) {
		List<Requirement> out = new ArrayList<>();
		for (Requirement r : requirements) {
			if (r.counts().test(stack)) out.add(r);
		}
		return out;
	}

	public void addStored(Requirement r, int count) {
		stored.merge(r.name(), count, Integer::sum);
	}

	public String progress(Player p) {
		StringJoiner j = new StringJoiner(", ");
		for (Requirement r : requirements) {
			j.add(r.name() + " " + Math.min(have(p, r), r.amount()) + "/" + r.amount() + (skipped.contains(r.name()) ? " (skipped)" : ""));
		}
		return j.toString();
	}
}
