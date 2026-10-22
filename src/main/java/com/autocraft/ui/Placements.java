package com.autocraft.ui;

import com.autocraft.Bot;
import com.autocraft.Chat;
import com.autocraft.build.BuildPlan;
import com.autocraft.build.Placement;
import com.autocraft.path.Navigator;
import com.autocraft.schematic.Schematic;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jspecify.annotations.Nullable;

/**
 * Litematica-style placement: a schematic follows keyboard/mouse adjustments as a ghost in the world until it is
 * confirmed (the bot builds it) or cancelled. Keys (rebindable under Controls → autoCraft):
 * arrows move it relative to where you face, Page Up/Down move it up/down, Alt + mouse wheel nudges it toward
 * where you look, R rotates (Shift+R the other way), M mirrors, G moves it to the block you look at, Enter builds,
 * Backspace cancels.
 */
public final class Placements {
	private static final int SHIFT_STEP = 8;

	private static @Nullable Placement active;
	private static KeyMapping forward;
	private static KeyMapping back;
	private static KeyMapping left;
	private static KeyMapping right;
	private static KeyMapping up;
	private static KeyMapping down;
	private static KeyMapping nudge;
	private static KeyMapping rotate;
	private static KeyMapping mirror;
	private static KeyMapping grab;
	private static KeyMapping confirm;
	private static KeyMapping cancel;

	private Placements() {}

	public static void registerKeys(KeyMapping.Category category) {
		forward = key("move_forward", InputConstants.KEY_UP, category);
		back = key("move_back", InputConstants.KEY_DOWN, category);
		left = key("move_left", InputConstants.KEY_LEFT, category);
		right = key("move_right", InputConstants.KEY_RIGHT, category);
		up = key("move_up", InputConstants.KEY_PAGEUP, category);
		down = key("move_down", InputConstants.KEY_PAGEDOWN, category);
		nudge = key("nudge_modifier", InputConstants.KEY_LALT, category);
		rotate = key("rotate", InputConstants.KEY_R, category);
		mirror = key("mirror", InputConstants.KEY_M, category);
		grab = key("move_to_crosshair", InputConstants.KEY_G, category);
		confirm = key("confirm", InputConstants.KEY_RETURN, category);
		cancel = key("cancel", InputConstants.KEY_BACKSPACE, category);
	}

	private static KeyMapping key(String name, int code, KeyMapping.Category category) {
		return KeyMappingHelper.registerKeyMapping(new KeyMapping("key.autocraft.placement." + name, code, category));
	}

	public static @Nullable Placement active() {
		return active;
	}

	/** Start positioning {@code s}, centered on the block you look at (or just in front of you). */
	public static Placement start(Schematic s) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		String wood = s.builtIn() && p != null ? BuildPlan.preferredWood(p) : null;
		Placement placement = new Placement(s, BlockPos.ZERO, wood);
		placement.centerOn(target(mc));
		active = placement;
		Chat.info("Placing " + s.name() + ": arrows/PgUp/PgDn or Alt+wheel move it, R rotates, M mirrors, G moves it to the crosshair, "
				+ "Enter builds, Backspace cancels (or use the panel).");
		return placement;
	}

	public static void cancel() {
		active = null;
	}

	/** Hand the placement to the bot. */
	public static void confirm() {
		Placement p = active;
		if (p == null) return;
		active = null;
		Bot.get().startBuild(p.toOrder());
	}

	/** Move to the crosshair target: footprint centered on it, bottom layer on top of the looked-at face. */
	public static void moveToCrosshair() {
		if (active != null) active.centerOn(target(Minecraft.getInstance()));
	}

	public static void moveToPlayer() {
		LocalPlayer p = Minecraft.getInstance().player;
		if (active != null && p != null) active.setOrigin(Navigator.feet(p));
	}

	private static BlockPos target(Minecraft mc) {
		HitResult hit = mc.hitResult;
		if (hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK) return b.getBlockPos().relative(b.getDirection());
		LocalPlayer p = mc.player;
		return p == null ? BlockPos.ZERO : Navigator.feet(p).relative(p.getDirection(), 3);
	}

	/** Client tick: placement keys (only while a placement exists and no screen is open). */
	public static void tick(Minecraft mc) {
		Placement p = active;
		LocalPlayer player = mc.player;
		if (p == null || player == null) {
			drain();
			return;
		}
		int step = player.isShiftKeyDown() ? SHIFT_STEP : 1;
		Direction facing = player.getDirection();
		while (forward.consumeClick()) p.move(facing.getStepX() * step, 0, facing.getStepZ() * step);
		while (back.consumeClick()) p.move(-facing.getStepX() * step, 0, -facing.getStepZ() * step);
		Direction leftDir = facing.getCounterClockWise();
		while (left.consumeClick()) p.move(leftDir.getStepX() * step, 0, leftDir.getStepZ() * step);
		while (right.consumeClick()) p.move(-leftDir.getStepX() * step, 0, -leftDir.getStepZ() * step);
		while (up.consumeClick()) p.move(0, step, 0);
		while (down.consumeClick()) p.move(0, -step, 0);
		while (rotate.consumeClick()) p.rotate(!player.isShiftKeyDown());
		while (mirror.consumeClick()) p.cycleMirror();
		while (grab.consumeClick()) moveToCrosshair();
		while (confirm.consumeClick()) confirm();
		while (cancel.consumeClick()) {
			cancel();
			Chat.info("Placement cancelled.");
		}
	}

	private static void drain() {
		for (KeyMapping k : List.of(forward, back, left, right, up, down, rotate, mirror, grab, confirm, cancel)) {
			while (k.consumeClick()) {
				// keys are only meaningful while placing
			}
		}
	}

	/** Mouse wheel with the nudge modifier held: move one block toward (or away from) where you look. */
	public static boolean onScroll(double amount) {
		Minecraft mc = Minecraft.getInstance();
		if (active == null || mc.player == null || mc.gui.screen() != null || !nudge.isDown() || amount == 0) return false;
		Direction d = Direction.getApproximateNearest(mc.player.getLookAngle());
		int sign = amount > 0 ? 1 : -1;
		active.move(d.getStepX() * sign, d.getStepY() * sign, d.getStepZ() * sign);
		return true;
	}

	/** Bottom-center hint while placing. */
	public static void extractHud(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		Placement p = active;
		if (p == null || mc.gui.screen() != null) return;
		Font font = mc.font;
		String head = p.schematic().name() + " @ " + p.origin().toShortString() + " · rotation " + Placement.describe(p.rotation())
				+ " · mirror " + Placement.describe(p.mirror());
		String keys = "arrows/PgUp/PgDn or Alt+wheel: move · R: rotate · M: mirror · G: to crosshair · Enter: build · Backspace: cancel";
		int w = g.guiWidth();
		int y = g.guiHeight() - 72;
		int width = Math.max(font.width(head), font.width(keys)) + 8;
		g.fill(w / 2 - width / 2, y - 3, w / 2 + width / 2, y + 21, 0x90000000);
		g.centeredText(font, head, w / 2, y, 0xFF7FE0FF);
		g.centeredText(font, keys, w / 2, y + 11, 0xFFB0B0B0);
	}
}
