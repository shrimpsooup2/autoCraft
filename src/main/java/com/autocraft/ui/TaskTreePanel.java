package com.autocraft.ui;

import com.autocraft.Bases;
import com.autocraft.Bot;
import com.autocraft.build.Placement;
import com.autocraft.control.InvUtil;
import com.autocraft.goal.Job;
import com.autocraft.goal.Requirement;
import com.autocraft.goal.Resources;
import com.autocraft.schematic.SchematicLibrary;
import com.autocraft.tasks.TaskNode;
import com.autocraft.tasks.TaskTree;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * The panel's Tasks tab: everything the bot is asked to do, as one tree. Left: the tree — item icons, a status
 * pip per task (done / running / next / waiting its turn / waiting for its subtasks), fold arrows, guide lines
 * from each subtask to its parent, progress bars; click a row to edit it, empty space to deselect. Right: "Add
 * items" — search everything the bot can get, click to add it as a collect task (under the selected task, else
 * at the top; adding the same item again raises the amount) — or "Edit" the selected task. Run all works
 * through the tree (subtasks before their parent); Run selected does only that task and its subtasks.
 */
final class TaskTreePanel {
	private enum Mode { ADD, EDIT }

	private static final int ROW = 16;
	private static final int LINE = 11;
	private static final int INDENT = 10;
	private static final int SLOT = 18;
	private static final int DONE_COLOR = 0xFF6FD66F;
	private static final int ACTIVE_COLOR = 0xFFFFC94F;
	private static final int NEXT_COLOR = 0xFFFFFFFF;
	private static final int READY_COLOR = 0xFF9A9A9A;
	private static final int BLOCKED_COLOR = 0xFF5A5A5A;
	private static final int GUIDE_COLOR = 0xFF555555;
	private static final int CRAFTED_MARK = 0xFF4FA3FF;
	private static final int ERROR_COLOR = 0xFFFF7F7F;

	// remembered between openings
	private static @Nullable TaskNode selected;
	private static Mode mode = Mode.ADD;
	private static int scroll;
	private static String searchText = "";
	private static String amountText = "64";
	/** Item id → what a job for it counts (icon, carried amount); looked up once, not every frame. */
	private static final Map<String, Optional<Requirement>> requirements = new HashMap<>();

	private final Font font;
	private final int left;
	private final int rightX;
	private final int colW;
	private final int contentY;
	private final int bottomY;
	private final Consumer<AbstractWidget> add;
	private final Runnable rebuild;
	private @Nullable Button runAll;
	private @Nullable Button runSelected;
	private @Nullable EditBox search;
	private List<ItemCatalog.Entry> results = List.of();
	private int resultScroll;
	private int gridY;
	private int gridCols;
	private int gridRows;

	/** A visible tree row; {@code open[k]}: the ancestor at depth k has later siblings (its guide line runs on). */
	private record Line(TaskNode node, int depth, boolean last, boolean[] open) {}

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

	private int treeX() {
		return left + 8;
	}

	private int rowsTop() {
		return contentY + 14;
	}

	private int visibleRows() {
		return Math.max(1, (bottomY - 2 - rowsTop()) / ROW);
	}

	/** The search box, to focus when the tab opens (null while editing). */
	@Nullable EditBox focus() {
		return search;
	}

	void init() {
		if (selected != null && TaskTree.siblings(selected) == null) selected = null; // deleted or another world
		if (selected == null) mode = Mode.ADD;
		int third = (colW - 8) / 3;
		button("+ Group", "A big task made of subtasks (added under the selected task)", treeX(), third, () -> addEdited(new TaskNode("New task", TaskNode.Kind.GROUP)));
		button("+ Build", "Build a schematic (a spot is found when it runs, or use From placement)", treeX() + 4 + third, third, () -> {
			TaskNode n = new TaskNode("Build starter_base", TaskNode.Kind.BUILD);
			n.schematic = "starter_base";
			addEdited(n);
		});
		button("Clear done", "Remove finished tasks", treeX() + 8 + 2 * third, third, () -> {
			TaskTree.clearDone();
			if (selected != null && TaskTree.siblings(selected) == null) selected = null;
			rebuild.run();
		});
		int half = colW / 2 - 2;
		runAll = Button.builder(Component.empty(), b -> {
			if (TaskTree.active()) TaskTree.stop();
			else TaskTree.start(null);
			tick();
		}).tooltip(Tooltip.create(Component.literal("Work through the whole tree: subtasks before their parent, one job or build at a time")))
				.bounds(rightX, bottomY + 2, half, 18).build();
		add.accept(runAll);
		runSelected = Button.builder(Component.literal("Run selected"), b -> {
			if (selected != null) TaskTree.start(selected);
			tick();
		}).tooltip(Tooltip.create(Component.literal("Only the selected task (and its subtasks first)"))).bounds(rightX + half + 4, bottomY + 2, half, 18).build();
		add.accept(runSelected);

		Button addMode = Button.builder(Component.literal("Add items"), b -> {
			mode = Mode.ADD;
			rebuild.run();
		}).tooltip(Tooltip.create(Component.literal("Pick items to collect; they go under the selected task"))).bounds(rightX, contentY, half, 16).build();
		Button editMode = Button.builder(Component.literal("Edit task"), b -> {
			mode = Mode.EDIT;
			rebuild.run();
		}).bounds(rightX + half + 4, contentY, half, 16).build();
		addMode.active = mode != Mode.ADD;
		editMode.active = mode != Mode.EDIT && selected != null;
		add.accept(addMode);
		add.accept(editMode);

		search = null;
		if (mode == Mode.ADD) initPicker();
		else if (selected != null) initEditor(selected);
		tick();
	}

	private void button(String label, String tip, int x, int w, Runnable action) {
		add.accept(Button.builder(Component.literal(label), b -> action.run()).tooltip(Tooltip.create(Component.literal(tip))).bounds(x, bottomY + 2, w, 18).build());
	}

	/** Add a group/build under the selection and open it for editing (it needs a title or a schematic). */
	private void addEdited(TaskNode node) {
		TaskTree.add(selected, node);
		selected = node;
		mode = Mode.EDIT;
		rebuild.run();
	}

	// ---- add items ------------------------------------------------------------------------------

	private void initPicker() {
		int y = contentY + 20;
		search = new EditBox(font, rightX, y, colW - 52, 16, Component.literal("Search"));
		search.setHint(Component.literal("Search items...").withStyle(ChatFormatting.DARK_GRAY));
		search.setValue(searchText);
		search.setResponder(s -> {
			searchText = s;
			refreshResults();
		});
		add.accept(search);
		EditBox amount = new EditBox(font, rightX + colW - 48, y, 48, 16, Component.literal("Amount"));
		amount.setMaxLength(6);
		amount.setValue(amountText);
		amount.setResponder(s -> amountText = s);
		amount.setTooltip(Tooltip.create(Component.literal("Amount added per click (right-click adds one stack)")));
		add.accept(amount);
		gridY = contentY + 52;
		gridCols = Math.max(1, (colW - 4) / SLOT);
		gridRows = Math.max(1, (bottomY - 2 - gridY) / SLOT);
		refreshResults();
	}

	private void refreshResults() {
		results = ItemCatalog.search(searchText);
		resultScroll = 0;
	}

	private int amountValue() {
		try {
			return Math.max(1, Integer.parseInt(amountText.trim()));
		} catch (NumberFormatException e) {
			return 64;
		}
	}

	/** A collect task under the selection (else at the top); the same item already waiting there just gets more. */
	private void addItem(String key, int amount) {
		List<TaskNode> list = selected == null ? TaskTree.roots() : selected.children;
		for (TaskNode n : list) {
			if (n.kind != TaskNode.Kind.COLLECT || n.state != TaskNode.State.PENDING || !n.item.equals(key)) continue;
			boolean defaultTitle = n.title.equals(TaskNode.collect(n.item, n.amount).title);
			n.amount += amount;
			if (defaultTitle) n.title = TaskNode.collect(n.item, n.amount).title;
			TaskTree.save();
			return;
		}
		TaskTree.add(selected, TaskNode.collect(key, amount));
	}

	/** Enter in the search box: the typed name if it is a resource ("log", "iron"), else the first result. */
	boolean keyPressed(KeyEvent event) {
		if (search == null || !search.isFocused() || !event.isConfirmation()) return false;
		String typed = searchText.trim().toLowerCase().replace(' ', '_');
		if (!typed.isEmpty() && Resources.forJob(typed, 1) != null) addItem(typed, amountValue());
		else if (!results.isEmpty()) addItem(results.getFirst().key(), amountValue());
		return true;
	}

	private ItemCatalog.@Nullable Entry resultAt(double mx, double my) {
		if (search == null || !inside(mx, my, rightX, gridY, gridCols * SLOT, gridRows * SLOT)) return null;
		int i = (resultScroll + (int) (my - gridY) / SLOT) * gridCols + (int) (mx - rightX) / SLOT;
		return i < results.size() ? results.get(i) : null;
	}

	private void extractPicker(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		String target = selected == null ? "Adds new top-level tasks" : "Adds under: " + selected.title;
		g.text(font, font.plainSubstrByWidth(target, colW), rightX, contentY + 41, Widgets.GRAY);
		for (int r = 0; r < gridRows; r++) {
			for (int c = 0; c < gridCols; c++) {
				int i = (resultScroll + r) * gridCols + c;
				int sx = rightX + c * SLOT;
				int sy = gridY + r * SLOT;
				boolean hover = i < results.size() && inside(mouseX, mouseY, sx, sy, SLOT, SLOT);
				Widgets.slot(g, sx, sy, hover);
				if (i >= results.size()) continue;
				ItemCatalog.Entry e = results.get(i);
				g.item(e.icon().getDefaultInstance(), sx + 1, sy + 1);
				if (e.crafted()) g.fill(sx + 14, sy + 1, sx + 17, sy + 4, CRAFTED_MARK);
				if (hover) {
					g.setComponentTooltipForNextFrame(font, List.of(Component.literal(e.name()),
							Component.literal(e.how() + (e.crafted() ? " (blue corner)" : "")).withStyle(ChatFormatting.GRAY),
							Component.literal("Click: add " + amountValue() + " · Right-click: a stack · Enter: the typed name").withStyle(ChatFormatting.DARK_GRAY)),
							mouseX, mouseY);
				}
			}
		}
		int totalRows = (results.size() + gridCols - 1) / gridCols;
		if (totalRows > gridRows) {
			int trackH = gridRows * SLOT;
			int thumbH = Math.max(8, trackH * gridRows / totalRows);
			int thumbY = gridY + (trackH - thumbH) * resultScroll / Math.max(1, totalRows - gridRows);
			int barX = rightX + gridCols * SLOT + 1;
			g.fill(barX, gridY, barX + 2, gridY + trackH, Widgets.DARK);
			g.fill(barX, thumbY, barX + 2, thumbY + thumbH, Widgets.GRAY);
		}
	}

	// ---- edit -----------------------------------------------------------------------------------

	private void initEditor(TaskNode n) {
		int top = contentY + 20;
		EditBox title = box(rightX, top, colW, 128, n.title, "Title");
		title.setResponder(s -> {
			n.title = s;
			TaskTree.save();
		});
		int y = top + 20;
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
		EditBox context = box(rightX, top + 40, colW, 256, n.context, "Context / notes for this task");
		context.setResponder(s -> {
			n.context = s;
			TaskTree.save();
		});
		int quarter = (colW - 12) / 4;
		int by = top + 60;
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
			if (TaskTree.siblings(n) == null) selected = null; // the running task can't be deleted
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

	private void extractDetails(GuiGraphicsExtractor g, TaskNode n) {
		int y = contentY + 124;
		g.text(font, "Status: " + stateText(n), rightX, y, color(n));
		y += LINE;
		String problem = problem(n);
		if (problem != null) {
			g.text(font, font.plainSubstrByWidth(problem, colW), rightX, y, ERROR_COLOR);
		} else {
			g.text(font, font.plainSubstrByWidth(describe(n), colW), rightX, y, Widgets.GRAY);
		}
		y += LINE;
		int[] pr = progress(n);
		if (pr != null) Widgets.progressRow(g, font, rightX, y, colW, null, progressLabel(n), pr[0], pr[1], false);
	}

	// ---- tree -----------------------------------------------------------------------------------

	private static List<Line> lines() {
		List<Line> out = new ArrayList<>();
		flatten(TaskTree.roots(), 0, new boolean[0], out);
		return out;
	}

	private static void flatten(List<TaskNode> nodes, int depth, boolean[] open, List<Line> out) {
		for (int i = 0; i < nodes.size(); i++) {
			TaskNode n = nodes.get(i);
			boolean last = i == nodes.size() - 1;
			out.add(new Line(n, depth, last, open));
			if (n.expanded && !n.children.isEmpty()) {
				boolean[] deeper = Arrays.copyOf(open, depth + 1);
				deeper[depth] = !last;
				flatten(n.children, depth + 1, deeper, out);
			}
		}
	}

	void tick() {
		boolean active = TaskTree.active();
		if (runAll != null) runAll.setMessage(Component.literal(active ? "Stop" : "Run all"));
		if (runSelected != null) runSelected.active = !active && selected != null && selected.state != TaskNode.State.DONE;
	}

	/** Unfinished jobs, fetches and builds in the whole tree (folded parts too). */
	private static int todo(List<TaskNode> nodes) {
		int n = 0;
		for (TaskNode t : nodes) n += (t.state != TaskNode.State.DONE && t.kind != TaskNode.Kind.GROUP ? 1 : 0) + todo(t.children);
		return n;
	}

	void extract(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int x = treeX();
		List<Line> lines = lines();
		int todo = todo(TaskTree.roots());
		g.text(font, "Tasks" + (TaskTree.active() ? " — running" : lines.isEmpty() ? "" : " — " + todo + " to do"), x, contentY + 2, Widgets.WHITE);
		if (lines.isEmpty()) {
			g.textWithWordWrap(font, Component.literal("Nothing to do yet. Pick items on the right to collect them, or add a group for a big goal"
					+ " and builds or items under it. Subtasks run before their parent; Expand breaks a task into its parts."), x, rowsTop() + 2, colW - 4,
					Widgets.GRAY);
			legend(g, x, rowsTop() + 70);
		}
		scroll = Math.max(0, Math.min(scroll, lines.size() - visibleRows()));
		TaskNode next = TaskTree.upNext();
		Line hovered = null;
		for (int i = 0; i < visibleRows() && scroll + i < lines.size(); i++) {
			Line l = lines.get(scroll + i);
			int y = rowsTop() + i * ROW;
			boolean hover = inside(mouseX, mouseY, x - 2, y, colW + 2, ROW);
			if (hover) hovered = l;
			if (l.node == selected) {
				g.fill(x - 2, y, x + colW, y + ROW, 0x40FFFFFF);
				g.outline(x - 2, y, colW + 2, ROW, 0x80FFFFFF);
			} else if (hover) {
				g.fill(x - 2, y, x + colW, y + ROW, 0x18FFFFFF);
			}
			row(g, x, y, l, l.node == next);
		}
		if (lines.size() > visibleRows()) {
			int trackH = visibleRows() * ROW;
			int thumbH = Math.max(8, trackH * visibleRows() / lines.size());
			int thumbY = rowsTop() + (trackH - thumbH) * scroll / Math.max(1, lines.size() - visibleRows());
			g.fill(x + colW + 1, rowsTop(), x + colW + 3, rowsTop() + trackH, Widgets.DARK);
			g.fill(x + colW + 1, thumbY, x + colW + 3, thumbY + thumbH, Widgets.GRAY);
		}
		if (hovered != null) g.setComponentTooltipForNextFrame(font, tooltip(hovered.node, hovered.node == next), mouseX, mouseY);

		if (mode == Mode.ADD) extractPicker(g, mouseX, mouseY);
		else if (selected != null) extractDetails(g, selected);
	}

	/** One row: guide lines, fold arrow, status pip, icon, title, amount and a progress bar under the title. */
	private void row(GuiGraphicsExtractor g, int x, int y, Line l, boolean next) {
		TaskNode n = l.node;
		int mid = y + ROW / 2 - 1;
		int nx = x + l.depth * INDENT;
		for (int k = 1; k < l.depth; k++) {
			if (l.open[k]) g.fill(guideX(x, k - 1), y, guideX(x, k - 1) + 1, y + ROW, GUIDE_COLOR);
		}
		if (l.depth > 0) {
			int gx = guideX(x, l.depth - 1);
			g.fill(gx, y, gx + 1, l.last ? mid + 1 : y + ROW, GUIDE_COLOR);
			g.fill(gx, mid, nx, mid + 1, GUIDE_COLOR);
		}
		boolean folds = !n.children.isEmpty();
		if (folds) {
			arrow(g, nx, mid - 2, n.expanded, Widgets.GRAY);
			if (n.expanded) g.fill(guideX(x, l.depth), mid + 2, guideX(x, l.depth) + 1, y + ROW, GUIDE_COLOR);
		} else if (l.depth > 0) {
			g.fill(nx, mid, nx + 6, mid + 1, GUIDE_COLOR); // the guide runs on to the dot
		}
		pip(g, nx + 7, mid - 2, pipColor(n, next));
		Item icon = icon(n);
		Widgets.scaledItem(g, icon, nx + 15, y + 2, 0.75F);
		if (n.kind == TaskNode.Kind.FETCH) Widgets.scaledItem(g, Items.CHEST, nx + 21, y + 8, 0.4375F); // "from a base" badge
		int textX = nx + 30;
		int right = x + colW - 3;
		int[] pr = progress(n);
		String count = pr == null ? "" : n.kind == TaskNode.Kind.GROUP ? pr[0] + "/" + pr[1] : shortCount(pr[0]) + "/" + shortCount(pr[1]);
		int countX = right - font.width(count);
		g.text(font, font.plainSubstrByWidth(n.title, Math.max(0, countX - textX - 4)), textX, y + 3, color(n));
		if (pr != null) {
			g.text(font, count, countX, y + 3, Widgets.GRAY);
			double f = pr[1] == 0 ? 0 : Math.min(1, (double) pr[0] / pr[1]);
			g.fill(textX, y + 13, right, y + 14, Widgets.DARK);
			g.fill(textX, y + 13, textX + (int) Math.round((right - textX) * f), y + 14, f >= 1 ? DONE_COLOR : ACTIVE_COLOR);
		}
	}

	private static int guideX(int x, int depth) {
		return x + depth * INDENT + 2;
	}

	/** ▾ (open) or ▸ (folded), 5 px. */
	private static void arrow(GuiGraphicsExtractor g, int x, int y, boolean open, int color) {
		for (int i = 0; i < 3; i++) {
			if (open) g.fill(x + i, y + 1 + i, x + 5 - i, y + 2 + i, color);
			else g.fill(x + 1 + i, y + i, x + 2 + i, y + 5 - i, color);
		}
	}

	/** A round-ish 5 px status dot (odd size: centred on the guide line). */
	private static void pip(GuiGraphicsExtractor g, int x, int y, int color) {
		g.fill(x, y + 1, x + 5, y + 4, color);
		g.fill(x + 1, y, x + 4, y + 5, color);
	}

	private void legend(GuiGraphicsExtractor g, int x, int y) {
		Object[][] keys = {{DONE_COLOR, "done"}, {ACTIVE_COLOR, "running"}, {NEXT_COLOR, "next"}, {READY_COLOR, "later"}, {BLOCKED_COLOR, "after its subtasks"}};
		int cx = x;
		for (Object[] k : keys) {
			String label = (String) k[1];
			if (cx + 9 + font.width(label) > x + colW) {
				cx = x;
				y += LINE;
			}
			pip(g, cx, y + 1, (Integer) k[0]);
			g.text(font, label, cx + 9, y, Widgets.GRAY);
			cx += 9 + font.width(label) + 8;
		}
	}

	private static int pipColor(TaskNode n, boolean next) {
		return switch (n.state) {
			case DONE -> DONE_COLOR;
			case ACTIVE -> System.currentTimeMillis() / 400 % 2 == 0 ? ACTIVE_COLOR : 0xFFB08A30; // blinking: working on it
			case PENDING -> next ? NEXT_COLOR : n.allChildrenDone() ? READY_COLOR : BLOCKED_COLOR;
		};
	}

	private static int color(TaskNode n) {
		return switch (n.state) {
			case DONE -> 0xFF9FCF9F;
			case ACTIVE -> ACTIVE_COLOR;
			case PENDING -> Widgets.WHITE;
		};
	}

	private static String stateText(TaskNode n) {
		return switch (n.state) {
			case DONE -> "done";
			case ACTIVE -> "running now";
			case PENDING -> n == TaskTree.upNext() ? "next up" : n.allChildrenDone() ? "waiting its turn" : "waits for its subtasks";
		};
	}

	private List<Component> tooltip(TaskNode n, boolean next) {
		List<Component> out = new ArrayList<>();
		out.add(Component.literal(n.title));
		String problem = problem(n);
		out.add(Component.literal(problem != null ? problem : describe(n)).withStyle(problem != null ? ChatFormatting.RED : ChatFormatting.GRAY));
		out.add(Component.literal(next ? "Next up" : stateText(n).substring(0, 1).toUpperCase() + stateText(n).substring(1)).withStyle(ChatFormatting.GRAY));
		if (!n.context.isBlank()) out.add(Component.literal(n.context).withStyle(ChatFormatting.ITALIC, ChatFormatting.DARK_AQUA));
		out.add(Component.literal("Click: select · arrow: fold").withStyle(ChatFormatting.DARK_GRAY));
		return out;
	}

	/** What the task does, in words. */
	private static String describe(TaskNode n) {
		return switch (n.kind) {
			case GROUP -> n.children.isEmpty() ? "Group: add subtasks under it" : "Group of " + n.children.size() + " subtask" + (n.children.size() == 1 ? "" : "s");
			case COLLECT -> {
				Requirement r = requirement(n.item);
				yield "Collect " + n.amount + " × " + itemName(n) + (r == null ? "" : " · " + ItemCatalog.how(ItemCatalog.iconOf(r), r).toLowerCase());
			}
			case FETCH -> "Take " + n.amount + " × " + itemName(n) + " from " + (n.base.isBlank() ? "the nearest base that has it" : "base \"" + n.base + "\"");
			case BUILD -> "Build " + n.schematic + (n.origin == null ? " (spot found when it runs)"
					: " at " + n.origin[0] + " " + n.origin[1] + " " + n.origin[2] + (n.rotation == 0 ? "" : ", rotated " + n.rotation * 90 + "°"));
		};
	}

	/** Why the task can't run as written, or null. */
	private static @Nullable String problem(TaskNode n) {
		return switch (n.kind) {
			case COLLECT -> requirement(n.item) == null ? "Unknown item: \"" + n.item + "\"" : null;
			case FETCH -> TaskTree.item(n.item) == null ? "Unknown item: \"" + n.item + "\""
					: !n.base.isBlank() && Bases.named(n.base) == null ? "No base named \"" + n.base + "\" (bases: " + Bases.all().stream().map(Bases.Base::name).toList() + ")"
					: null;
			case BUILD -> SchematicLibrary.get(n.schematic) == null ? "No schematic named \"" + n.schematic + "\"" : null;
			case GROUP -> null;
		};
	}

	private static String itemName(TaskNode n) {
		Item item = TaskTree.item(n.item);
		return item != null ? item.getDefaultInstance().getHoverName().getString() : n.item.replace('_', ' ');
	}

	private static Item icon(TaskNode n) {
		return switch (n.kind) {
			case GROUP -> Items.WRITABLE_BOOK;
			case BUILD -> Items.BRICKS;
			case COLLECT, FETCH -> {
				Requirement r = requirement(n.item);
				Item item = TaskTree.item(n.item);
				yield r != null ? ItemCatalog.iconOf(r) : item != null ? item : Items.BARRIER;
			}
		};
	}

	private static @Nullable Requirement requirement(String item) {
		return requirements.computeIfAbsent(item, k -> Optional.ofNullable(k.isBlank() ? null : Resources.forJob(k, 1))).orElse(null);
	}

	/** {have, total} for the bar, or null when there's nothing to measure. */
	private static int @Nullable [] progress(TaskNode n) {
		LocalPlayer p = Minecraft.getInstance().player;
		Bot bot = Bot.get();
		return switch (n.kind) {
			case GROUP -> n.children.isEmpty() ? null : new int[] {(int) n.children.stream().filter(c -> c.state == TaskNode.State.DONE).count(), n.children.size()};
			case BUILD -> n.state == TaskNode.State.DONE ? new int[] {1, 1}
					: n.state == TaskNode.State.ACTIVE && bot.buildTotal() > 0 ? new int[] {bot.buildPlaced(), bot.buildTotal()} : null;
			case COLLECT, FETCH -> {
				if (n.state == TaskNode.State.DONE) yield new int[] {n.amount, n.amount};
				Requirement r = requirement(n.item);
				if (p == null || r == null) yield null;
				Job job = bot.job();
				// running: the job's count (carried plus already deposited); otherwise what's carried now
				int have = n.state == TaskNode.State.ACTIVE && job != null && n == TaskTree.running() ? job.have(p, job.requirements().getFirst())
						: InvUtil.count(p, r.counts());
				yield new int[] {Math.min(have, n.amount), n.amount};
			}
		};
	}

	private static String progressLabel(TaskNode n) {
		return switch (n.kind) {
			case GROUP -> "subtasks done";
			case BUILD -> "blocks placed";
			case COLLECT, FETCH -> n.state == TaskNode.State.ACTIVE ? "collected" : "carried now";
		};
	}

	private static String shortCount(int n) {
		return n >= 10000 ? n / 1000 + "k" : Integer.toString(n);
	}

	// ---- input ----------------------------------------------------------------------------------

	boolean click(double mx, double my, int button) {
		ItemCatalog.Entry e = resultAt(mx, my);
		if (e != null) {
			addItem(e.key(), button == 1 ? e.icon().getDefaultMaxStackSize() : amountValue());
			return true;
		}
		int x = treeX();
		if (mx < x - 2 || mx >= x + colW || my < rowsTop() || my >= rowsTop() + visibleRows() * ROW) return false;
		int index = scroll + (int) ((my - rowsTop()) / ROW);
		List<Line> lines = lines();
		if (index >= lines.size()) {
			selected = null; // empty space: items go to the top level again
			mode = Mode.ADD;
			rebuild.run();
			return true;
		}
		Line l = lines.get(index);
		int nx = x + l.depth * INDENT;
		if (!l.node.children.isEmpty() && mx >= nx - 2 && mx < nx + 7) {
			l.node.expanded = !l.node.expanded;
			TaskTree.save();
			return true;
		}
		if (selected == l.node && mode == Mode.EDIT) return true;
		selected = l.node;
		if (l.node.kind != TaskNode.Kind.GROUP) mode = Mode.EDIT; // a group is usually selected to add items under it
		rebuild.run();
		return true;
	}

	boolean scroll(double mx, double my, int dir) {
		if (search != null && inside(mx, my, rightX, gridY, gridCols * SLOT + 4, gridRows * SLOT)) {
			int maxScroll = Math.max(0, (results.size() + gridCols - 1) / gridCols - gridRows);
			resultScroll = Math.max(0, Math.min(maxScroll, resultScroll - dir));
			return true;
		}
		if (mx < treeX() - 2 || mx >= treeX() + colW + 4 || my < rowsTop() || my >= bottomY) return false;
		scroll = Math.max(0, scroll - dir);
		return true;
	}

	private static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}
}
