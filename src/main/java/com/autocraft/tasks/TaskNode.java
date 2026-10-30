package com.autocraft.tasks;

import com.autocraft.goal.Destination;
import com.autocraft.goal.Ending;
import com.autocraft.goal.Requirement.Dim;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One task in the tree: a group of subtasks, "collect N of an item" (any job target: mined, crafted, hunted,
 * special), "get N of an item from a base" (a named base, or the nearest one known to hold it), "build a
 * schematic" (at a fixed spot taken from a placement, or a spot found when it runs), "go to" coordinates, a base or
 * the nearest structure of a kind, or a plan step. Subtasks run before their parent. {@code context} is the user's
 * note on why/how. Every task but a plan step may end at a base ({@link #endBase}, {@link #deposit}).
 *
 * <p>Plan steps ({@link Kind#STEP}) are the bot's own plan for a collect task, from the planner (mine, craft, smelt,
 * the pickaxe to make first...): they show how it will get the item and how far along it is, and never run on
 * their own — the collect task above them runs that plan as one job.
 */
public final class TaskNode {
	public enum Kind { GROUP, COLLECT, FETCH, BUILD, GOTO, STEP }

	public enum State { PENDING, ACTIVE, DONE }

	/** What a GOTO task heads for. */
	public enum Target { COORDS, BASE, STRUCTURE }

	public String title;
	public Kind kind;
	public String item = "";
	public int amount = 1;
	/** FETCH: base name, empty = the nearest base whose chests hold the item; GOTO to a base: that base. */
	public String base = "";
	public String schematic = "";
	/** Build origin from a placement; null = find a spot when it runs. */
	public int @Nullable [] origin;
	public int rotation;
	public int mirror;
	public Target target = Target.COORDS;
	/** GOTO coordinates; {@code y} null = any height the bot can stand at on that column. */
	public int x;
	public @Nullable Integer y;
	public int z;
	/** GOTO coordinates' dimension; null = the one the bot is in when the task runs. */
	public @Nullable Dim dimension;
	/** GOTO a structure: its kind (a {@link com.autocraft.loot.LootSites#structures} name). */
	public String structure = "village";
	/** The base the task ends at ("" = it ends where it is) and what it does there. */
	public String endBase = "";
	public Ending.Deposit deposit = Ending.Deposit.NONE;
	public String context = "";
	/** How the bot gets it, from the planner ("mine diamond ore", "craft", "smelt", "carried"); empty until planned. */
	public String how = "";
	public boolean expanded = true;
	public State state = State.PENDING;
	public List<TaskNode> children = new ArrayList<>();

	public TaskNode(String title, Kind kind) {
		this.title = title;
		this.kind = kind;
	}

	/**
	 * For Gson: with a no-argument constructor it runs the field defaults above and then fills in what the file has,
	 * so a field added later ({@link #how}) is "" in older saves, not null (without one Gson skips the initializers).
	 */
	private TaskNode() {
		this("", Kind.GROUP);
	}

	public static TaskNode collect(String item, int amount) {
		TaskNode n = new TaskNode(amount + " " + item.replace('_', ' '), Kind.COLLECT);
		n.item = item;
		n.amount = amount;
		return n;
	}

	/** A go-to task for {@code d} (its coordinates' dimension kept). */
	public static TaskNode goTo(Destination d) {
		TaskNode n = new TaskNode("", Kind.GOTO);
		switch (d.kind()) {
			case COORDS -> {
				n.target = Target.COORDS;
				n.x = d.x();
				n.y = d.y();
				n.z = d.z();
				n.dimension = d.dim();
			}
			case BASE -> {
				n.target = Target.BASE;
				n.base = d.name();
			}
			case STRUCTURE -> {
				n.target = Target.STRUCTURE;
				n.structure = d.name();
			}
		}
		n.title = n.gotoTitle();
		return n;
	}

	/** "Go to 100 64 -20", "Go to base "home"", "Go to a village": the title a go-to task gets until the user names it. */
	public String gotoTitle() {
		return "Go to " + switch (target) {
			case COORDS -> x + (y == null ? "" : " " + y) + " " + z + (dimension == null ? "" : " (" + dimension.name().toLowerCase() + ")");
			case BASE -> "base \"" + base + "\"";
			case STRUCTURE -> "a " + structure;
		};
	}

	/** This go-to task is the one heading for {@code d} (resuming it after a relog). */
	public boolean matches(Destination d) {
		if (kind != Kind.GOTO) return false;
		return switch (d.kind()) {
			case COORDS -> target == Target.COORDS && x == d.x() && z == d.z() && Objects.equals(y, d.y()) && (dimension == null || dimension == d.dim());
			case BASE -> target == Target.BASE && base.equalsIgnoreCase(d.name());
			case STRUCTURE -> target == Target.STRUCTURE && structure.equalsIgnoreCase(d.name());
		};
	}

	/** Where it ends: null = where it is. */
	public @Nullable Ending ending() {
		return endBase.isBlank() ? null : new Ending(endBase, deposit);
	}

	public void setEnding(@Nullable Ending e) {
		endBase = e == null ? "" : e.base();
		deposit = e == null ? Ending.Deposit.NONE : e.deposit();
	}

	/** The subtasks that must finish first (plan steps don't count: they're carried out by this task itself). */
	public boolean allChildrenDone() {
		return children.stream().allMatch(c -> c.kind == Kind.STEP || c.state == State.DONE);
	}

	/** Its children are the bot's plan (refreshed by Expand), not subtasks the user made. */
	public boolean hasPlan() {
		return !children.isEmpty() && children.stream().allMatch(c -> c.kind == Kind.STEP);
	}
}
