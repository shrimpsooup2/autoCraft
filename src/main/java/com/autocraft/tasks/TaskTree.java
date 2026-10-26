package com.autocraft.tasks;

import com.autocraft.Bases;
import com.autocraft.Bot;
import com.autocraft.Chat;
import com.autocraft.Settings;
import com.autocraft.TaskSave;
import com.autocraft.build.BaseWithdraw;
import com.autocraft.build.BuildOrder;
import com.autocraft.build.BuildPlan;
import com.autocraft.build.MaterialAdvisor;
import com.autocraft.build.MaterialSettings;
import com.autocraft.build.SiteFinder;
import com.autocraft.craft.Planner;
import com.autocraft.craft.Stations;
import com.autocraft.goal.Job;
import com.autocraft.goal.Requirement;
import com.autocraft.goal.Resources;
import com.autocraft.schematic.Schematic;
import com.autocraft.schematic.SchematicLibrary;
import com.autocraft.world.ChestMemory;
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

	private static @Nullable Map<String, List<TaskNode>> worlds;
	private static @Nullable TaskNode running;
	private static boolean active;
	/** Running just this task and its subtasks; null = the whole tree. */
	private static @Nullable TaskNode scope;
	/** The world the run was started in ({@link WorldKey}): it only runs there, and resumes there after a relog. */
	private static String runWorld = "";
	/** Ticks since the running task's plan steps were refreshed. */
	private static int planAge;
	private static final int PLAN_REFRESH_TICKS = 20 * 3;

	private TaskTree() {}

	public static List<TaskNode> roots() {
		if (worlds == null) worlds = load();
		return worlds.computeIfAbsent(WorldKey.current(), k -> new ArrayList<>());
	}

	/** A run is going in this world (one started in another world waits there). */
	public static boolean active() {
		return active && WorldKey.current().equals(runWorld);
	}

	/** True while the tree has work left after the current one (so finishing a job doesn't log off). */
	public static boolean hasMore() {
		return active() && next(runList(), running) != null;
	}

	/** The task running right now in this world (a job, fetch or build the bot is doing), or null. */
	public static @Nullable TaskNode running() {
		return active() ? running : null;
	}

	/** What a run works through: the whole tree, or the one task it was started on. */
	private static List<TaskNode> runList() {
		return scope == null ? roots() : List.of(scope);
	}

	/** The task a run starts next (while running: the one after the current task), or null. */
	public static @Nullable TaskNode upNext() {
		return active() ? next(runList(), running) : next(roots());
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
		list.removeIf(n -> n.kind != TaskNode.Kind.STEP && n.state == TaskNode.State.DONE && n.allChildrenDone()); // plans stay whole
		for (TaskNode n : list) clearDone(n.children);
	}

	/**
	 * Break a task down. A collect task gets the bot's own plan as steps (see {@link #plan}); expanding it again
	 * refreshes them. A build gets its bill of materials as collect subtasks (mark some "from base", expand others).
	 * False when there's nothing to break down.
	 */
	public static boolean expand(TaskNode node) {
		return switch (node.kind) {
			case COLLECT -> (node.children.isEmpty() || node.hasPlan()) && plan(node);
			case BUILD -> {
				if (!node.children.isEmpty()) yield false;
				Map<Item, Integer> parts = bill(node.schematic);
				parts.forEach((item, n) -> node.children.add(TaskNode.collect(BuiltInRegistries.ITEM.getKey(item).getPath(), n)));
				node.expanded = true;
				save();
				yield !parts.isEmpty();
			}
			case GROUP, FETCH, STEP -> false;
		};
	}

	/**
	 * Put the planner's plan for a collect task under it: what it would mine, craft or smelt, the pickaxe it makes
	 * before mining, crafting table, furnace and fuel — the same choices the running job makes, against what's
	 * carried now (diamond: mine diamond ore, after an iron pickaxe from smelted iron, after a stone pickaxe...).
	 * Folding the user did is kept; the file is written only when the plan changed. False when the item is unknown
	 * or there's no world.
	 */
	public static boolean plan(TaskNode node) {
		Bot bot = Bot.get();
		Requirement r = Resources.forJob(node.item, node.amount);
		if (r == null || bot.player() == null) return false;
		Planner.PlanNode root = Stations.planner(bot, r.counts()).explain(r);
		String before = node.how + signature(node.children);
		Map<String, Boolean> folds = new HashMap<>();
		collectFolds(node.children, "", folds);
		node.how = root.how();
		node.children.clear();
		for (Planner.PlanNode part : root.parts()) node.children.add(step(part, "", folds));
		if (!before.equals(node.how + signature(node.children))) save();
		return true;
	}

	private static String signature(List<TaskNode> nodes) {
		StringBuilder sb = new StringBuilder();
		for (TaskNode n : nodes) sb.append('[').append(n.title).append('|').append(n.how).append('|').append(n.state).append(signature(n.children)).append(']');
		return sb.toString();
	}

	private static TaskNode step(Planner.PlanNode p, String parentPath, Map<String, Boolean> folds) {
		String path = parentPath + "/" + p.key();
		TaskNode n = new TaskNode(p.amount() + " " + p.name(), TaskNode.Kind.STEP);
		n.item = p.key();
		n.amount = p.amount();
		n.how = p.how();
		n.state = p.have() >= p.amount() ? TaskNode.State.DONE : TaskNode.State.PENDING;
		n.expanded = folds.getOrDefault(path, true);
		for (Planner.PlanNode part : p.parts()) n.children.add(step(part, path, folds));
		return n;
	}

	private static void collectFolds(List<TaskNode> nodes, String parentPath, Map<String, Boolean> out) {
		for (TaskNode n : nodes) {
			if (n.kind != TaskNode.Kind.STEP) continue;
			String path = parentPath + "/" + n.item;
			out.put(path, n.expanded);
			collectFolds(n.children, path, out);
		}
	}

	private static void markDone(List<TaskNode> nodes) {
		for (TaskNode n : nodes) {
			if (n.kind != TaskNode.Kind.STEP) continue;
			n.state = TaskNode.State.DONE;
			markDone(n.children);
		}
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

	/** Run the whole tree ({@code only} null), or just {@code only} and its subtasks. */
	public static void start(@Nullable TaskNode only) {
		if (active()) return;
		if (running != null) { // a run left behind in another world: it no longer counts as running there
			running.state = TaskNode.State.PENDING;
			running = null;
		}
		scope = only;
		if (next(runList()) == null) {
			Chat.info(only == null ? "Nothing left to do in the task tree." : "\"" + only.title + "\" is already done.");
			scope = null;
			active = false;
			return;
		}
		active = true;
		runWorld = WorldKey.current();
		Chat.info(only == null ? "Running the task tree." : "Running \"" + only.title + "\".");
	}

	public static void stop() {
		if (!active()) return;
		active = false;
		scope = null;
		if (running != null) {
			running.state = TaskNode.State.PENDING;
			running = null;
			Bot.get().stop("task tree stopped");
		}
		save();
	}

	/** The bot finished a job or build ("job complete" / "build complete"): the running task is done. */
	public static void completed() {
		if (running == null || !active()) return; // a job in another world finished, not this run's task
		running.state = TaskNode.State.DONE;
		markDone(running.children);
		running = null;
		save();
	}

	/** Every client tick: notice the current task stopping and start the next. */
	public static void tick(Minecraft mc) {
		if (!active || mc.player == null || mc.level == null) return;
		// another world (or the same one right after joining, before the bot's task is restored): wait
		if (!WorldKey.current().equals(runWorld) || TaskSave.restorePending()) return;
		Bot bot = Bot.get();
		if (running != null) {
			if (bot.isRunning()) {
				// the plan under the running task follows the bot's progress (steps done, the next one, a changed route)
				if (running.hasPlan() && ++planAge >= PLAN_REFRESH_TICKS) {
					planAge = 0;
					plan(running);
				}
				return;
			}
			// stopped without finishing (stopped by hand, died, player nearby...): wait for the user
			active = false;
			scope = null;
			running.state = TaskNode.State.PENDING;
			running = null;
			save();
			Chat.warn("Task tree paused (" + bot.lastStop() + "). Press Run to continue.");
			return;
		}
		if (bot.isRunning()) return; // something else is going on (a manual job): wait for it
		if (scope != null && siblings(scope) == null) { // the task it was running was deleted
			active = false;
			scope = null;
			return;
		}
		TaskNode next = next(runList());
		if (next == null) {
			active = false;
			Chat.info(scope == null ? "Task tree finished." : "Finished \"" + scope.title + "\".");
			scope = null;
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
			if (n.children.isEmpty()) plan(n); // the tree shows how the bot goes about it while it runs
			bot.start(new Job(List.of(r)));
			return true;
		}
		if (n.kind == TaskNode.Kind.FETCH) {
			Item item = item(n.item);
			if (item == null) {
				Chat.warn("Task tree: unknown item \"" + n.item + "\".");
				return false;
			}
			Bases.Base base = n.base.isBlank() ? holderOf(bot, item) : Bases.named(n.base);
			if (base == null) {
				Chat.warn(n.base.isBlank() ? "Task tree: no base set (!ac base [name])." : "Task tree: no base named \"" + n.base + "\".");
				return false;
			}
			bot.startFetch(new Bot.FetchOrder(base.pos(), base.dim(), item, n.amount));
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

	/** The nearest base whose chests are known to hold {@code item} (this dimension first), else the nearest base. */
	private static Bases.@Nullable Base holderOf(Bot bot, Item item) {
		BlockPos here = bot.player().blockPosition();
		Bases.Base best = null;
		double bestCost = Double.MAX_VALUE;
		for (Bases.Base b : Bases.all()) {
			if (ChestMemory.count(b.pos(), BaseWithdraw.BASE_RADIUS, b.dim(), s -> s.is(item)) <= 0) continue;
			double cost = b.dim() == bot.dimension() ? b.pos().distSqr(here) : Double.MAX_VALUE / 2;
			if (cost < bestCost) {
				bestCost = cost;
				best = b;
			}
		}
		return best != null ? best : Bases.nearest(bot.dimension(), here);
	}

	/** First unfinished task whose subtasks are all done (depth first, in order). */
	private static @Nullable TaskNode next(List<TaskNode> nodes) {
		return next(nodes, null);
	}

	/** As {@link #next(List)}, counting {@code finishing} as done already. */
	private static @Nullable TaskNode next(List<TaskNode> nodes, @Nullable TaskNode finishing) {
		for (TaskNode n : nodes) {
			if (n.state == TaskNode.State.DONE || n == finishing || n.kind == TaskNode.Kind.STEP) continue;
			boolean childrenDone = n.children.stream().allMatch(c -> c.kind == TaskNode.Kind.STEP || c.state == TaskNode.State.DONE || c == finishing);
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
			// fields added later are missing from older files
			if (n.base == null) n.base = "";
			if (n.item == null) n.item = "";
			if (n.schematic == null) n.schematic = "";
			if (n.context == null) n.context = "";
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
