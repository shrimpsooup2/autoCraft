package com.autocraft.tasks;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One task in the tree: a group of subtasks, "collect N of an item" (any job target: mined, crafted, hunted,
 * special), "get N of an item from a base" (a named base, or the nearest one known to hold it) or "build a
 * schematic" (at a fixed spot taken from a placement, or a spot found when it runs). Subtasks run before their
 * parent. {@code context} is the user's note on why/how.
 */
public final class TaskNode {
	public enum Kind { GROUP, COLLECT, FETCH, BUILD }

	public enum State { PENDING, ACTIVE, DONE }
	public String title;
	public Kind kind;
	public String item = "";
	public int amount = 1;
	/** FETCH: base name; empty = the nearest base whose chests hold the item. */
	public String base = "";
	public String schematic = "";
	/** Build origin from a placement; null = find a spot when it runs. */
	public int @Nullable [] origin;
	public int rotation;
	public int mirror;
	public String context = "";
	public boolean expanded = true;
	public State state = State.PENDING;
	public List<TaskNode> children = new ArrayList<>();

	public TaskNode(String title, Kind kind) {
		this.title = title;
		this.kind = kind;
	}

	public static TaskNode collect(String item, int amount) {
		TaskNode n = new TaskNode(amount + " " + item.replace('_', ' '), Kind.COLLECT);
		n.item = item;
		n.amount = amount;
		return n;
	}

	public boolean allChildrenDone() {
		return children.stream().allMatch(c -> c.state == State.DONE);
	}
}
