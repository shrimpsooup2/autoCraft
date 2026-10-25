package com.autocraft.ui;

import com.autocraft.Bases;
import com.autocraft.Bot;
import com.autocraft.Chat;
import com.autocraft.DeathLog;
import com.autocraft.Settings;
import com.autocraft.build.BuildPlan;
import com.autocraft.build.Placement;
import com.autocraft.build.SiteFinder;
import com.autocraft.build.StructureRegistry;
import com.autocraft.goal.Job;
import com.autocraft.goal.Requirement;
import com.autocraft.schematic.Schematic;
import com.autocraft.schematic.SchematicLibrary;
import com.autocraft.team.Team;
import com.mojang.blaze3d.Blaze3D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
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
 * The bot's control panel (default key K). Tasks: everything the bot is asked to do as a tree — pick items to
 * collect, add groups and builds, edit, run ({@link TaskTreePanel}). Build: pick a schematic (built-in or from the
 * folder) and place it in the world as a ghost, Litematica-style — exact coordinates, nudges, rotation, mirror —
 * then build it. Team: host or join a team of bots. Bot: live status, pause/stop, base. Settings: saved options
 * (log off, overlay, smooth aim, players nearby, after death, base, resume).
 */
public final class AutoCraftScreen extends Screen {
	private enum Tab {
		TASKS("Tasks"), BUILD("Build"), TEAM("Team"), BOT("Bot"), SETTINGS("Settings");

		final String label;

		Tab(String label) {
			this.label = label;
		}
	}

	private static final int LINE = 11;

	/** Tab button pitch (the tabs fit next to the title in the 420-wide panel). */
	private static final int TAB_W = 50;

	// remembered between openings
	private static Tab tab = Tab.TASKS;
	private static @Nullable String selectedSchematic;

	private int left;
	private int top;
	private int panelW;
	private int panelH;
	private int colW;
	private int rightX;
	private int contentY;
	private int bottomY;

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

	// tasks tab
	private @Nullable TaskTreePanel tasks;

	// settings tab
	private static final int[] PLAYER_RADII = {16, 32, 48, 64, 96, 128};
	private final List<Runnable> settingLabels = new ArrayList<>();

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
		int tabX = left + panelW - Tab.values().length * TAB_W - 4;
		for (Tab t : Tab.values()) {
			Button b = addRenderableWidget(Button.builder(Component.literal(t.label), btn -> {
				tab = t;
				rebuildWidgets();
			}).bounds(tabX + t.ordinal() * TAB_W, top + 4, TAB_W - 2, 16).build());
			b.active = t != tab;
		}
		tasks = null;
		switch (tab) {
			case TASKS -> {
				tasks = new TaskTreePanel(font, left, rightX, colW, contentY, bottomY, this::addRenderableWidget, this::rebuildWidgets);
				tasks.init();
				if (tasks.focus() != null) setInitialFocus(tasks.focus());
			}
			case BUILD -> initBuild();
			case TEAM -> initTeam();
			case BOT -> initBot();
			case SETTINGS -> initSettings();
		}
	}

	@Override
	public void tick() {
		switch (tab) {
			case TASKS -> {
				if (tasks != null) tasks.tick();
			}
			case BUILD -> updateBuildButtons();
			case TEAM -> updateTeamButtons();
			case BOT -> updateBotButtons();
			case SETTINGS -> {
			}
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
		g.text(font, font.plainSubstrByWidth(state, left + panelW - Tab.values().length * TAB_W - 8 - stateX), stateX, top + 8, Widgets.GRAY);
		switch (tab) {
			case TASKS -> {
				if (tasks != null) tasks.extract(g, mouseX, mouseY);
			}
			case BUILD -> extractBuild(g, mouseX, mouseY);
			case TEAM -> extractTeam(g);
			case BOT -> extractBot(g);
			case SETTINGS -> extractSettings(g);
		}
		super.extractRenderState(g, mouseX, mouseY, a);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) return true;
		double mx = event.x();
		double my = event.y();
		if (tab == Tab.TASKS && tasks != null) {
			return tasks.click(mx, my, event.button());
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
		if (tab == Tab.TASKS && tasks != null && tasks.scroll(x, y, dir)) {
			return true;
		} else if (tab == Tab.BUILD && inside(x, y, left + 8, listY, colW, listRows * LINE)) {
			listScroll = Math.max(0, Math.min(Math.max(0, schematics.size() - listRows), listScroll - dir));
			return true;
		}
		return super.mouseScrolled(x, y, scrollX, scrollY);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (tab == Tab.TASKS && tasks != null && tasks.keyPressed(event)) return true;
		return super.keyPressed(event);
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
		int halfW = colW / 2 - 2;
		addRenderableWidget(Button.builder(Component.literal("Add base here"), b -> {
			LocalPlayer p = minecraft.player;
			if (p == null) return;
			String name = bot.setBase(p.blockPosition());
			Chat.info("Base \"" + name + "\" at " + p.blockPosition().toShortString() + ". Chests within 8 blocks of it are used for storage"
					+ " (rename: !ac base <name>).");
		}).tooltip(Tooltip.create(Component.literal("Several bases are fine (one per area or dimension): the nearest one in this dimension is used")))
				.bounds(rightX, contentY + 22, halfW, 18).build());
		addRenderableWidget(Button.builder(Component.literal("Remove nearest"), b -> {
			LocalPlayer p = minecraft.player;
			Bases.Base nearest = p == null ? null : Bases.inDimension(bot.dimension()).stream()
					.min(java.util.Comparator.comparingDouble(x -> x.pos().distSqr(p.blockPosition()))).orElse(null);
			if (nearest != null && Bases.remove(nearest.name())) Chat.info("Removed base \"" + nearest.name() + "\".");
		}).bounds(rightX + halfW + 4, contentY + 22, halfW, 18).build());
		updateBotButtons();
	}

	private void updateBotButtons() {
		if (pauseButton == null) return;
		Bot bot = Bot.get();
		pauseButton.active = bot.isRunning();
		pauseButton.setMessage(Component.literal(bot.isPaused() ? "Resume" : "Pause"));
	}

	// ---- settings -------------------------------------------------------------------------------

	private void initSettings() {
		settingLabels.clear();
		Settings s = Settings.get();
		int y = contentY + 14;
		toggle(left + 8, y, "Log off when done", "Disconnect once the job is finished and the loot is stored",
				() -> s.logoffWhenDone, v -> s.logoffWhenDone = v);
		toggle(left + 8, y + 22, "Status overlay", "Small activity and progress panel at the top left while the bot runs",
				() -> s.statusOverlay, v -> s.statusOverlay = v);
		toggle(left + 8, y + 44, "Smooth aim", "Turn the view along curved, eased paths like a hand on a mouse instead of snapping",
				() -> s.smoothAim, v -> s.smoothAim = v);
		toggle(left + 8, y + 66, "After death: recover", "Go back for the dropped items and carry on with the job (off: stop the job)",
				() -> s.recoverAfterDeath, v -> s.recoverAfterDeath = v);
		toggle(rightX, y, "Log off near players", "Disconnect as soon as another player comes within the radius below",
				() -> s.logoffNearPlayers, v -> s.logoffNearPlayers = v);
		toggle(rightX, y + 44, "Materials at base", "The base chests hold materials: look there before gathering, even in chests not looked into yet",
				() -> s.materialsAtBase, v -> s.materialsAtBase = v);
		toggle(rightX, y + 66, "Resume after relog", "Pick the job or build back up after a relog or crash (progress is saved every few seconds)",
				() -> s.resumeAfterRelog, v -> s.resumeAfterRelog = v);
		Button radius = addRenderableWidget(Button.builder(Component.empty(), b -> {
			int next = PLAYER_RADII[0];
			for (int r : PLAYER_RADII) {
				if (r > s.playerAlertRadius) {
					next = r;
					break;
				}
			}
			s.playerAlertRadius = next;
			s.save();
			updateSettingsButtons();
		}).tooltip(Tooltip.create(Component.literal("How close another player may come (click to change)"))).bounds(rightX, y + 22, colW, 18).build());
		settingLabels.add(() -> radius.setMessage(Component.literal("Player radius: " + s.playerAlertRadius + " blocks")));
		updateSettingsButtons();
	}

	private void toggle(int x, int y, String label, String tip, BooleanSupplier get, Consumer<Boolean> set) {
		Button b = addRenderableWidget(Button.builder(Component.empty(), btn -> {
			set.accept(!get.getAsBoolean());
			Settings.get().save();
			updateSettingsButtons();
		}).tooltip(Tooltip.create(Component.literal(tip))).bounds(x, y, colW, 18).build());
		settingLabels.add(() -> b.setMessage(Component.literal(label + ": " + (get.getAsBoolean() ? "ON" : "OFF"))));
	}

	private void updateSettingsButtons() {
		for (Runnable r : settingLabels) r.run();
	}

	private void extractSettings(GuiGraphicsExtractor g) {
		g.text(font, "Saved to config/autocraft/settings.json", left + 8, contentY + 2, Widgets.GRAY);
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
			DeathLog.Death death = DeathLog.last();
			if (death != null) {
				String where = "Last death: " + death.pos().toShortString() + " (" + death.dim().name().toLowerCase() + ", " + death.cause() + ")"
						+ (bot.recovery() != null ? " · recovering" : "");
				g.text(font, font.plainSubstrByWidth(where, colW), x, vy - LINE, 0xFFFF9F9F);
			}
			g.text(font, String.format("Health %.0f/20 · Food %d", p.getHealth(), p.getFoodData().getFoodLevel()), x, vy, Widgets.GRAY);
			BlockPos base = bot.base();
			g.text(font, font.plainSubstrByWidth("At " + p.blockPosition().toShortString() + " · base "
					+ (base == null ? "not set" : base.toShortString()), colW), x, vy + LINE, Widgets.GRAY);
		}

		int sy = contentY + 48;
		List<Bases.Base> bases = Bases.all();
		g.text(font, "Bases (" + bases.size() + ")", rightX, sy, Widgets.WHITE);
		sy += LINE;
		for (Bases.Base b : bases) {
			if (sy > contentY + 48 + 4 * LINE) break;
			g.text(font, font.plainSubstrByWidth(b.name() + " @ " + b.pos().toShortString() + " (" + b.dim().name().toLowerCase() + ")", colW), rightX, sy,
					Widgets.GRAY);
			sy += LINE;
		}
		sy += 2;
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
