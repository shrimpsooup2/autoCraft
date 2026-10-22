package com.autocraft.ui;

import com.autocraft.goal.Requirement;
import com.autocraft.goal.Resources;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.Nullable;

/** Everything a job can ask for — minable blocks and drops, craftable/smeltable items — for the picker. */
public final class ItemCatalog {
	/**
	 * @param key     what {@code Resources.forJob} takes
	 * @param icon    item shown in the grid
	 * @param name    display name (searched)
	 * @param crafted made by recipes rather than mined
	 */
	public record Entry(String key, Item icon, String name, boolean crafted) {}

	private static @Nullable List<Entry> all;

	private ItemCatalog() {}

	/** Built on first use (needs a joined world: tags and the recipe book). */
	public static List<Entry> all() {
		if (all != null) return all;
		List<Entry> out = new ArrayList<>();
		out.add(new Entry("log", Items.OAK_LOG, "Any log", false)); // the one named group without an item of its own
		for (Item item : BuiltInRegistries.ITEM) {
			if (item == Items.AIR) continue;
			String key = BuiltInRegistries.ITEM.getKey(item).getPath();
			Requirement r = Resources.forJob(key, 1);
			// a name can resolve to a different item (the "glowstone" resource is the dust): list the item that counts
			if (r == null || !r.counts().test(item.getDefaultInstance())) continue;
			out.add(new Entry(key, item, item.getDefaultInstance().getHoverName().getString(), r.isCrafted()));
		}
		all = List.copyOf(out);
		return all;
	}

	/** Entries whose name or id contains {@code query}; names starting with it first. */
	public static List<Entry> search(String query) {
		String q = query.trim().toLowerCase(Locale.ROOT);
		if (q.isEmpty()) return all();
		String id = q.replace(' ', '_');
		List<Entry> out = new ArrayList<>();
		for (Entry e : all()) {
			if (e.name.toLowerCase(Locale.ROOT).contains(q) || e.key.contains(id)) out.add(e);
		}
		out.sort(Comparator.comparingInt(e -> e.name.toLowerCase(Locale.ROOT).startsWith(q) || e.key.startsWith(id) ? 0 : 1));
		return out;
	}

	/** Icon for a requirement: its product, the item named like it, or the first source block. */
	public static Item iconOf(Requirement r) {
		if (r.product() != null) return r.product();
		Item named = BuiltInRegistries.ITEM.getOptional(Identifier.withDefaultNamespace(r.name())).orElse(Items.AIR);
		if (named != Items.AIR && r.counts().test(named.getDefaultInstance())) return named;
		for (Block b : r.sources()) {
			if (b.asItem() != Items.AIR) return b.asItem();
		}
		return Items.BARRIER;
	}
}
