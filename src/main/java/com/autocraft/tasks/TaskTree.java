package com.autocraft.tasks;

import com.autocraft.Bot;
import com.autocraft.Chat;
import com.autocraft.Settings;
import com.autocraft.build.BuildOrder;
import com.autocraft.build.BuildPlan;
import com.autocraft.build.MaterialAdvisor;
import com.autocraft.build.MaterialSettings;
import com.autocraft.build.SiteFinder;
import com.autocraft.craft.RecipeBook;
import com.autocraft.goal.Job;
import com.autocraft.goal.Requirement;
import com.autocraft.goal.Resources;
import com.autocraft.schematic.Schematic;
import com.autocraft.schematic.SchematicLibrary;
import com.autocraft.world.WorldKey;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import org.jspecify.annotations.Nullable;

/**
 * The user's task tree (per world, saved to {@code config/autocraft/tasktree.json}) and its runner: subtasks
 * before their parent, one collect job or build at a time; when the bot finishes one ("job complete" / "build
 * complete") the next starts. Stopping the bot any other way pauses the tree.
 */
public final class TaskTree {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** A visible row of the tree view. */
	public record Row(TaskNode node, int depth) {}

	private static @Nullable Map<String, List<TaskNode>> worlds;
	private static @Nullable TaskNode running;
	private static boolean active;

	private TaskTree() {}

	public static List<TaskNode> roots() {
		if (worlds == null) worlds = load();
		return worlds.computeIfAbsent(WorldKey.current(), k -> new ArrayList<>());
	}

	public static boolean active() {
		return active;
	}

	/** True while the tree has work left after the current one (so finishing a job doesn't log off). */
	public static boolean hasMore() {
		return active && next(roots(), running) != null;
	}

	/** Visible rows: expanded nodes show their children, indented. */
	public static List<Row> rows() {
		List<Row> out = new ArrayList<>();
		flatten(roots(), 0, out);
		return out;
	}

	private static void flatten(List<TaskNode> nodes, int depth, List<Row> out) {
		for (TaskNode n : nodes) {
			out.add(new Row(n, depth));
			if (n.expanded) flatten(n.children, depth + 1, out);
		}
	}

	/** The list holding {@code node} (a parent's children or the roots), or null. */
	public static @Nullable List<TaskNode> siblings(TaskNode node) {
		return siblings(roots(), node);
	}

	private static @Nullable List<TaskNode> siblings(List<TaskNode> list, TaskNode node) {
		if (list.contains(node)) return list;
		for (TaskNode n : list) {
			List<TaskNode> found = siblings(n.children, node);
			if (found != null) return found;
		}
		return null;
	}

	public static void add(@Nullable TaskNode parent, TaskNode child) {
		if (parent == null) roots().add(child);
		else {
			parent.children.add(child);
			parent.expanded = true;
			if (parent.state == TaskNode.State.DONE) parent.state = TaskNode.State.PENDING;
		}
		save();
	}

	public static void remove(TaskNode node) {
		if (node == running) return; // stop the tree first
		List<TaskNode> list = siblings(node);
		if (list != null) list.remove(node);
		save();
	}

	public static void move(TaskNode node, int delta) {
		List<TaskNode> list = siblings(node);
		if (list == null) return;
		int i = list.indexOf(node), j = i + delta;
		if (j < 0 || j >= list.size()) return;
		list.remove(i);
		list.add(j, node);
		save();
	}

	public static void clearDone() {
		clearDone(roots());
		save();
	}

	private static void clearDone(List<TaskNode> list) {
		list.removeIf(n -> n.state == TaskNode.State.DONE && n.allChildrenDone());
		for (TaskNode n : list) clearDone(n.children);
	}

	/**
	 * Break a task into its parts as subtasks: a crafted/smelted/cut item into its ingredients (one recipe level),
	 * a build into its bill of materials. False when there's nothing to break down.
	 */
	public static boolean expand(TaskNode node) {
		if (!node.children.isEmpty()) return false;
		Map<Item, Integer> parts = switch (node.kind) {
			case COLLECT -> ingredients(item(node.item), node.amount);
			case BUILD -> bill(node.schematic);
			case GROUP -> Map.of();
		};
		if (parts.isEmpty()) return false;
		parts.forEach((item, n) -> node.children.add(TaskNode.collect(BuiltInRegistries.ITEM.getKey(item).getPath(), n)));
		node.expanded = true;
		save();
		return true;
	}

	private static Map<Item, Integer> ingredients(@Nullable Item item, int amount) {
		Map<Item, Integer> out = new LinkedHashMap<>();
		if (item == null) return out;
		RecipeBook book = RecipeBook.get();
		if (!book.crafting(item).isEmpty()) {
			RecipeBook.Recipe r = book.crafting(item).getFirst();
			int batches = (amount + r.count() - 1) / r.count();
			for (RecipeBook.Ingredient ing : new LinkedHashSet<>(r.key().values())) {
				out.merge(ing.options().getFirst(), r.amountOf(ing) * batches, Integer::sum);
			}
		} else if (!book.smelting(item).isEmpty()) {
			out.put(book.smelting(item).getFirst().smeltInput().options().getFirst(), amount);
		} else if (!book.cutting(item).isEmpty()) {
			RecipeBook.Cut c = book.cutting(item).getFirst();
			out.put(c.input().options().getFirst(), (amount + c.count() - 1) / c.count());
		}
		return out;
	}

	private static Map<Item, Integer> bill(String schematicName) {
		Map<Item, Integer> out = new LinkedHashMap<>();
		Schematic s = SchematicLibrary.get(schematicName);
		if (s == null) return out;
		for (Schematic.Cell c : s.cells()) {
			Item item = c.state().isAir() ? null : BuildPlan.itemFor(c.state());
			if (item != null && item != Items.AIR) out.merge(item, 1, Integer::sum);
		}
		return out;
	}

	public static @Nullable Item item(String id) {
		Identifier key = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
		Item item = key == null ? null : BuiltInRegistries.ITEM.getOptional(key).orElse(null);
		return item == Items.AIR ? null : item;
	}

	// ---- running --------------------------------------------------------------------------------

	public static void start() {
		if (active) return;
		if (next(roots()) == null) {
			Chat.info("Nothing left to do in the task tree.");
			return;
		}
		active = true;
		Chat.info("Running the task tree.");
	}

	public static void stop() {
		if (!active) return;
		active = false;
		if (running != null) {
			running.state = TaskNode.State.PENDING;
			running = null;
			Bot.get().stop("task tree stopped");
		}
		save();
	}

	/** The bot finished a job or build ("job complete" / "build complete"): the running task is done. */
	public static void completed() {
		if (running == null) return;
		running.state = TaskNode.State.DONE;
		running = null;
		save();
	}

	/** Every client tick: notice the current task stopping and start the next. */
	public static void tick(Minecraft mc) {
		if (!active || mc.player == null || mc.level == null) return;
		Bot bot = Bot.get();
		if (running != null) {
			if (bot.isRunning()) return;
			// stopped without finishing (stopped by hand, died, player nearby...): wait for the user
			active = false;
			running.state = TaskNode.State.PENDING;
			running = null;
			save();
			Chat.warn("Task tree paused (" + bot.lastStop() + "). Press Run to continue.");
			return;
		}
		if (bot.isRunning()) return; // something else is going on (a manual job): wait for it
		TaskNode next = next(roots());
		if (next == null) {
			active = false;
			Chat.info("Task tree finished.");
			if (Settings.get().logoffWhenDone) mc.schedule(() -> mc.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE));
			return;
		}
		if (next.kind == TaskNode.Kind.GROUP) { // its subtasks are done
			next.state = TaskNode.State.DONE;
			save();
			return;
		}
		if (!begin(bot, next)) {
			active = false;
			save();
			return;
		}
		next.state = TaskNode.State.ACTIVE;
		running = next;
		Chat.info("Task: " + next.title + (next.context.isBlank() ? "" : " — " + next.context));
		save();
	}

	private static boolean begin(Bot bot, TaskNode n) {
		if (n.kind == TaskNode.Kind.COLLECT) {
			Requirement r = Resources.forJob(n.item, n.amount);
			if (r == null) {
				Chat.warn("Task tree: don't know how to get \"" + n.item + "\"; fix the task and press Run.");
				return false;
			}
			bot.start(new Job(List.of(r)));
			return true;
		}
		Schematic s = SchematicLibrary.get(n.schematic);
		if (s == null) {
			Chat.warn("Task tree: no schematic \"" + n.schematic + "\".");
			return false;
		}
		BlockPos origin = n.origin != null ? new BlockPos(n.origin[0], n.origin[1], n.origin[2]) : SiteFinder.find(bot, s);
		if (origin == null) {
			Chat.warn("Task tree: no spot found for " + s.name() + " here; place it in the world and use \"From placement\".");
			return false;
		}
		bot.startBuild(new BuildOrder(s, origin, Rotation.values()[n.rotation], Mirror.values()[n.mirror],
				MaterialAdvisor.resolve(MaterialSettings.defaults(), s), BuildOrder.Purpose.USER));
		return true;
	}

	/** First unfinished task whose subtasks are all done (depth first, in order). */
	private static @Nullable TaskNode next(List<TaskNode> nodes) {
		return next(nodes, null);
	}

	/** As {@link #next(List)}, counting {@code finishing} as done already. */
	private static @Nullable TaskNode next(List<TaskNode> nodes, @Nullable TaskNode finishing) {
		for (TaskNode n : nodes) {
			if (n.state == TaskNode.State.DONE || n == finishing) continue;
			boolean childrenDone = n.children.stream().allMatch(c -> c.state == TaskNode.State.DONE || c == finishing);
			if (!childrenDone) return next(n.children, finishing);
			return n;
		}
		return null;
	}

	// ---- storage --------------------------------------------------------------------------------

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("autocraft").resolve("tasktree.json");
	}

	private static Map<String, List<TaskNode>> load() {
		Map<String, List<TaskNode>> out = new HashMap<>();
		try {
			if (Files.exists(file())) {
				Map<String, List<TaskNode>> loaded = GSON.fromJson(Files.readString(file()), new TypeToken<Map<String, List<TaskNode>>>() {}.getType());
				if (loaded != null) loaded.forEach((k, v) -> out.put(k, new ArrayList<>(v)));
			}
		} catch (IOException | JsonParseException e) {
			Chat.LOG.warn("Could not read tasktree.json", e);
		}
		// a task that was running when the game closed starts over
		out.values().forEach(TaskTree::resetActive);
		return out;
	}

	private static void resetActive(List<TaskNode> list) {
		for (TaskNode n : list) {
			if (n.state == TaskNode.State.ACTIVE) n.state = TaskNode.State.PENDING;
			if (n.children == null) n.children = new ArrayList<>();
			resetActive(n.children);
		}
	}

	public static void save() {
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(worlds));
		} catch (IOException e) {
			Chat.LOG.warn("Could not save tasktree.json", e);
		}
	}
}
