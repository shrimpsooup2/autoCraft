package com.autocraft.goal;

import com.autocraft.control.InvUtil;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import net.minecraft.world.entity.player.Player;

/** The user's order: a list of requirements plus what has already been deposited at the base. */
public final class Job {
	private final List<Requirement> requirements;
	private final Map<String, Integer> stored = new HashMap<>();

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

	/** First requirement still short, or null when the job is done. */
	public Requirement nextUnmet(Player p) {
		for (Requirement r : requirements) {
			if (missing(p, r) > 0) return r;
		}
		return null;
	}

	/** Requirements that would be counted by an item stack, used when depositing. */
	public List<Requirement> matching(net.minecraft.world.item.ItemStack stack) {
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
			j.add(r.name() + " " + Math.min(have(p, r), r.amount()) + "/" + r.amount());
		}
		return j.toString();
	}
}
