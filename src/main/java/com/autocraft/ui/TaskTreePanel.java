package com.autocraft.ui;

import com.autocraft.Bases;
import com.autocraft.build.Placement;
import com.autocraft.goal.Resources;
import com.autocraft.schematic.SchematicLibrary;
import com.autocraft.tasks.TaskNode;
import com.autocraft.tasks.TaskTree;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The panel's Tasks tab: a tree of big tasks and their subtasks. Left: the tree (click a row to select it, click
 * its +/- to fold it, scroll), add buttons (a child of the selection, or a top-level task with nothing selected).
 * Right: edit the selected task — title, item and amount or schematic, context notes — break it into parts
 * (Expand), reorder, delete; run or stop the whole tree.
 */
final class TaskTreePanel {
	private static final int LINE = 11;
	private static final int INDENT = 8;

	private static @Nullable TaskNode selected;
	private static int scroll;

	private final Font font;
	private final int left;
	private final int rightX;
	private final int colW;
	private final int contentY;
	private final int bottomY;
	private final Consumer<AbstractWidget> add;
	private final Runnable rebuild;
	private @Nullable Button runButton;

	TaskTreePanel(Font font, int left, int rightX, int colW, int contentY, int bottomY, Consumer<AbstractWidget> add, Runnable rebuild) {
		this.font = font;
		this.left = left;
		this.rightX = rightX;
		this.colW = colW;
		this.contentY = contentY;
		this.bottomY = bottomY;
		this.add = add;
		this.rebuild = rebuild;
	}

	private int rowsTop() {
		return contentY + 14;
	}

	private int visibleRows() {
		return Math.max(1, (bottomY - 4 - rowsTop()) / LINE);
	}

	void init() {
		if (selected != null && TaskTree.siblings(selected) == null) selected = null; // deleted or another world
		int third = (colW - 8) / 3;
		button("+ Group", "A task made of subtasks", left + 8, third, () -> addNode(new TaskNode("New task", TaskNode.Kind.GROUP)));
		button("+ Collect", "Collect an amount of an item (mined, crafted, hunted or special)", left + 12 + third, third,
				() -> addNode(TaskNode.collect("cobblestone", 64)));
		button("+ Build", "Build a schematic (here: a spot is found when it runs; or use From placement)", left + 16 + 2 * third, third, () -> {
			TaskNode n = new TaskNode("Build", TaskNode.Kind.BUILD);
			n.schematic = "starter_base";
			n.title = "Build starter_base";
			addNode(n);
		});
		int half = colW / 2 - 2;
		runButton = new Button.Builder(Component.empty(), b -> {
			if (TaskTree.active()) TaskTree.stop();
			else TaskTree.start();
			tick();
		}).tooltip(Tooltip.create(Component.literal("Subtasks run before their parent, one job or build at a time"))).bounds(rightX, bottomY + 2, half, 18).build();
		add.accept(runButton);
		add.accept(Button.builder(Component.literal("Clear done"), b -> {
			TaskTree.clearDone();
			rebuild.run();
		}).bounds(rightX + half + 4, bottomY + 2, half, 18).build());
		if (selected != null) initEditor(selected);
		tick();
	}

	private void button(String label, String tip, int x, int w, Runnable action) {
		add.accept(Button.builder(Component.literal(label), b -> action.run()).tooltip(Tooltip.create(Component.literal(tip))).bounds(x, bottomY + 2, w, 18).build());
	}

	private void addNode(TaskNode node) {
		TaskTree.add(selected, node);
		selected = node;
		rebuild.run();
	}

	private void initEditor(TaskNode n) {
		EditBox title = box(rightX, contentY, colW, 128, n.title, "Title");
		title.setResponder(s -> {
			n.title = s;
			TaskTree.save();
		});
		int y = contentY + 20;
		if (n.kind == TaskNode.Kind.COLLECT || n.kind == TaskNode.Kind.FETCH) {
			EditBox item = box(rightX, y, colW - 52, 64, n.item, "Item id, e.g. iron or piston");
			item.setResponder(s -> {
				n.item = s.trim().toLowerCase();
				TaskTree.save();
			});
			EditBox amount = box(rightX + colW - 48, y, 48, 6, Integer.toString(n.amount), "Amount");
			amount.setResponder(s -> {
				try {
					n.amount = Math.max(1, Integer.parseInt(s.trim()));
					TaskTree.save();
				} catch (NumberFormatException e) {
					// keep the last valid amount while typing
				}
			});
		} else if (n.kind == TaskNode.Kind.BUILD) {
			EditBox schematic = box(rightX, y, colW - 90, 64, n.schematic, "Schematic name");
			schematic.setResponder(s -> {
				n.schematic = s.trim();
				TaskTree.save();
			});
			add.accept(Button.builder(Component.literal("From placement"), b -> {
				Placement p = Placements.active();
				if (p == null) return;
				n.schematic = p.schematic().name();
				n.origin = new int[] {p.origin().getX(), p.origin().getY(), p.origin().getZ()};
				n.rotation = p.rotation().ordinal();
				n.mirror = p.mirror().ordinal();
				TaskTree.save();
				rebuild.run();
			}).tooltip(Tooltip.create(Component.literal("Use the schematic being placed in the world, at its position and rotation")))
					.bounds(rightX + colW - 86, y, 86, 16).build());
		}
		EditBox context = box(rightX, contentY + 40, colW, 256, n.context, "Context / notes for this task");
		context.setResponder(s -> {
			n.context = s;
			TaskTree.save();
		});
		int quarter = (colW - 12) / 4;
		int by = contentY + 60;
		add.accept(Button.builder(Component.literal("Expand"), b -> {
			if (TaskTree.expand(n)) rebuild.run();
		}).tooltip(Tooltip.create(Component.literal("Break it into parts: ingredients of a crafted item, materials of a build"))).bounds(rightX, by, quarter, 18).build());
		add.accept(Button.builder(Component.literal("Up"), b -> {
			TaskTree.move(n, -1);
			rebuild.run();
		}).bounds(rightX + quarter + 4, by, quarter, 18).build());
		add.accept(Button.builder(Component.literal("Down"), b -> {
			TaskTree.move(n, 1);
			rebuild.run();
		}).bounds(rightX + 2 * (quarter + 4), by, quarter, 18).build());
		add.accept(Button.builder(Component.literal("Delete"), b -> {
			TaskTree.remove(n);
			selected = null;
			rebuild.run();
		}).bounds(rightX + 3 * (quarter + 4), by, quarter, 18).build());
		if (n.kind == TaskNode.Kind.COLLECT || n.kind == TaskNode.Kind.FETCH) {
			int half = colW / 2 - 2;
			add.accept(Button.builder(Component.literal(n.kind == TaskNode.Kind.FETCH ? "Source: from base" : "Source: gather"), b -> {
				n.kind = n.kind == TaskNode.Kind.FETCH ? TaskNode.Kind.COLLECT : TaskNode.Kind.FETCH;
				TaskTree.save();
				rebuild.run();
			}).tooltip(Tooltip.create(Component.literal("Gather it (mine, craft, hunt...) or take it out of a base's chests")))
					.bounds(rightX, by + 22, half, 18).build());
			if (n.kind == TaskNode.Kind.FETCH) {
				EditBox base = box(rightX + half + 4, by + 23, half, 32, n.base, "Base (empty: nearest with it)");
				base.setResponder(s -> {
					n.base = s.trim();
					TaskTree.save();
				});
			}
		}
	}

	private EditBox box(int x, int y, int w, int maxLength, String value, String hint) {
		EditBox box = new EditBox(font, x, y, w, 16, Component.literal(hint));
		box.setMaxLength(maxLength);
		box.setValue(value);
		box.setHint(Component.literal(hint).withStyle(ChatFormatting.DARK_GRAY));
		add.accept(box);
		return box;
	}

	void tick() {
		if (runButton != null) runButton.setMessage(Component.literal(TaskTree.active() ? "Stop tasks" : "Run tasks"));
	}

	void extract(GuiGraphicsExtractor g) {
		int x = left + 8;
		g.text(font, "Tasks" + (TaskTree.active() ? " (running)" : ""), x, contentY + 2, Widgets.WHITE);
		List<TaskTree.Row> rows = TaskTree.rows();
		if (rows.isEmpty()) {
			g.textWithWordWrap(font, Component.literal("No tasks yet. Add a group for a big goal, then collect/build subtasks under it;"
					+ " Expand breaks a task into its ingredients or materials."), x, rowsTop(), colW, Widgets.GRAY);
		}
		scroll = Math.max(0, Math.min(scroll, rows.size() - visibleRows()));
		for (int i = 0; i < visibleRows() && scroll + i < rows.size(); i++) {
			TaskTree.Row r = rows.get(scroll + i);
			TaskNode n = r.node();
			int y = rowsTop() + i * LINE;
			if (n == selected) g.fill(x - 2, y - 1, x + colW, y + LINE - 1, 0x60FFFFFF);
			int tx = x + r.depth() * INDENT;
			if (!n.children.isEmpty()) g.text(font, n.expanded ? "-" : "+", tx, y, Widgets.GRAY);
			int color = switch (n.state) {
				case DONE -> 0xFF7FD67F;
				case ACTIVE -> 0xFFFFD27F;
				case PENDING -> Widgets.WHITE;
			};
			String label = n.title + switch (n.kind) {
				case GROUP -> "";
				case COLLECT -> " [collect]";
				case FETCH -> " [from base]";
				case BUILD -> " [build]";
			};
			g.text(font, font.plainSubstrByWidth(label, colW - (tx - x) - 8), tx + 8, y, color);
		}
		if (selected != null) extractDetails(g, selected);
	}

	private void extractDetails(GuiGraphicsExtractor g, TaskNode n) {
		int y = contentY + 104;
		String state = switch (n.state) {
			case DONE -> "done";
			case ACTIVE -> "running";
			case PENDING -> n.allChildrenDone() ? "waiting" : "waiting for its subtasks";
		};
		g.text(font, "Status: " + state, rightX, y, Widgets.GRAY);
		y += LINE;
		if (n.kind == TaskNode.Kind.FETCH) {
			if (TaskTree.item(n.item) == null) {
				g.text(font, "Unknown item: \"" + n.item + "\"", rightX, y, 0xFFFF7F7F);
			} else if (!n.base.isBlank() && Bases.named(n.base) == null) {
				g.text(font, "No base named \"" + n.base + "\" (bases: " + Bases.all().stream().map(Bases.Base::name).toList() + ")", rightX, y, 0xFFFF7F7F);
			} else {
				g.text(font, font.plainSubstrByWidth("From " + (n.base.isBlank() ? "the nearest base that has it" : "base \"" + n.base + "\""), colW), rightX,
						y, Widgets.GRAY);
			}
		} else if (n.kind == TaskNode.Kind.COLLECT && Resources.forJob(n.item, 1) == null) {
			g.text(font, "Unknown item: \"" + n.item + "\"", rightX, y, 0xFFFF7F7F);
		} else if (n.kind == TaskNode.Kind.BUILD) {
			if (SchematicLibrary.get(n.schematic) == null) {
				g.text(font, "No schematic named \"" + n.schematic + "\"", rightX, y, 0xFFFF7F7F);
			} else {
				g.text(font, font.plainSubstrByWidth(n.origin == null ? "Spot: found when it runs" : "At " + n.origin[0] + " " + n.origin[1] + " " + n.origin[2]
						+ (n.rotation == 0 ? "" : ", rotated " + n.rotation * 90 + "°"), colW), rightX, y, Widgets.GRAY);
			}
		}
	}

	boolean click(double mx, double my) {
		int x = left + 8;
		if (mx < x - 2 || mx >= x + colW || my < rowsTop() || my >= rowsTop() + visibleRows() * LINE) return false;
		int index = scroll + (int) ((my - rowsTop()) / LINE);
		List<TaskTree.Row> rows = TaskTree.rows();
		if (index >= rows.size()) {
			selected = null; // empty space: next additions go to the top level
			rebuild.run();
			return true;
		}
		TaskTree.Row r = rows.get(index);
		int tx = x + r.depth() * INDENT;
		if (!r.node().children.isEmpty() && mx >= tx && mx < tx + 8) {
			r.node().expanded = !r.node().expanded;
			TaskTree.save();
		} else {
			selected = r.node();
		}
		rebuild.run();
		return true;
	}

	boolean scroll(double mx, double my, int dir) {
		if (mx < left + 6 || mx >= left + 8 + colW || my < rowsTop() || my >= bottomY) return false;
		scroll = Math.max(0, scroll - dir);
		return true;
	}
}
