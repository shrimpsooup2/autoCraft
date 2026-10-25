package com.autocraft.ui;

import com.autocraft.Bot;
import com.autocraft.Chat;
import com.autocraft.build.BuildPlan;
import com.autocraft.build.Placement;
import com.autocraft.build.SiteFinder;
import com.autocraft.build.StructureRegistry;
import com.autocraft.goal.Job;
import com.autocraft.goal.Requirement;
import com.autocraft.goal.Resources;
import com.autocraft.schematic.Schematic;
import com.autocraft.schematic.SchematicLibrary;
import com.autocraft.team.Team;
import com.mojang.blaze3d.Blaze3D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

/**
 * The bot's control panel (default key K). Collect: search any minable or craftable item, add it with an amount,
 * start the job. Build: pick a schematic (built-in or from the folder) and place it in the world as a ghost,
 * Litematica-style — exact coordinates, nudges, rotation, mirror — then build it. Bot: live status, pause/stop,
 * base, log-off and HUD toggles.
 */
public final class AutoCraftScreen extends Screen {
	private enum Tab {
		COLLECT("Collect"), BUILD("Build"), TEAM("Team"), BOT("Bot");

		final String label;

		Tab(String label) {
			this.label = label;
		}
	}

	private record OrderLine(ItemCatalog.Entry entry, int amount) {}

	private static final int ROW = 14;
	private static final int LINE = 11;
	private static final int CRAFTED_MARK = 0xFF4FA3FF;

	// remembered between openings
	private static Tab tab = Tab.COLLECT;
	private static final List<OrderLine> order = new ArrayList<>();
	private static String searchText = "";
	private static String amountText = "64";
	private static @Nullable String selectedSchematic;

	private int left;
	private int top;
	private int panelW;
	private int panelH;
	private int colW;
	private int rightX;
	private int contentY;
	private int bottomY;

	// collect tab
	private @Nullable EditBox search;
	private List<ItemCatalog.Entry> results = List.of();
	private int resultScroll;
	private int gridX;
	private int gridY;
	private int gridCols;
	private int gridRows;
	private int orderY;
	private int orderRows;
	private int orderScroll;
	private @Nullable Button startButton;

	// build tab
	private List<String> schematics = List.of();
	private int listY;
	private int listRows;
	private int listScroll;
	private @Nullable Schematic preview;
	private List<Map.Entry<Item, Integer>> previewBill = List.of();
	private @Nullable EditBox posX;
	private @Nullable EditBox posY;
	private @Nullable EditBox posZ;
	private @Nullable Button placeButton;
	private @Nullable Button siteButton;

	// bot tab
	private @Nullable Button pauseButton;
	private @Nullable Button logoffButton;
	private @Nullable Button hudButton;

	// team tab
	private static String joinAddress = "127.0.0.1:" + Team.DEFAULT_PORT;
	private static String joinCode = "";
	private @Nullable Button joinButton;

	public AutoCraftScreen() {
		super(Component.literal("autoCraft"));
	}

	@Override
	public boolean isPauseScreen() {
		return false; // the bot keeps working (and singleplayer keeps ticking) while the panel is open
	}

	@Override
	public boolean isInGameUi() {
		return true;
	}

	@Override
	protected void init() {
		panelW = Math.min(width - 16, 420);
		panelH = Math.min(height - 16, 240);
		left = (width - panelW) / 2;
		top = (height - panelH) / 2;
		contentY = top + 26;
		bottomY = top + panelH - 24;
		colW = (panelW - 24) / 2;
		rightX = left + 16 + colW;
		int tabX = left + panelW - Tab.values().length * 56 - 4;
		for (Tab t : Tab.values()) {
			Button b = addRenderableWidget(Button.builder(Component.literal(t.label), btn -> {
				tab = t;
				rebuildWidgets();
			}).bounds(tabX + t.ordinal() * 56, top + 4, 54, 16).build());
			b.active = t != tab;
		}
		switch (tab) {
			case COLLECT -> initCollect();
			case BUILD -> initBuild();
			case TEAM -> initTeam();
			case BOT -> initBot();
		}
	}

	@Override
	public void tick() {
		switch (tab) {
			case COLLECT -> updateCollectButtons();
			case BUILD -> updateBuildButtons();
			case TEAM -> updateTeamButtons();
			case BOT -> updateBotButtons();
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		g.fill(left, top, left + panelW, top + panelH, 0xE0101010);
		g.outline(left, top, panelW, panelH, 0xFF505050);
		g.text(font, "autoCraft", left + 8, top + 8, Widgets.WHITE);
		Bot bot = Bot.get();
		String state = !bot.isRunning() ? "idle" : bot.isPaused() ? "paused" : bot.activeBehavior();
		int stateX = left + 16 + font.width("autoCraft");
		g.text(font, font.plainSubstrByWidth(state, left + panelW - Tab.values().length * 56 - 8 - stateX), stateX, top + 8, Widgets.GRAY);
		switch (tab) {
			case COLLECT -> extractCollect(g, mouseX, mouseY);
			case BUILD -> extractBuild(g, mouseX, mouseY);
			case TEAM -> extractTeam(g);
			case BOT -> extractBot(g);
		}
		super.extractRenderState(g, mouseX, mouseY, a);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) return true;
		double mx = event.x();
		double my = event.y();
		if (tab == Tab.COLLECT) {
			ItemCatalog.Entry e = resultAt(mx, my);
			if (e != null) {
				add(e, event.button() == 1 ? e.icon().getDefaultMaxStackSize() : amountValue());
				return true;
			}
			int row = orderRowAt(mx, my);
			if (row >= 0 && (event.button() == 1 || mx >= rightX + colW - 10)) {
				order.remove(row);
				orderScroll = Math.max(0, Math.min(orderScroll, order.size() - orderRows));
				return true;
			}
		} else if (tab == Tab.BUILD) {
			int i = schematicAt(mx, my);
			if (i >= 0) {
				select(schematics.get(i));
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		int dir = (int) Math.signum(scrollY);
		if (tab == Tab.COLLECT) {
			if (inside(x, y, gridX, gridY, gridCols * 18, gridRows * 18)) {
				int maxScroll = Math.max(0, (results.size() + gridCols - 1) / gridCols - gridRows);
				resultScroll = Math.max(0, Math.min(maxScroll, resultScroll - dir));
				return true;
			}
			int row = orderRowAt(x, y);
			if (row >= 0) {
				OrderLine l = order.get(row);
				int step = minecraft.hasShiftDown() ? 16 : 1;
				order.set(row, new OrderLine(l.entry, Math.max(1, l.amount + dir * step)));
				return true;
			}
			if (inside(x, y, rightX, orderY, colW, orderRows * ROW)) {
				orderScroll = Math.max(0, Math.min(Math.max(0, order.size() - orderRows), orderScroll - dir));
				return true;
			}
		} else if (tab == Tab.BUILD && inside(x, y, left + 8, listY, colW, listRows * LINE)) {
			listScroll = Math.max(0, Math.min(Math.max(0, schematics.size() - listRows), listScroll - dir));
			return true;
		}
		return super.mouseScrolled(x, y, scrollX, scrollY);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (tab == Tab.COLLECT && search != null && search.isFocused() && event.isConfirmation()) {
			addTyped();
			return true;
		}
		return super.keyPressed(event);
	}

	// ---- collect --------------------------------------------------------------------------------

	private void initCollect() {
		int x = left + 8;
		search = addRenderableWidget(new EditBox(font, x, contentY, colW - 52, 16, Component.literal("Search")));
		search.setHint(Component.literal("Search items...").withStyle(ChatFormatting.DARK_GRAY));
		search.setValue(searchText);
		search.setResponder(s -> {
			searchText = s;
			refreshResults();
		});
		EditBox amount = addRenderableWidget(new EditBox(font, x + colW - 48, contentY, 48, 16, Component.literal("Amount")));
		amount.setMaxLength(6);
		amount.setValue(amountText);
		amount.setResponder(s -> amountText = s);
		amount.setTooltip(Tooltip.create(Component.literal("Amount added per click (right-click adds one stack)")));

		gridX = x;
		gridY = contentY + 22;
		gridCols = Math.max(1, colW / 18);
		gridRows = Math.max(1, (top + panelH - 16 - gridY) / 18);
		refreshResults();

		orderY = contentY + 14;
		orderRows = Math.max(1, (bottomY - 16 - orderY) / ROW);
		addRenderableWidget(Button.builder(Component.literal("Clear"), b -> {
			order.clear();
			orderScroll = 0;
		}).bounds(rightX, bottomY, 50, 18).build());
		startButton = addRenderableWidget(Button.builder(Component.literal("Start job"), b -> startJob())
				.bounds(rightX + colW - 90, bottomY, 90, 18).build());
		updateCollectButtons();
		setInitialFocus(search);
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

	private void add(ItemCatalog.Entry e, int amount) {
		for (int i = 0; i < order.size(); i++) {
			if (order.get(i).entry.key().equals(e.key())) {
				order.set(i, new OrderLine(e, order.get(i).amount + amount));
				return;
			}
		}
		order.add(new OrderLine(e, amount));
		orderScroll = Math.max(0, order.size() - orderRows);
	}

	/** Enter in the search box: the typed name if it is a resource ("log", "iron"), else the first result. */
	private void addTyped() {
		String typed = searchText.trim().toLowerCase().replace(' ', '_');
		Requirement r = typed.isEmpty() ? null : Resources.forJob(typed, 1);
		if (r != null) {
			add(new ItemCatalog.Entry(typed, ItemCatalog.iconOf(r), typed, r.isCrafted(), ItemCatalog.how(ItemCatalog.iconOf(r), r)), amountValue());
		} else if (!results.isEmpty()) {
			add(results.getFirst(), amountValue());
		}
	}

	private void startJob() {
		List<Requirement> reqs = new ArrayList<>();
		for (OrderLine l : order) {
			Requirement r = Resources.forJob(l.entry.key(), l.amount);
			if (r != null) reqs.add(r);
		}
		if (reqs.isEmpty()) return;
		Bot.get().start(new Job(reqs));
		onClose();
	}

	private void updateCollectButtons() {
		if (startButton == null) return;
		startButton.active = !order.isEmpty() && minecraft.player != null;
		startButton.setMessage(Component.literal(Bot.get().isRunning() ? "Replace job" : "Start job"));
	}

	private ItemCatalog.@Nullable Entry resultAt(double mx, double my) {
		if (!inside(mx, my, gridX, gridY, gridCols * 18, gridRows * 18)) return null;
		int i = (resultScroll + (int) (my - gridY) / 18) * gridCols + (int) (mx - gridX) / 18;
		return i < results.size() ? results.get(i) : null;
	}

	private int orderRowAt(double mx, double my) {
		if (!inside(mx, my, rightX, orderY, colW, orderRows * ROW)) return -1;
		int i = orderScroll + (int) (my - orderY) / ROW;
		return i < order.size() ? i : -1;
	}

	private void extractCollect(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		for (int r = 0; r < gridRows; r++) {
			for (int c = 0; c < gridCols; c++) {
				int i = (resultScroll + r) * gridCols + c;
				int sx = gridX + c * 18;
				int sy = gridY + r * 18;
				boolean hover = i < results.size() && inside(mouseX, mouseY, sx, sy, 18, 18);
				Widgets.slot(g, sx, sy, hover);
				if (i >= results.size()) continue;
				ItemCatalog.Entry e = results.get(i);
				g.item(e.icon().getDefaultInstance(), sx + 1, sy + 1);
				if (e.crafted()) g.fill(sx + 14, sy + 1, sx + 17, sy + 4, CRAFTED_MARK);
				if (hover) {
					g.setComponentTooltipForNextFrame(font, List.of(Component.literal(e.name()),
							Component.literal(e.how()).withStyle(ChatFormatting.GRAY),
							Component.literal("Click: add " + amountValue() + " · Right-click: add a stack").withStyle(ChatFormatting.DARK_GRAY)),
							mouseX, mouseY);
				}
			}
		}
		int totalRows = (results.size() + gridCols - 1) / gridCols;
		if (totalRows > gridRows) {
			int trackH = gridRows * 18;
			int thumbH = Math.max(8, trackH * gridRows / totalRows);
			int thumbY = gridY + (trackH - thumbH) * resultScroll / Math.max(1, totalRows - gridRows);
			int barX = gridX + gridCols * 18 + 1;
			g.fill(barX, gridY, barX + 2, gridY + trackH, Widgets.DARK);
			g.fill(barX, thumbY, barX + 2, thumbY + thumbH, Widgets.GRAY);
		}
		int hintY = top + panelH - 12;
		g.fill(left + 8, hintY + 1, left + 13, hintY + 6, CRAFTED_MARK);
		g.text(font, font.plainSubstrByWidth(" crafted · Enter adds the typed name", colW - 5), left + 13, hintY, Widgets.GRAY);

		g.text(font, "Job" + (order.isEmpty() ? "" : " (" + order.size() + ")"), rightX, contentY + 2, Widgets.WHITE);
		if (order.isEmpty()) {
			g.textWithWordWrap(font, Component.literal("Click items on the left to add them. Scroll over a line to change the amount"
					+ " (shift: 16 at a time), right-click or × to remove."), rightX, orderY + 2, colW, Widgets.GRAY);
		}
		for (int k = 0; k < orderRows && orderScroll + k < order.size(); k++) {
			OrderLine l = order.get(orderScroll + k);
			int y = orderY + k * ROW;
			boolean hover = inside(mouseX, mouseY, rightX, y, colW, ROW);
			if (hover) g.fill(rightX, y, rightX + colW, y + ROW, 0x30FFFFFF);
			Widgets.smallItem(g, l.entry.icon(), rightX + 1, y + 2);
			String amount = l.amount + (l.amount >= 64 && l.entry.icon().getDefaultMaxStackSize() == 64 ? " (" + stacks(l.amount) + ")" : "");
			int amountX = rightX + colW - 14 - font.width(amount);
			g.text(font, font.plainSubstrByWidth(l.entry.name(), amountX - rightX - 18), rightX + 14, y + 3, l.entry.crafted() ? 0xFFB8D8FF : Widgets.WHITE);
			g.text(font, amount, amountX, y + 3, Widgets.GRAY);
			g.text(font, "×", rightX + colW - 8, y + 3, hover && mouseX >= rightX + colW - 10 ? 0xFFFF5555 : Widgets.GRAY);
		}
		if (Bot.get().isRunning() && !order.isEmpty()) g.text(font, "Replaces the running job.", rightX, bottomY - 11, Widgets.GRAY);
	}

	private static String stacks(int amount) {
		int full = amount / 64;
		int rest = amount % 64;
		return full + (full == 1 ? " stack" : " stacks") + (rest == 0 ? "" : " + " + rest);
	}

	// ---- build ----------------------------------------------------------------------------------

	private void initBuild() {
		schematics = SchematicLibrary.names();
		listY = contentY + 12;
		listRows = Math.max(1, (bottomY - 4 - listY) / LINE);
		addRenderableWidget(Button.builder(Component.literal("Open folder"), b -> Blaze3D.openPath(SchematicLibrary.folder()))
				.tooltip(Tooltip.create(Component.literal("Put .litematic, .schem or .nbt files here")))
				.bounds(left + 8, bottomY, 76, 18).build());
		addRenderableWidget(Button.builder(Component.literal("Reload"), b -> rebuildWidgets()).bounds(left + 88, bottomY, 50, 18).build());

		Placement placing = Placements.active();
		if (placing != null) selectedSchematic = placing.schematic().name();
		if (selectedSchematic != null && !schematics.contains(selectedSchematic) && placing == null) selectedSchematic = null;
		if (selectedSchematic == null && !schematics.isEmpty()) selectedSchematic = schematics.getFirst();
		if (selectedSchematic != null) select(selectedSchematic);
		if (placing != null) initPlacementControls(placing);
		else initPreviewButtons();
	}

	private void initPreviewButtons() {
		int half = colW / 2 - 2;
		siteButton = addRenderableWidget(Button.builder(Component.literal("Find a spot"), b -> findSpot())
				.tooltip(Tooltip.create(Component.literal("Let the bot pick flat, clear ground nearby; then adjust it or build")))
				.bounds(rightX, bottomY, half, 18).build());
		placeButton = addRenderableWidget(Button.builder(Component.literal("Place in world"), b -> place())
				.tooltip(Tooltip.create(Component.literal("Show it as a ghost where you look and position it (like Litematica)")))
				.bounds(rightX + half + 4, bottomY, half, 18).build());
		addRenderableWidget(Button.builder(Component.literal("Materials (defaults)..."), b -> minecraft.gui.setScreen(new MaterialsScreen(this, null)))
				.tooltip(Tooltip.create(Component.literal("Wood type, stand-ins, filler blocks used for new placements")))
				.bounds(rightX, bottomY - 22, colW, 18).build());
		updateBuildButtons();
	}

	/** Placement settings: exact coordinates with ± nudges, rotation, mirror, quick moves; the ghost updates live. */
	private void initPlacementControls(Placement p) {
		int rowY = contentY + 24;
		posX = coordRow(p, rowY, 0);
		posY = coordRow(p, rowY + 20, 1);
		posZ = coordRow(p, rowY + 40, 2);
		int half = colW / 2 - 2;
		addRenderableWidget(Button.builder(rotationLabel(p), b -> {
			p.rotate(!minecraft.hasShiftDown());
			b.setMessage(rotationLabel(p));
		}).tooltip(Tooltip.create(Component.literal("Clockwise; shift-click for counter-clockwise"))).bounds(rightX, rowY + 62, half, 18).build());
		addRenderableWidget(Button.builder(mirrorLabel(p), b -> {
			p.cycleMirror();
			b.setMessage(mirrorLabel(p));
		}).bounds(rightX + half + 4, rowY + 62, half, 18).build());
		addRenderableWidget(Button.builder(Component.literal("Move to me"), b -> {
			Placements.moveToPlayer();
			syncCoordinates(p);
		}).tooltip(Tooltip.create(Component.literal("Its 0,0,0 corner goes where you stand"))).bounds(rightX, rowY + 84, half, 18).build());
		addRenderableWidget(Button.builder(Component.literal("To crosshair"), b -> {
			Placements.moveToCrosshair();
			syncCoordinates(p);
		}).tooltip(Tooltip.create(Component.literal("Centered on the block you're looking at"))).bounds(rightX + half + 4, rowY + 84, half, 18).build());
		addRenderableWidget(Button.builder(Component.literal("Materials..."), b -> minecraft.gui.setScreen(new MaterialsScreen(this, p)))
				.tooltip(Tooltip.create(Component.literal(p.substitution().reason()))).bounds(rightX, rowY + 106, colW, 18).build());
		int third = (colW - 8) / 3;
		addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> {
			Placements.cancel();
			rebuildWidgets();
		}).bounds(rightX, bottomY, third, 18).build());
		addRenderableWidget(Button.builder(Component.literal("In world"), b -> onClose())
				.tooltip(Tooltip.create(Component.literal("Close the panel and move it with the keys: arrows, Page Up/Down, Alt+wheel, R, M, G;"
						+ " Enter builds")))
				.bounds(rightX + third + 4, bottomY, third, 18).build());
		addRenderableWidget(Button.builder(Component.literal("Build"), b -> {
			Placements.confirm();
			onClose();
		}).bounds(rightX + 2 * (third + 4), bottomY, third, 18).build());
	}

	private EditBox coordRow(Placement p, int y, int axis) {
		addRenderableWidget(Button.builder(Component.literal("-"), b -> nudge(p, axis, -step())).bounds(rightX + 10, y, 18, 16).build());
		EditBox box = addRenderableWidget(new EditBox(font, rightX + 30, y, colW - 52, 16, Component.literal("coordinate")));
		box.setMaxLength(9);
		box.setValue(Integer.toString(coordinate(p.origin(), axis)));
		box.setResponder(s -> {
			try {
				int v = Integer.parseInt(s.trim());
				BlockPos o = p.origin();
				p.setOrigin(new BlockPos(axis == 0 ? v : o.getX(), axis == 1 ? v : o.getY(), axis == 2 ? v : o.getZ()));
			} catch (NumberFormatException ignored) {
				// half-typed number: keep the last valid position
			}
		});
		addRenderableWidget(Button.builder(Component.literal("+"), b -> nudge(p, axis, step())).bounds(rightX + colW - 18, y, 18, 16).build());
		return box;
	}

	private int step() {
		return minecraft.hasShiftDown() ? 8 : 1;
	}

	private void nudge(Placement p, int axis, int d) {
		p.move(axis == 0 ? d : 0, axis == 1 ? d : 0, axis == 2 ? d : 0);
		syncCoordinates(p);
	}

	private void syncCoordinates(Placement p) {
		EditBox[] boxes = {posX, posY, posZ};
		for (int axis = 0; axis < 3; axis++) {
			String value = Integer.toString(coordinate(p.origin(), axis));
			if (boxes[axis] != null && !boxes[axis].getValue().trim().equals(value)) boxes[axis].setValue(value);
		}
	}

	private static int coordinate(BlockPos pos, int axis) {
		return axis == 0 ? pos.getX() : axis == 1 ? pos.getY() : pos.getZ();
	}

	private static Component rotationLabel(Placement p) {
		return Component.literal("Rotation: " + Placement.describe(p.rotation()));
	}

	private static Component mirrorLabel(Placement p) {
		return Component.literal("Mirror: " + Placement.describe(p.mirror()));
	}

	private void select(String name) {
		selectedSchematic = name;
		Placement placing = Placements.active();
		preview = placing != null && placing.schematic().name().equals(name) ? placing.schematic() : SchematicLibrary.get(name);
		Map<Item, Integer> bill = new HashMap<>();
		if (preview != null) {
			for (Schematic.Cell c : preview.cells()) {
				Item item = BuildPlan.itemFor(c.state());
				if (item != null) bill.merge(item, 1, Integer::sum);
			}
		}
		List<Map.Entry<Item, Integer>> sorted = new ArrayList<>(bill.entrySet());
		sorted.sort(Map.Entry.<Item, Integer>comparingByValue().reversed());
		previewBill = sorted;
	}

	private void place() {
		if (preview == null || minecraft.player == null) return;
		Placements.start(preview);
		onClose();
	}

	private void findSpot() {
		if (preview == null || minecraft.player == null) return;
		BlockPos site = SiteFinder.find(Bot.get(), preview);
		if (site == null) {
			Chat.warn("No flat, clear spot for " + preview.name() + " nearby; place it yourself instead.");
			return;
		}
		Placements.start(preview).setOrigin(site);
		onClose();
	}

	private void updateBuildButtons() {
		if (placeButton == null || siteButton == null) return;
		boolean ok = preview != null && minecraft.player != null && Placements.active() == null;
		placeButton.active = ok;
		siteButton.active = ok;
	}

	private int schematicAt(double mx, double my) {
		if (Placements.active() != null || !inside(mx, my, left + 8, listY, colW, listRows * LINE)) return -1; // locked while placing
		int i = listScroll + (int) (my - listY) / LINE;
		return i < schematics.size() ? i : -1;
	}

	private void extractBuild(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int x = left + 8;
		Placement placing = Placements.active();
		g.text(font, "Schematics (" + schematics.size() + ")", x, contentY + 2, Widgets.WHITE);
		for (int k = 0; k < listRows && listScroll + k < schematics.size(); k++) {
			String name = schematics.get(listScroll + k);
			int y = listY + k * LINE;
			boolean sel = name.equals(selectedSchematic);
			if (sel) g.fill(x, y, x + colW, y + LINE, 0x50FFFFFF);
			else if (placing == null && inside(mouseX, mouseY, x, y, colW, LINE)) g.fill(x, y, x + colW, y + LINE, 0x25FFFFFF);
			g.text(font, font.plainSubstrByWidth(name, colW - 4), x + 2, y + 2, sel ? Widgets.WHITE : placing == null ? 0xFFD0D0D0 : Widgets.GRAY);
		}
		if (schematics.size() > listRows) g.text(font, "scroll for more", x + colW - font.width("scroll for more"), contentY + 2, Widgets.GRAY);

		int y = contentY + 2;
		if (placing != null) {
			Schematic s = placing.schematic();
			BoundingBox box = placing.box();
			g.text(font, font.plainSubstrByWidth("Placing " + s.name(), colW), rightX, y, 0xFF7FE0FF);
			g.text(font, box.getXSpan() + "×" + box.getYSpan() + "×" + box.getZSpan() + " · " + s.nonAirCount() + " blocks", rightX, y + LINE,
					Widgets.GRAY);
			int rowY = contentY + 24;
			g.text(font, "X", rightX + 1, rowY + 4, Widgets.GRAY);
			g.text(font, "Y", rightX + 1, rowY + 24, Widgets.GRAY);
			g.text(font, "Z", rightX + 1, rowY + 44, Widgets.GRAY);
			g.text(font, font.plainSubstrByWidth("Shift: steps of 8. The ghost in the world updates live.", colW), rightX, rowY + 128, Widgets.GRAY);
			return;
		}
		if (preview == null) {
			g.textWithWordWrap(font, Component.literal(selectedSchematic == null ? "Add schematic files to the folder, then Reload."
					: "Couldn't read " + selectedSchematic + " (see the log)."), rightX, y, colW, Widgets.GRAY);
			return;
		}
		Schematic s = preview;
		g.text(font, font.plainSubstrByWidth(s.name() + (s.builtIn() ? " (built-in)" : ""), colW), rightX, y, Widgets.WHITE);
		y += LINE;
		g.text(font, s.size().getX() + "×" + s.size().getY() + "×" + s.size().getZ() + " (x·y·z), " + s.nonAirCount() + " blocks", rightX, y,
				Widgets.GRAY);
		y += LINE;
		if (s.farm() != null) {
			g.text(font, "Farm for " + s.farm().provides(), rightX, y, 0xFF9CE29C);
			y += LINE;
		}
		if (s.baseSpot() != null) {
			g.text(font, "Becomes your storage base", rightX, y, 0xFF9CE29C);
			y += LINE;
		}
		y += 2;
		int perRow = 2;
		int cellW = colW / perRow;
		int maxRows = Math.max(0, (bottomY - 38 - y) / 12);
		for (int i = 0; i < previewBill.size() && i < maxRows * perRow; i++) {
			Map.Entry<Item, Integer> e = previewBill.get(i);
			int cx = rightX + (i % perRow) * cellW;
			int cy = y + (i / perRow) * 12;
			Widgets.smallItem(g, e.getKey(), cx, cy);
			String label = e.getValue() + " " + e.getKey().getDefaultInstance().getHoverName().getString();
			g.text(font, font.plainSubstrByWidth(label, cellW - 14), cx + 12, cy + 1, 0xFFD0D0D0);
		}
		if (previewBill.size() > maxRows * perRow && maxRows > 0) {
			g.text(font, "+" + (previewBill.size() - maxRows * perRow) + " more kinds", rightX, y + maxRows * 12, Widgets.GRAY);
		}
	}

	// ---- team -----------------------------------------------------------------------------------

	private void initTeam() {
		if (Team.isActive()) {
			addRenderableWidget(Button.builder(Component.literal(Team.isLeader() ? "Close team" : "Leave team"), b -> {
				Team.leave();
				rebuildWidgets();
			}).bounds(rightX, bottomY, colW, 18).build());
			return;
		}
		addRenderableWidget(Button.builder(Component.literal("Host a team here"), b -> {
			try {
				Team.host(Team.DEFAULT_PORT);
			} catch (IllegalStateException e) {
				Chat.error(e.getMessage());
			}
			rebuildWidgets();
		}).tooltip(Tooltip.create(Component.literal("Open the team server on this computer; teammates join with the address and code shown")))
				.bounds(rightX, contentY + 14, colW, 18).build());
		EditBox address = addRenderableWidget(new EditBox(font, rightX, contentY + 72, colW, 16, Component.literal("Address")));
		address.setMaxLength(64);
		address.setValue(joinAddress);
		address.setResponder(s -> joinAddress = s);
		EditBox code = addRenderableWidget(new EditBox(font, rightX, contentY + 104, colW / 2, 16, Component.literal("Code")));
		code.setMaxLength(8);
		code.setValue(joinCode);
		code.setHint(Component.literal("CODE").withStyle(ChatFormatting.DARK_GRAY));
		code.setResponder(s -> joinCode = s);
		joinButton = addRenderableWidget(Button.builder(Component.literal("Join"), b -> {
			String a = joinAddress.trim();
			int colon = a.lastIndexOf(':');
			try {
				Team.join(colon > 0 ? a.substring(0, colon) : a, colon > 0 ? Integer.parseInt(a.substring(colon + 1)) : Team.DEFAULT_PORT, joinCode.trim());
			} catch (RuntimeException e) {
				Chat.error("Can't join: " + e.getMessage());
			}
			rebuildWidgets();
		}).bounds(rightX + colW / 2 + 4, contentY + 103, colW / 2 - 4, 18).build());
		updateTeamButtons();
	}

	private void updateTeamButtons() {
		if (joinButton != null) joinButton.active = !joinCode.isBlank() && !joinAddress.isBlank();
	}

	private void extractTeam(GuiGraphicsExtractor g) {
		int x = left + 8;
		int y = contentY + 2;
		if (!Team.isActive()) {
			g.textWithWordWrap(font, Component.literal("Bots on several accounts can share work. One computer hosts the team (usually the one"
					+ " starting the job); the others join with its address and code. Every bot must be in the same world.\n\nA build you"
					+ " start is split: you place, one teammate digs out the site and then gathers, the rest gather materials into a chest"
					+ " next to the site. Collect jobs are split by amount."), x, y, colW, Widgets.GRAY);
			g.text(font, "Join a team", rightX, contentY + 50, Widgets.WHITE);
			g.text(font, "Address (host:port)", rightX, contentY + 62, Widgets.GRAY);
			g.text(font, "Code", rightX, contentY + 94, Widgets.GRAY);
			return;
		}
		if (Team.isLeader()) {
			g.text(font, "Hosting a team", rightX, y, Widgets.WHITE);
			g.text(font, "Address: " + Team.shareAddress(), rightX, y + LINE + 2, 0xFF7FE0FF);
			g.text(font, "Code: " + Team.code(), rightX, y + 2 * LINE + 2, 0xFF7FE0FF);
			g.textWithWordWrap(font, Component.literal("Teammates on this PC can use 127.0.0.1. Start a build or a collect job as usual and it's"
					+ " shared with everyone here."), rightX, y + 3 * LINE + 8, colW, Widgets.GRAY);
		} else {
			g.text(font, Team.isMember() ? "In " + Team.leaderName() + "'s team" : "Joining...", rightX, y, Widgets.WHITE);
			g.textWithWordWrap(font, Component.literal("The leader hands out the work; this bot follows its orders."), rightX, y + LINE + 2, colW,
					Widgets.GRAY);
		}
		g.text(font, "Members", x, y, Widgets.WHITE);
		y += LINE + 2;
		LocalPlayer p = minecraft.player;
		for (Team.MemberView m : Team.roster()) {
			if (y > bottomY + 6) break;
			String head = m.name() + (m.role().isEmpty() ? "" : " · " + m.role());
			if (m.pos() != null && p != null) head += " · " + (int) Math.sqrt(m.pos().distSqr(p.blockPosition())) + "m";
			g.text(font, font.plainSubstrByWidth(head, colW), x, y, Widgets.WHITE);
			g.text(font, font.plainSubstrByWidth(m.activity(), colW - 6), x + 6, y + LINE, Widgets.GRAY);
			y += 2 * LINE + 3;
		}
	}

	// ---- bot ------------------------------------------------------------------------------------

	private void initBot() {
		Bot bot = Bot.get();
		int half = colW / 2 - 2;
		pauseButton = addRenderableWidget(Button.builder(Component.literal("Pause"), b -> {
			bot.setPaused(!bot.isPaused());
			updateBotButtons();
		}).bounds(rightX, contentY, half, 18).build());
		addRenderableWidget(Button.builder(Component.literal("Stop"), b -> bot.stop("from the panel")).bounds(rightX + half + 4, contentY, half, 18).build());
		addRenderableWidget(Button.builder(Component.literal("Set base here"), b -> {
			LocalPlayer p = minecraft.player;
			if (p == null) return;
			bot.setBase(p.blockPosition());
			Chat.info("Base set to " + p.blockPosition().toShortString() + ". Chests within 8 blocks will be used for storage.");
		}).tooltip(Tooltip.create(Component.literal("Chests near here receive the loot; finished jobs are delivered here")))
				.bounds(rightX, contentY + 22, colW, 18).build());
		logoffButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
			bot.setLogoffWhenDone(!bot.logoffWhenDone());
			updateBotButtons();
		}).bounds(rightX, contentY + 44, colW, 18).build());
		hudButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
			HudOverlay.setEnabled(!HudOverlay.enabled());
			updateBotButtons();
		}).bounds(rightX, contentY + 66, colW, 18).build());
		updateBotButtons();
	}

	private void updateBotButtons() {
		if (pauseButton == null || logoffButton == null || hudButton == null) return;
		Bot bot = Bot.get();
		pauseButton.active = bot.isRunning();
		pauseButton.setMessage(Component.literal(bot.isPaused() ? "Resume" : "Pause"));
		logoffButton.setMessage(Component.literal("Log off when done: " + (bot.logoffWhenDone() ? "ON" : "OFF")));
		hudButton.setMessage(Component.literal("Status overlay: " + (HudOverlay.enabled() ? "ON" : "OFF")));
	}

	private void extractBot(GuiGraphicsExtractor g) {
		Bot bot = Bot.get();
		LocalPlayer p = minecraft.player;
		int x = left + 8;
		int y = contentY + 2;
		String state = !bot.isRunning() ? "Idle" : bot.isPaused() ? "Paused" : "Running";
		g.text(font, state + (bot.isRunning() ? " · " + bot.activeBehavior() : ""), x, y, Widgets.WHITE);
		y += LINE;
		if (bot.isRunning() && !bot.status().isEmpty()) {
			y = g.textWithWordWrap(font, Component.literal(bot.status()), x, y, colW, Widgets.GRAY) + 2;
		}
		Job job = bot.job();
		if (job != null && p != null) {
			y += 2;
			for (Requirement r : job.requirements()) {
				if (y > bottomY - 30) break;
				Widgets.progressRow(g, font, x, y, colW, ItemCatalog.iconOf(r), r.name() + (r.isCrafted() ? " (make)" : ""),
						Math.min(job.have(p, r), r.amount()), r.amount(), job.isSkipped(r));
				y += 12;
			}
		}
		if (bot.buildOrder() != null) {
			Widgets.progressRow(g, font, x, y, colW, null, "build " + bot.buildOrder().schematic().name(), bot.buildPlaced(),
					Math.max(1, bot.buildTotal()), false);
			y += 12;
		}
		Requirement need = bot.activeNeed();
		if (need != null) {
			g.text(font, font.plainSubstrByWidth("Getting first: " + need, colW), x, y + 2, 0xFFFFD27F);
			y += LINE + 2;
		}
		if (p != null) {
			int vy = top + panelH - 24;
			g.text(font, String.format("Health %.0f/20 · Food %d", p.getHealth(), p.getFoodData().getFoodLevel()), x, vy, Widgets.GRAY);
			BlockPos base = bot.base();
			g.text(font, font.plainSubstrByWidth("At " + p.blockPosition().toShortString() + " · base "
					+ (base == null ? "not set" : base.toShortString()), colW), x, vy + LINE, Widgets.GRAY);
		}

		int sy = contentY + 92;
		List<StructureRegistry.Entry> built = StructureRegistry.all();
		g.text(font, "Built (" + built.size() + ")", rightX, sy, Widgets.WHITE);
		sy += LINE;
		for (StructureRegistry.Entry e : built) {
			if (sy > top + panelH - 14) break;
			String line = e.schematic() + " @ " + e.origin().toShortString() + (e.provides() == null ? "" : " → " + e.provides());
			g.text(font, font.plainSubstrByWidth(line, colW), rightX, sy, Widgets.GRAY);
			sy += LINE;
		}
	}

	private static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}
}
