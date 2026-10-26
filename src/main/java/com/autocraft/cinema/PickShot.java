package com.autocraft.cinema;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * How to use it. Over your shoulder: K is pressed and the Tasks panel unfolds in front of you as a glowing glass
 * screen. You tap the search box, "diamond" types in, 32 goes in the amount box, you tap the diamond, open its
 * plan (every step down to chopping a tree unfolds) and tap Run all. The panel folds away, your status light
 * flips from red to green, and you walk off on your own. The same job as a chat command types in below.
 */
final class PickShot extends Shot {
	private static final double W = 2.2, H = 1.25;
	private static final int CYAN = 0x39D0FF, AMBER = 0xFFBF3C, GREEN = 0x5EE36A;
	private static final String SEARCH = "diamond", COMMAND = "!ac collect diamond 32";
	private static final double KEY = 1.8, OPEN = 2.2, TYPE = 3.6, AMOUNT = 5.4, ADD = 6.3, UNFOLD = 7.4, RUN = 11.6, FOLD = 12.6, GO = 13.6;
	private static final double ROW_GAP = 0.36;

	private record Row(int depth, String title, Item icon, String how) {}

	private static final List<Row> PLAN = List.of(new Row(1, "1 Iron Pickaxe", Items.IRON_PICKAXE, "craft"),
			new Row(2, "3 Iron Ingot", Items.IRON_INGOT, "smelt"), new Row(3, "3 Raw Iron", Items.RAW_IRON, "mine iron ore"),
			new Row(4, "1 Stone Pickaxe", Items.STONE_PICKAXE, "craft"), new Row(5, "3 Cobblestone", Items.COBBLESTONE, "mine stone"),
			new Row(6, "1 Wooden Pickaxe", Items.WOODEN_PICKAXE, "craft"), new Row(7, "3 Planks (any wood)", Items.OAK_PLANKS, "craft"),
			new Row(8, "1 Logs (any kind)", Items.OAK_LOG, "chop trees"), new Row(1, "1 Furnace", Items.FURNACE, "craft"),
			new Row(1, "1 Coal", Items.COAL, "mine coal ore"));
	private static final List<Item> ALL = List.of(Items.OAK_LOG, Items.OAK_PLANKS, Items.STICK, Items.COBBLESTONE, Items.IRON_INGOT,
			Items.DIAMOND, Items.BREAD, Items.TORCH, Items.FURNACE, Items.CHEST, Items.GLASS, Items.BRICKS, Items.IRON_PICKAXE,
			Items.WHEAT, Items.SAND, Items.COAL, Items.WOOL.pick(DyeColor.RED), Items.CRAFTING_TABLE);
	private static final List<Item> FOUND = List.of(Items.DIAMOND, Items.DIAMOND_ORE, Items.DEEPSLATE_DIAMOND_ORE, Items.DIAMOND_PICKAXE);
	/** Where the finger taps (time, panel u, panel v). */
	private static final double[][] TAPS = {{3.3, 0.70, 0.81}, {AMOUNT - 0.2, 0.93, 0.81}, {ADD, 0.556, 0.68}, {UNFOLD, 0.035, 0.76},
			{RUN, 0.62, 0.08}};

	private Actor me;
	private Vec3 screen;
	private Anim.Track walk;

	PickShot() {
		super("Pick", "Pick", 22, 1000);
	}

	@Override
	BlockPos offset() {
		return new BlockPos(-70, 0, 0);
	}

	@Override
	void build(ServerLevel level, BlockPos at) {
		int y = Stage.pad(level, at.getX(), at.getZ(), 12);
		origin = new BlockPos(at.getX(), y + 1, at.getZ());
		Sets.oak(level, at.getX() + 6, y + 1, at.getZ() + 9, 5);
		Sets.oak(level, at.getX() - 5, y + 1, at.getZ() + 11, 6);
	}

	@Override
	void start() {
		Vec3 p = v(0.5, 0, 0.5);
		me = add(Actor.me(level, p, 0));
		me.light = FilmHud.RED;
		screen = p.add(0, 1.45, 1.35);
		walk = new Anim.Track().at(GO, p).to(v(3.5, 0, 6.5), 3.6).to(v(4.5, 0, 14.5), 4.0);
		Vec3 over = p.add(-0.9, 1.85, -2.3);
		cam.key(0, over.add(-0.3, 0.2, -0.6), screen.add(0, -0.1, 0), 60)
				.key(FOLD - 1.0, over.add(0.05, 0, 0.35), screen, 56)
				.key(GO + 1.6, p.add(2.8, 1.5, 2.4), p.add(0, 1.6, 0), 60)
				.key(duration, p.add(-3.5, 4.2, -3.0), walk.last().add(0, 1.0, 0), 58);
	}

	@Override
	void tick(double t) {
		if (t < GO) {
			me.moveTo(walk.pos(0));
			Vec3 tap = null;
			for (double[] k : TAPS) {
				if (t >= k[0] - 0.35 && t <= k[0] + 0.4) tap = pt(k[1], k[2]);
				if (at(t, k[0])) me.swing();
			}
			me.look(tap != null ? tap : t > OPEN ? screen : null);
		} else {
			me.follow(walk, t);
			me.look(null);
		}
		if (at(t, GO - 0.4)) {
			me.light = GREEN;
			particles(ParticleTypes.HAPPY_VILLAGER, me.pos().add(0, 2.3, 0), 10, 0.6, 0.05);
		}
		me.lightFlash = Anim.window(t, GO - 0.5, GO - 0.35, GO - 0.2, GO + 0.4);
	}

	/** Panel scale: unfolds at OPEN, folds at FOLD. */
	private static double scale(double t) {
		return Anim.ramp(t, OPEN, OPEN + 0.7) * (1 - Anim.ramp(t, FOLD, FOLD + 0.55));
	}

	/** A point on the panel's face (u: 0 left → 1 right, v: 0 bottom → 1 top), just in front of the glass. */
	private Vec3 pt(double u, double v) {
		Vec3[] k = Fx.panelCorners(screen, 180, W, H);
		return k[0].add(k[1].subtract(k[0]).scale(u)).add(k[3].subtract(k[0]).scale(v)).add(0, 0, -0.008);
	}

	@Override
	void draw(Fx fx, double t) {
		double s = scale(t);
		if (s <= 0.001) return;
		fx.panel(screen, 180, W * s, H * (0.15 + 0.85 * s), Fx.argb(190, 0x0A1015), Fx.argb(230, CYAN), 0.012);
		double c = Anim.ramp(s, 0.85, 1.0);
		if (c <= 0) return;
		int white = Fx.argb((int) (255 * c), 0xFFFFFF), grey = Fx.argb((int) (255 * c), 0x9AA3AE), dim = Fx.argb((int) (255 * c), 0x7A8696);
		boolean running = t >= RUN;
		text(fx, 0.03, 0.95, "autoCraft", white, 1.1f);
		text(fx, 0.17, 0.95, running ? "collect" : "idle", grey, 1f);
		String[] tabs = {"Tasks", "Build", "Team", "Bot", "Settings"};
		for (int i = 0; i < tabs.length; i++) {
			double u = 0.53 + i * 0.094;
			box(fx, u, 0.915, u + 0.088, 0.985, i == 0 ? 60 : 30, c);
			text(fx, u + 0.044 - tabs[i].length() * 0.0045, 0.95, tabs[i], i == 0 ? white : grey, 0.9f);
		}
		// left: the tasks
		boolean added = t >= ADD + 0.1;
		int shown = t < UNFOLD + 0.2 ? 0 : Math.min(PLAN.size(), (int) ((t - UNFOLD - 0.2) / ROW_GAP) + 1);
		text(fx, 0.03, 0.865, running ? "Tasks — running" : added ? "Tasks — " + (1 + shown) + " to do" : "Tasks", white, 1f);
		if (!added) {
			text(fx, 0.03, 0.80, "Nothing to do yet. Pick items on the right", dim, 0.8f);
			text(fx, 0.03, 0.765, "to collect them.", dim, 0.8f);
		} else {
			double a = Anim.ramp(t, ADD + 0.1, ADD + 0.4) * c;
			row(fx, 0, 0.76, "32 Diamond", Items.DIAMOND, "mine diamond ore", running ? AMBER : 0xFFFFFF, a, true, t);
			for (int i = 0; i < shown; i++) {
				Row r = PLAN.get(i);
				double ra = Anim.ramp(t, UNFOLD + 0.2 + i * ROW_GAP, UNFOLD + 0.45 + i * ROW_GAP) * c;
				row(fx, r.depth(), 0.76 - (i + 1) * 0.06, r.title(), r.icon(), r.how(), r.depth() == 8 ? 0xFFFFFF : 0xBEC6D0, ra, false, t);
			}
		}
		// right: search, amount, the item grid
		int typed = t < TYPE ? 0 : Math.min(SEARCH.length(), (int) ((t - TYPE) / 0.17) + 1);
		box(fx, 0.52, 0.785, 0.865, 0.835, t >= 3.3 && t < AMOUNT - 0.2 ? 120 : 50, c);
		boolean caret = ((int) (t * 3)) % 2 == 0 && t < AMOUNT;
		text(fx, 0.53, 0.81, typed == 0 ? "Search items..." : SEARCH.substring(0, typed) + (caret ? "_" : ""), typed == 0 ? dim : white, 0.95f);
		box(fx, 0.875, 0.785, 0.98, 0.835, t >= AMOUNT - 0.2 && t < ADD ? 120 : 50, c);
		text(fx, 0.888, 0.81, t >= AMOUNT + 0.3 ? "32" : t >= AMOUNT ? "3" : "64", white, 0.95f);
		text(fx, 0.52, 0.755, "Adds new top-level tasks", dim, 0.75f);
		List<Item> items = typed >= 3 ? FOUND : ALL;
		for (int r = 0; r < 3; r++) {
			for (int k = 0; k < 6; k++) {
				int i = r * 6 + k;
				double u0 = 0.52 + k * 0.077, v0 = 0.62 - r * 0.13;
				boolean hot = i == 0 && items == FOUND && t > ADD - 0.4 && t < ADD + 0.3;
				box(fx, u0, v0, u0 + 0.07, v0 + 0.12, hot ? 160 : 40, c);
				if (i < items.size()) fx.item(pt(u0 + 0.035, v0 + 0.06), new ItemStack(items.get(i)), 0.055 * c, 0, true);
			}
		}
		// buttons
		String[] left = {"+ Group", "+ Build", "Clear done"};
		for (int i = 0; i < 3; i++) {
			double u = 0.03 + i * 0.152;
			box(fx, u, 0.04, u + 0.145, 0.12, 40, c);
			text(fx, u + 0.02, 0.08, left[i], white, 0.9f);
		}
		boolean pressed = t >= RUN && t < RUN + 0.25;
		box(fx, 0.52, 0.04, 0.745, 0.12, pressed ? 220 : running ? 90 : 60, c);
		text(fx, 0.58, 0.08, running ? "Stop" : "Run all", white, 1f);
		box(fx, 0.755, 0.04, 0.98, 0.12, 35, c);
		text(fx, 0.79, 0.08, "Run selected", running ? dim : white, 0.9f);
		// the finger's touch: a dot while touching and a ripple after
		for (double[] k : TAPS) {
			double since = t - k[0];
			if (since < -0.2 || since > 0.6) continue;
			if (since < 0.15) fx.spark(pt(k[1], k[2]).add(0, 0, -0.004), 0.05, Fx.argb(230, 0xBFEFFF));
			if (since >= 0) ripple(fx, k[1], k[2], since);
		}
	}

	private void ripple(Fx fx, double u, double v, double since) {
		double r = 0.01 + since * 0.08;
		int col = Fx.argb((int) (220 * (1 - since / 0.6)), CYAN);
		List<Vec3> ring = new ArrayList<>();
		for (int i = 0; i <= 24; i++) {
			double a = i * Math.PI * 2 / 24;
			ring.add(pt(u + Math.cos(a) * r * H / W, v + Math.sin(a) * r));
		}
		fx.ribbon(ring, 0.006, col, 1);
	}

	private void row(Fx fx, int depth, double v, String title, Item icon, String how, int color, double a, boolean root, double t) {
		if (a <= 0.01) return;
		double u = 0.03 + depth * 0.022;
		if (depth > 0) {   // a guide line from the parent
			double gu = 0.03 + (depth - 1) * 0.022 + 0.008;
			fx.ribbon(List.of(pt(gu, v + 0.045), pt(gu, v), pt(u + 0.004, v)), 0.0035, Fx.argb((int) (140 * a), 0x8899AA), 1);
		}
		int pip = root ? (t >= RUN ? AMBER : 0xFFFFFF) : depth == 8 ? 0xFFFFFF : 0x9A9A9A;
		Vec3 pp = pt(u + 0.012, v);
		fx.glowBox(new AABB(pp.subtract(0.008, 0.008, 0.003), pp.add(0.008, 0.008, 0.003)), Fx.argb((int) (230 * a), pip));
		fx.item(pt(u + 0.04, v), new ItemStack(icon), 0.045 * a, 0, true);
		text(fx, u + 0.065, v, title, Fx.argb((int) (255 * a), color), 0.95f);
		text(fx, u + 0.065 + title.length() * 0.0108, v, how, Fx.argb((int) (255 * a), 0x7A8696), 0.95f);
		if (root) {
			text(fx, 0.43, v, "0/32", Fx.argb((int) (255 * a), 0x9AA3AE), 0.9f);
			fx.ribbon(List.of(pt(u + 0.065, v - 0.026), pt(0.475, v - 0.026)), 0.004, Fx.argb((int) (120 * a), 0x2B2B2B), 1);
		}
	}

	private void text(Fx fx, double u, double v, String s, int color, float size) {
		fx.text(pt(u, v), Component.literal(s), 0.0062f * size, color, 0, false, 180, false);
	}

	/** A button / field / slot: its edge glows (brightness 0..255). */
	private void box(Fx fx, double u0, double v0, double u1, double v1, int glow, double c) {
		Vec3 a = pt(u0, v0), b = pt(u1, v0), cc = pt(u1, v1), d = pt(u0, v1);
		fx.ribbon(List.of(a, b, cc, d, a), 0.004, Fx.argb((int) (glow * c), CYAN), 1);
	}

	@Override
	void hud(FilmHud hud, double t) {
		double k = Anim.window(t, KEY - 0.6, KEY - 0.3, OPEN + 0.6, OPEN + 1.0);
		double press = Anim.window(t, KEY - 0.05, KEY, KEY + 0.1, KEY + 0.2);
		hud.keycap("K", 34, hud.h - 40, 2.2f, press, k);
		double typing = t - (GO + 1.6);
		int n = typing < 0 ? 0 : Math.min(COMMAND.length(), (int) (typing / 0.06));
		double typed = COMMAND.length() * 0.06;
		if (typing > 0 && n < COMMAND.length()) hud.chatInput(COMMAND.substring(0, n), true, 1);
		if (typing > typed) hud.chat(List.of(Component.literal("<you> " + COMMAND)), Anim.ramp(typing, typed, typed + 0.3));
	}
}
