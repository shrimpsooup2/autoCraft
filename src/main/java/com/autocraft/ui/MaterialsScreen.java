package com.autocraft.ui;

import com.autocraft.build.MaterialSettings;
import com.autocraft.build.Placement;
import com.autocraft.build.Substitution;
import com.autocraft.schematic.Schematic;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;

/**
 * What the bot may change when building: wood type (auto = what grows here), a stand-in for structural wood
 * (auto = only where no trees grow), filler blocks for cells nobody sees, and explicit block replacements.
 * Edits the placement being positioned (the ghost updates live), or the saved defaults without one.
 */
public final class MaterialsScreen extends Screen {
	private static final int SLOT = 18;
	private static @Nullable List<Block> allBlocks;

	private final Screen parent;
	private final @Nullable Placement placement;
	private final MaterialSettings settings;
	private final List<Block> schematicBlocks = new ArrayList<>();
	private final Map<Block, Integer> schematicCounts = new HashMap<>();

	private int left;
	private int top;
	private int panelW;
	private int panelH;
	private int colW;
	private int rightX;
	private int gridY;
	private int gridRows;
	private int cols;
	private int leftScroll;
	private int rightScroll;
	private List<Block> results = List.of();
	private @Nullable Block selectedFrom;
	private String search = "";

	public MaterialsScreen(Screen parent, @Nullable Placement placement) {
		super(Component.literal("autoCraft materials"));
		this.parent = parent;
		this.placement = placement;
		this.settings = placement != null ? placement.materials().copy() : MaterialSettings.defaults();
		if (placement != null) {
			Schematic s = placement.schematic();
			for (Schematic.Cell c : s.cells()) {
				Block b = c.state().getBlock();
				if (b == Blocks.AIR || b.asItem() == net.minecraft.world.item.Items.AIR) continue;
				if (schematicCounts.merge(b, 1, Integer::sum) == 1) schematicBlocks.add(b);
			}
			schematicBlocks.sort((a, b) -> schematicCounts.get(b) - schematicCounts.get(a));
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
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
		colW = (panelW - 24) / 2;
		rightX = left + 16 + colW;
		cols = Math.max(1, colW / SLOT);
		gridY = top + 64;
		gridRows = Math.max(1, (top + panelH - 76 - gridY) / SLOT);

		int third = (panelW - 24) / 3;
		addRenderableWidget(cycle(this::woodLabel, () -> settings.wood = next(MaterialSettings.woodChoices(), settings.wood),
				"auto: the wood growing around here (birch forest → birch); exact: as designed; or one wood type for everything")
				.bounds(left + 8, top + 22, third, 18).build());
		addRenderableWidget(cycle(this::standInLabel, () -> settings.standIn = next(MaterialSettings.standInChoices(), settings.standIn),
				"Stone-type blocks for planks, logs, stairs, slabs and fences (doors, chests, etc. stay wood). auto: only when no trees grow"
						+ " nearby (sandstone in deserts, red sandstone in badlands, blackstone in the Nether)")
				.bounds(left + 12 + third, top + 22, third, 18).build());
		addRenderableWidget(cycle(this::fillLabel, () -> settings.fillInterior = !settings.fillInterior,
				"Cells surrounded by blocks on all six sides can't be seen: build them from a filler block (the one you carry most of)")
				.bounds(left + 16 + 2 * third, top + 22, third, 18).build());

		EditBox box = addRenderableWidget(new EditBox(font, rightX, top + 46, colW, 14, Component.literal("Search blocks")));
		box.setHint(Component.literal("Search blocks...").withStyle(ChatFormatting.DARK_GRAY));
		box.setValue(search);
		box.setResponder(s -> {
			search = s;
			refresh();
		});
		refresh();

		int bw = 90;
		addRenderableWidget(Button.builder(Component.literal("Save as default"), b -> {
			settings.saveAsDefault();
			apply();
		}).tooltip(Tooltip.create(Component.literal("Use these choices for every new placement"))).bounds(left + panelW - 2 * bw - 12, top + panelH - 22, bw,
				18).build());
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(left + panelW - bw - 8, top + panelH - 22, bw, 18).build());
	}

	private Button.Builder cycle(java.util.function.Supplier<String> label, Runnable action, String tooltip) {
		return Button.builder(Component.literal(label.get()), b -> {
			action.run();
			b.setMessage(Component.literal(label.get()));
			apply();
		}).tooltip(Tooltip.create(Component.literal(tooltip)));
	}

	private static String next(List<String> choices, String current) {
		int i = choices.indexOf(current);
		return choices.get((i + 1) % choices.size());
	}

	private String woodLabel() {
		return "Wood: " + settings.wood.replace('_', ' ');
	}

	private String standInLabel() {
		String v = settings.standIn;
		return "Stand-in: " + (v.equals(MaterialSettings.AUTO) || v.equals(MaterialSettings.OFF) ? v : Substitution.StandIn.valueOf(v).label().toLowerCase());
	}

	private String fillLabel() {
		return "Fill hidden: " + (settings.fillInterior ? "on" : "off");
	}

	/** Push the edited settings to the placement (the ghost re-renders with the new materials). */
	private void apply() {
		if (placement != null) placement.setMaterials(settings.copy());
	}

	@Override
	public void onClose() {
		apply();
		minecraft.gui.setScreen(parent);
	}

	private void refresh() {
		String q = search.trim().toLowerCase(Locale.ROOT);
		List<Block> out = new ArrayList<>();
		for (Block b : blocks()) {
			if (q.isEmpty() || b.getName().getString().toLowerCase(Locale.ROOT).contains(q) || Substitution.id(b).contains(q.replace(' ', '_'))) out.add(b);
		}
		results = out;
		rightScroll = 0;
	}

	private static List<Block> blocks() {
		if (allBlocks == null) {
			List<Block> out = new ArrayList<>();
			for (Item item : BuiltInRegistries.ITEM) {
				if (item instanceof BlockItem bi && bi.getBlock() != Blocks.AIR) out.add(bi.getBlock());
			}
			allBlocks = List.copyOf(out);
		}
		return allBlocks;
	}

	// ---- input ----------------------------------------------------------------------------------

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) return true;
		double mx = event.x();
		double my = event.y();
		Block from = slotAt(schematicBlocks, leftScroll, left + 8, mx, my);
		if (from != null) {
			selectedFrom = from == selectedFrom ? null : from;
			return true;
		}
		Block to = slotAt(results, rightScroll, rightX, mx, my);
		if (to != null) {
			if (selectedFrom != null) {
				if (to == selectedFrom) settings.overrides.remove(selectedFrom);
				else settings.overrides.put(selectedFrom, to);
				selectedFrom = null;
			} else if (!settings.fillers.contains(to)) {
				settings.fillers.add(to);
			}
			apply();
			return true;
		}
		int filler = fillerAt(mx, my);
		if (filler >= 0) {
			settings.fillers.remove(filler);
			apply();
			return true;
		}
		Block replaced = overrideAt(mx, my);
		if (replaced != null) {
			settings.overrides.remove(replaced);
			apply();
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		int dir = (int) Math.signum(scrollY);
		if (inside(x, y, left + 8, gridY, cols * SLOT, gridRows * SLOT)) {
			leftScroll = clampScroll(leftScroll - dir, schematicBlocks.size());
			return true;
		}
		if (inside(x, y, rightX, gridY, cols * SLOT, gridRows * SLOT)) {
			rightScroll = clampScroll(rightScroll - dir, results.size());
			return true;
		}
		return super.mouseScrolled(x, y, scrollX, scrollY);
	}

	private int clampScroll(int value, int size) {
		return Math.max(0, Math.min(value, Math.max(0, (size + cols - 1) / cols - gridRows)));
	}

	private @Nullable Block slotAt(List<Block> list, int scroll, int x0, double mx, double my) {
		if (!inside(mx, my, x0, gridY, cols * SLOT, gridRows * SLOT)) return null;
		int i = (scroll + (int) (my - gridY) / SLOT) * cols + (int) (mx - x0) / SLOT;
		return i < list.size() ? list.get(i) : null;
	}

	private int fillerY() {
		return gridY + gridRows * SLOT + 6;
	}

	private int fillerAt(double mx, double my) {
		int y = fillerY();
		int x0 = left + 8 + font.width("Fillers: ");
		if (my < y || my >= y + 12 || mx < x0) return -1;
		int i = (int) (mx - x0) / 12;
		return i < settings.fillers.size() ? i : -1;
	}

	private @Nullable Block overrideAt(double mx, double my) {
		int y = fillerY() + 14;
		int x0 = left + 8 + font.width("Replace: ");
		if (my < y || my >= y + 12 || mx < x0) return null;
		int i = (int) (mx - x0) / 34;
		List<Block> keys = new ArrayList<>(settings.overrides.keySet());
		return i < keys.size() ? keys.get(i) : null;
	}

	// ---- drawing --------------------------------------------------------------------------------

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		g.fill(left, top, left + panelW, top + panelH, 0xE0101010);
		g.outline(left, top, panelW, panelH, 0xFF505050);
		g.text(font, "Materials" + (placement == null ? " (defaults)" : " · " + placement.schematic().name()), left + 8, top + 8, Widgets.WHITE);

		g.text(font, placement == null ? "Place a schematic to pick replacements" : selectedFrom == null ? "Schematic blocks — click one to replace it"
				: "Now pick its replacement on the right →", left + 8, top + 50, selectedFrom == null ? Widgets.GRAY : 0xFF7FE0FF);
		drawGrid(g, schematicBlocks, leftScroll, left + 8, mouseX, mouseY, true);
		drawGrid(g, results, rightScroll, rightX, mouseX, mouseY, false);

		int y = fillerY();
		g.text(font, "Fillers: ", left + 8, y + 2, Widgets.GRAY);
		int x = left + 8 + font.width("Fillers: ");
		for (Block b : settings.fillers) {
			Widgets.smallItem(g, b.asItem(), x, y + 1);
			x += 12;
		}
		if (settings.fillers.isEmpty()) g.text(font, "none — click blocks on the right (with nothing selected) to add", x, y + 2, Widgets.GRAY);

		y += 14;
		g.text(font, "Replace: ", left + 8, y + 2, Widgets.GRAY);
		x = left + 8 + font.width("Replace: ");
		for (Map.Entry<Block, Block> e : settings.overrides.entrySet()) {
			Widgets.smallItem(g, e.getKey().asItem(), x, y + 1);
			g.text(font, "→", x + 11, y + 2, Widgets.GRAY);
			Widgets.smallItem(g, e.getValue().asItem(), x + 19, y + 1);
			x += 34;
		}
		if (settings.overrides.isEmpty()) g.text(font, "none", x, y + 2, Widgets.GRAY);

		String result = placement != null ? placement.substitution().reason() : "applies to new placements";
		g.text(font, font.plainSubstrByWidth("Result: " + result, panelW - 2 * 90 - 30), left + 8, top + panelH - 17, 0xFF9CE29C);
		g.text(font, font.plainSubstrByWidth("Click a filler or replacement to remove it.", colW), rightX, fillerY() + 2, Widgets.GRAY);
		super.extractRenderState(g, mouseX, mouseY, a);
	}

	private void drawGrid(GuiGraphicsExtractor g, List<Block> list, int scroll, int x0, int mouseX, int mouseY, boolean schematicSide) {
		for (int r = 0; r < gridRows; r++) {
			for (int c = 0; c < cols; c++) {
				int i = (scroll + r) * cols + c;
				int sx = x0 + c * SLOT;
				int sy = gridY + r * SLOT;
				boolean hover = i < list.size() && inside(mouseX, mouseY, sx, sy, SLOT, SLOT);
				Widgets.slot(g, sx, sy, hover);
				if (i >= list.size()) continue;
				Block b = list.get(i);
				g.item(b.asItem().getDefaultInstance(), sx + 1, sy + 1);
				if (schematicSide && b == selectedFrom) g.outline(sx, sy, SLOT, SLOT, 0xFF7FE0FF);
				Block to = schematicSide ? settings.overrides.get(b) : null;
				if (to != null) Widgets.smallItem(g, to.asItem(), sx + 9, sy + 9);
				if (hover) {
					List<Component> tip = new ArrayList<>();
					tip.add(Component.literal(b.getName().getString()));
					if (schematicSide) {
						tip.add(Component.literal(schematicCounts.get(b) + " in the schematic").withStyle(ChatFormatting.GRAY));
						if (to != null) tip.add(Component.literal("→ " + to.getName().getString()).withStyle(ChatFormatting.AQUA));
					} else {
						tip.add(Component.literal(selectedFrom != null ? "Click: use instead of " + selectedFrom.getName().getString() : "Click: add as filler")
								.withStyle(ChatFormatting.GRAY));
					}
					g.setComponentTooltipForNextFrame(font, tip, mouseX, mouseY);
				}
			}
		}
	}

	private static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}
}
