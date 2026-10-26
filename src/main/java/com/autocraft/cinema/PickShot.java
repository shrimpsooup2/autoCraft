package com.autocraft.cinema;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
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
 * How to use it. Over your shoulder: K is pressed and the Tasks panel unfolds off to your right as a glowing glass
 * screen turned toward you. You tap the search box, "diamond" types in, 32 goes in the amount box, you tap the
 * diamond, open its plan (every step down to chopping a tree unfolds) and tap Run all. The panel folds away, your
 * status light flips from red to green, and you walk off on your own. The same job as a chat command types in below.
 * Layout: texts are placed by their real width, so nothing overlaps whatever the font.
 */
final class PickShot extends Shot {
	private static final double W = 2.6, H = 1.45;
	/** Blocks per font pixel at text size 1. */
	private static final double TS = 0.0073;
	private static final int CYAN = 0x39D0FF, AMBER = 0xFFBF3C, GREEN = 0x5EE36A;
	private static final String SEARCH = "diamond", COMMAND = "!ac collect diamond 32";
	private static final double KEY = 1.8, OPEN = 2.2, TYPE = 3.6, AMOUNT = 5.4, ADD = 6.3, UNFOLD = 7.4, RUN = 11.6, FOLD = 12.6, GO = 13.6;
	private static final double ROW_GAP = 0.36;
	/** Panel columns (u): tasks from LEFT, items from MID to RIGHT. */
	private static final double LEFT = 0.03, MID = 0.60, RIGHT = 0.975;
	private static final double ROW0 = 0.76, ROW_STEP = 0.058;
	private static final double SLOT_GAP = 0.007, SLOT_W = (RIGHT - MID - 5 * SLOT_GAP) / 6, SLOT_H = SLOT_W * W / H, GRID_TOP = 0.71;

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
	/** Where the finger taps (time, panel u, panel v): the search box, the amount, the diamond, the task's plan, Run all. */
	private static final double[][] TAPS = {{3.3, 0.73, 0.8175}, {AMOUNT - 0.2, 0.92, 0.8175}, {ADD, MID + SLOT_W / 2, GRID_TOP - SLOT_H / 2},
			{UNFOLD, LEFT + 0.01, ROW0}, {RUN, 0.69, 0.0775}};

	private Actor me;
	private Vec3 screen;
	/** The way the panel's face points (toward the bot). */
	private Vec3 front;
	private float panelYaw;
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
		Vec3 p = v(0.5, 0, 0.5), eye = p.add(0, 1.62, 0);
		screen = p.add(-1.2, 1.5, 1.75);   // off to the bot's right, so the camera behind it sees all of it
		panelYaw = Anim.yawTo(screen, eye);
		double yr = Math.toRadians(panelYaw);
		front = new Vec3(-Math.sin(yr), 0, Math.cos(yr));
		me = add(Actor.me(level, p, Anim.yawTo(p, screen)));
		me.light = FilmHud.RED;
		walk = new Anim.Track().at(GO, p).to(v(3.5, 0, 6.5), 3.6).to(v(4.5, 0, 14.5), 4.0);
		Vec3 shoulder = eye.add(-0.7, 0.2, -2.2);
		cam.key(0, shoulder.add(0, 0.12, -0.5), screen.add(0, -0.1, 0), 42)
				.key(FOLD - 1.0, shoulder.add(0, 0, 0.2), screen, 40)
				.key(FOLD + 0.6, eye.add(-1.6, 0.9, -3.4), p.add(0.6, 1.2, 2.0), 50)   // back off round the bot, not through it
				.key(16.0, p.add(-1.0, 3.2, -3.5), v(3.6, 1.0, 7.6), 56)                // and follow it walking off
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
		Vec3[] k = Fx.panelCorners(screen, panelYaw, W, H);
		return k[0].add(k[1].subtract(k[0]).scale(u)).add(k[3].subtract(k[0]).scale(v)).add(front.scale(0.008));
	}

	/** Slot i of the item grid: u0, v0, u1, v1. */
	private static double[] slot(int i) {
		int r = i / 6, k = i % 6;
		double u0 = MID + k * (SLOT_W + SLOT_GAP), v1 = GRID_TOP - r * (SLOT_H + 0.012);
		return new double[] {u0, v1 - SLOT_H, u0 + SLOT_W, v1};
	}

	@Override
	void draw(Fx fx, double t) {
		double s = scale(t);
		if (s <= 0.001) return;
		fx.panel(screen, panelYaw, W * s, H * (0.15 + 0.85 * s), Fx.argb(190, 0x0A1015), Fx.argb(230, CYAN), 0.012);
		double c = Anim.ramp(s, 0.85, 1.0);
		if (c <= 0) return;
		int white = Fx.argb((int) (255 * c), 0xFFFFFF), grey = Fx.argb((int) (255 * c), 0x9AA3AE), dim = Fx.argb((int) (255 * c), 0x7A8696);
		boolean running = t >= RUN;
		// header: name, status, tabs
		text(fx, LEFT, 0.945, "autoCraft", white, 1.15, 0);
		text(fx, LEFT + tw("autoCraft", 1.15) + 0.02, 0.945, running ? "collect" : "idle", grey, 0.95, 0);
		String[] tabs = {"Tasks", "Build", "Team", "Bot", "Settings"};
		double[] widths = new double[tabs.length];
		double total = 0.008 * (tabs.length - 1);
		for (int i = 0; i < tabs.length; i++) total += widths[i] = tw(tabs[i], 0.85) + 0.022;
		double u = RIGHT - total;
		for (int i = 0; i < tabs.length; i++) {
			box(fx, u, 0.905, u + widths[i], 0.985, i == 0 ? 70 : 30, c);
			text(fx, u + widths[i] / 2, 0.945, tabs[i], i == 0 ? white : grey, 0.85, 1);
			u += widths[i] + 0.008;
		}
		// left: the tasks
		boolean added = t >= ADD + 0.1;
		int shown = t < UNFOLD + 0.2 ? 0 : Math.min(PLAN.size(), (int) ((t - UNFOLD - 0.2) / ROW_GAP) + 1);
		text(fx, LEFT, 0.855, running ? "Tasks — running" : added ? "Tasks — " + (1 + shown) + " to do" : "Tasks", white, 1, 0);
		if (!added) {
			text(fx, LEFT, 0.79, "Nothing to do yet. Pick items on the right", dim, 0.8, 0);
			text(fx, LEFT, 0.755, "to collect them.", dim, 0.8, 0);
		} else {
			double a = Anim.ramp(t, ADD + 0.1, ADD + 0.4) * c;
			row(fx, 0, ROW0, "32 Diamond", Items.DIAMOND, "mine diamond ore", running ? AMBER : 0xFFFFFF, a, true, t);
			for (int i = 0; i < shown; i++) {
				Row r = PLAN.get(i);
				double ra = Anim.ramp(t, UNFOLD + 0.2 + i * ROW_GAP, UNFOLD + 0.45 + i * ROW_GAP) * c;
				row(fx, r.depth(), ROW0 - (i + 1) * ROW_STEP, r.title(), r.icon(), r.how(), r.depth() == 8 ? 0xFFFFFF : 0xBEC6D0, ra, false, t);
			}
		}
		// right: search, amount, the item grid
		int typed = t < TYPE ? 0 : Math.min(SEARCH.length(), (int) ((t - TYPE) / 0.17) + 1);
		box(fx, MID, 0.79, 0.86, 0.845, t >= 3.3 && t < AMOUNT - 0.2 ? 120 : 50, c);
		boolean caret = ((int) (t * 3)) % 2 == 0 && t < AMOUNT;
		text(fx, MID + 0.01, 0.8175, typed == 0 ? "Search items..." : SEARCH.substring(0, typed) + (caret ? "_" : ""), typed == 0 ? dim : white, 0.95, 0);
		box(fx, 0.87, 0.79, RIGHT, 0.845, t >= AMOUNT - 0.2 && t < ADD ? 120 : 50, c);
		text(fx, (0.87 + RIGHT) / 2, 0.8175, t >= AMOUNT + 0.3 ? "32" : t >= AMOUNT ? "3" : "64", white, 0.95, 1);
		text(fx, MID, 0.755, "Adds new top-level tasks", dim, 0.75, 0);
		List<Item> items = typed >= 3 ? FOUND : ALL;
		for (int i = 0; i < 18; i++) {
			double[] sl = slot(i);
			boolean hot = i == 0 && items == FOUND && t > ADD - 0.4 && t < ADD + 0.3;
			box(fx, sl[0], sl[1], sl[2], sl[3], hot ? 160 : 40, c);
			if (i < items.size()) fx.guiItem(pt((sl[0] + sl[2]) / 2, (sl[1] + sl[3]) / 2), new ItemStack(items.get(i)), 0.058 * c);
		}
		// buttons
		u = LEFT;
		for (String label : new String[] {"+ Group", "+ Build", "Clear done"}) {
			double w = tw(label, 0.9) + 0.04;
			button(fx, u, u + w, label, 0.9, 40, white, c);
			u += w + 0.012;
		}
		boolean pressed = t >= RUN && t < RUN + 0.25;
		button(fx, MID, 0.78, running ? "Stop" : "Run all", 1, pressed ? 220 : running ? 90 : 60, white, c);
		button(fx, 0.79, RIGHT, "Run selected", 0.9, 35, running ? dim : white, c);
		// the finger's touch: a dot while touching and a ripple after
		for (double[] k : TAPS) {
			double since = t - k[0];
			if (since < -0.2 || since > 0.6) continue;
			if (since < 0.15) fx.spark(pt(k[1], k[2]).add(front.scale(0.004)), 0.05, Fx.argb(230, 0xBFEFFF));
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
		double u = LEFT + depth * 0.016;
		if (depth > 0) {   // a guide line from the parent
			double gu = LEFT + (depth - 1) * 0.016 + 0.006;
			fx.ribbon(List.of(pt(gu, v + 0.04), pt(gu, v), pt(u + 0.002, v)), 0.0035, Fx.argb((int) (140 * a), 0x8899AA), 1);
		}
		int pip = root ? (t >= RUN ? AMBER : 0xFFFFFF) : depth == 8 ? 0xFFFFFF : 0x9A9A9A;
		Vec3 pp = pt(u + 0.006, v);
		fx.glowBox(new AABB(pp.subtract(0.008, 0.008, 0.008), pp.add(0.008, 0.008, 0.008)), Fx.argb((int) (230 * a), pip));
		fx.guiItem(pt(u + 0.034, v), new ItemStack(icon), 0.042 * a);
		double x = u + 0.058;
		text(fx, x, v, title, Fx.argb((int) (255 * a), color), 0.9, 0);
		text(fx, x + tw(title, 0.9) + 0.012, v, how, Fx.argb((int) (255 * a), 0x7A8696), 0.8, 0);
		if (root) {
			text(fx, MID - 0.03, v, "0/32", Fx.argb((int) (255 * a), 0x9AA3AE), 0.9, 2);
			fx.ribbon(List.of(pt(x, v - 0.03), pt(MID - 0.03, v - 0.03)), 0.004, Fx.argb((int) (120 * a), 0x2B2B2B), 1);
		}
	}

	/** Text width in panel units. */
	private static double tw(String s, double size) {
		return Minecraft.getInstance().font.width(s) * TS * size / W;
	}

	/** Text on the panel; align 0 left, 1 centred, 2 right. */
	private void text(Fx fx, double u, double v, String s, int color, double size, int align) {
		if (align == 1) u -= tw(s, size) / 2;
		else if (align == 2) u -= tw(s, size);
		fx.text(pt(u, v), Component.literal(s), (float) (TS * size), color, 0, false, panelYaw, false);
	}

	/** A button / field / slot: its edge glows (brightness 0..255). */
	private void box(Fx fx, double u0, double v0, double u1, double v1, int glow, double c) {
		Vec3 a = pt(u0, v0), b = pt(u1, v0), cc = pt(u1, v1), d = pt(u0, v1);
		fx.ribbon(List.of(a, b, cc, d, a), 0.004, Fx.argb((int) (glow * c), CYAN), 1);
	}

	/** A button along the bottom with its label centred. */
	private void button(Fx fx, double u0, double u1, String label, double size, int glow, int color, double c) {
		box(fx, u0, 0.035, u1, 0.12, glow, c);
		text(fx, (u0 + u1) / 2, 0.0775, label, color, size, 1);
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
