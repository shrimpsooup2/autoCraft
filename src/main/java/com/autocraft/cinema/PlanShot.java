package com.autocraft.cinema;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The plan comes alive, high above the land: the plan as a tower of items on glowing rings, from a log up to a
 * diamond. Beside it float islands where the bot does each step — chops the tree, crafts planks and a pickaxe,
 * mines stone, then iron, smelts it, and mines the diamonds. Each finished item flies to its place on the tower,
 * its ring turns green and the link to the next step lights up. The camera climbs with the work.
 */
final class PlanShot extends Shot {
	private static final double STEP = 4.6;
	private static final int CYAN = 0x39D0FF, GREEN = 0x5EE36A, DIM = 0x3A6B88;
	private static final Item[] NODES = {Items.OAK_LOG, Items.OAK_PLANKS, Items.WOODEN_PICKAXE, Items.COBBLESTONE, Items.STONE_PICKAXE,
		Items.RAW_IRON, Items.IRON_INGOT, Items.IRON_PICKAXE, Items.DIAMOND};
	/** Island centres (x, y) and the bot's time on each. */
	private static final double[][] ISLANDS = {{-8.5, -1.5, 1.5, 5.5}, {8.5, 5.4, 5.5, 9.5}, {-8.5, 14.6, 9.5, 13.5}, {8.5, 21.6, 13.5, 17.0},
		{-8.5, 28.4, 17.0, 21.0}, {8.5, 35.4, 21.0, 25.5}};
	/** When each node is done (its item lands). */
	private static final double[] DONE = {5.2, 7.3, 9.2, 11.6, 13.3, 16.6, 19.0, 20.8, 25.0};

	private Actor bot;

	/** Its story (seconds) against bars into the shot: each island's step ends on the music, the diamonds by the last bar. */
	private static final Warp WARP = new Warp(new double[][] {{0, 0}, {0.5, 1.5}, {1.75, 5.5}, {3, 9.5}, {4.25, 13.5}, {5.25, 17}, {6.5, 21},
		{7.8, 25.5}, {8, 27}});

	PlanShot() {
		super("Plan", "Plan", 2, 2000);
	}

	@Override
	double clock(double t) {
		return WARP.story(t);
	}

	@Override
	void build(ServerLevel level, BlockPos at) {
		int base = Stage.ground(level, at.getX(), at.getZ()) + 105;
		origin = new BlockPos(at.getX(), base, at.getZ());
		for (double[] is : ISLANDS) Sets.island(level, x(is[0]), base + (int) is[1], origin.getZ(), 3, Blocks.GRASS_BLOCK.defaultBlockState());
		int ax = x(ISLANDS[0][0]), ay = base + (int) ISLANDS[0][1];
		Sets.oak(level, ax + 1, ay + 1, origin.getZ(), 4);
		Stage.set(level, x(ISLANDS[1][0]), base + (int) ISLANDS[1][1] + 1, origin.getZ(), Blocks.CRAFTING_TABLE.defaultBlockState());
		int cx = x(ISLANDS[2][0]), cy = base + (int) ISLANDS[2][1];
		Stage.fill(level, cx + 1, cy + 1, origin.getZ(), cx + 2, cy + 2, origin.getZ() + 1, Blocks.STONE.defaultBlockState());
		Stage.set(level, cx - 2, cy + 1, origin.getZ(), Blocks.CRAFTING_TABLE.defaultBlockState());
		int dx = x(ISLANDS[3][0]), dy = base + (int) ISLANDS[3][1];
		Stage.fill(level, dx - 1, dy + 1, origin.getZ() + 2, dx + 1, dy + 3, origin.getZ() + 2, Blocks.STONE.defaultBlockState());
		Stage.set(level, dx, dy + 1, origin.getZ() + 2, Blocks.IRON_ORE.defaultBlockState());
		Stage.set(level, dx + 1, dy + 3, origin.getZ() + 2, Blocks.COAL_ORE.defaultBlockState());
		int ex = x(ISLANDS[4][0]), ey = base + (int) ISLANDS[4][1];
		Stage.set(level, ex + 1, ey + 1, origin.getZ(), Blocks.FURNACE.defaultBlockState());
		Stage.set(level, ex - 2, ey + 1, origin.getZ(), Blocks.CRAFTING_TABLE.defaultBlockState());
		int fx = x(ISLANDS[5][0]), fy = base + (int) ISLANDS[5][1];
		Sets.island(level, fx, fy, origin.getZ(), 3, Blocks.DEEPSLATE.defaultBlockState());
		Stage.fill(level, fx - 2, fy + 1, origin.getZ() + 2, fx + 2, fy + 3, origin.getZ() + 2, Blocks.DEEPSLATE.defaultBlockState());
		Stage.fill(level, fx - 2, fy + 1, origin.getZ(), fx - 2, fy + 3, origin.getZ() + 1, Blocks.DEEPSLATE.defaultBlockState());
		Stage.fill(level, fx + 2, fy + 1, origin.getZ(), fx + 2, fy + 3, origin.getZ() + 1, Blocks.DEEPSLATE.defaultBlockState());
		for (int k = -1; k <= 1; k++) Stage.set(level, fx + k, fy + 1 + (k == 1 ? 1 : 0), origin.getZ() + 2, Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState());
	}

	private int x(double rel) {
		return origin.getX() + (int) Math.floor(rel);
	}

	/** Island i's standing point (its surface + 1) offset by (dx, dz). */
	private Vec3 on(int i, double dx, double dz) {
		return new Vec3(x(ISLANDS[i][0]) + 0.5 + dx, origin.getY() + (int) ISLANDS[i][1] + 1, origin.getZ() + 0.5 + dz);
	}

	private Vec3 node(int i) {
		return v(i % 2 == 0 ? -1.3 : 1.3, i * STEP + 0.5, 0.5);
	}

	@Override
	void start() {
		bot = add(Actor.me(level, on(0, -2, -0.4), -90));
		bot.light = 0x5EE36A;
		bot.hold(Items.AIR);
		Vec3 top = v(0, 18, 0.5);
		cam.key(0, v(-4, 2.5, -15), v(-3.5, 1.0, 0.5), 62);
		for (double[] is : ISLANDS) {
			double mid = (is[2] + is[3]) / 2;
			cam.key(WARP.film(mid), v(is[0] * 0.32, is[1] + 3.0, -13.2), v(is[0] * 0.52, is[1] + 1.8, 0.5));
		}
		cam.key(duration, v(0, 22, -58), top.add(0, 0, 0), 60);
		cam.watch(on(bot, 0, 1.0, 0), 0, WARP.film(25.5), 0.35);
	}

	@Override
	void tick(double t) {
		for (int i = 0; i < ISLANDS.length; i++) {
			if (at(t, ISLANDS[i][2])) hop(i);
		}
		// A: chop the tree (two logs)
		BlockPos trunk = BlockPos.containing(on(0, 1, 0));
		work(t, 2.0, 4.6, 0, trunk);
		if (at(t, 3.3)) dig(trunk);
		if (at(t, 4.6)) dig(trunk.above());
		// B: craft at the table
		swingEvery(t, 6.0, 7.0);
		swingEvery(t, 7.8, 9.0);
		// C: mine three stone, then craft
		BlockPos stone = BlockPos.containing(on(2, 1, 0));
		for (int k = 0; k < 3; k++) {
			BlockPos p = k == 0 ? stone : k == 1 ? stone.above() : stone.east();
			work(t, 9.8 + k * 0.6, 10.4 + k * 0.6, 0, p);
			if (at(t, 10.4 + k * 0.6)) dig(p);
		}
		swingEvery(t, 12.0, 13.2);
		// D: mine the iron ore
		BlockPos ore = BlockPos.containing(on(3, 0, 1.5));
		work(t, 14.0, 16.2, 0, ore);
		if (at(t, 16.2)) dig(ore);
		// E: smelt, then craft the iron pickaxe
		BlockPos furnace = BlockPos.containing(on(4, 1, 0));
		if (at(t, 17.4)) place(furnace, Blocks.FURNACE.defaultBlockState().setValue(AbstractFurnaceBlock.LIT, true));
		if (every(t, 17.4, 18.8, 0.2)) particles(ParticleTypes.SMOKE, Vec3.atCenterOf(furnace).add(0, 0.6, 0), 2, 0.3, 0.02);
		if (at(t, 18.8)) place(furnace, Blocks.FURNACE.defaultBlockState());
		swingEvery(t, 19.3, 20.6);
		if (at(t, 19.3)) bot.face(90);
		// F: three diamonds
		if (at(t, 21.0)) bot.hold(Items.IRON_PICKAXE);
		BlockPos d0 = BlockPos.containing(on(5, -1, 1.5));
		BlockPos[] gems = {d0, d0.east(), d0.east(2).above()};
		for (int k = 0; k < 3; k++) {
			work(t, 21.6 + k * 1.0, 22.5 + k * 1.0, 0, gems[k]);
			if (at(t, 22.5 + k * 1.0)) {
				dig(gems[k]);
				particles(ParticleTypes.END_ROD, Vec3.atCenterOf(gems[k]), 6, 0.6, 0.06);
			}
		}
		for (int i = 0; i < DONE.length; i++) {
			if (at(t, DONE[i])) particles(ParticleTypes.HAPPY_VILLAGER, node(i), 8, 1.0, 0.04);
		}
	}

	/** The bot pops over to island i. */
	private void hop(int i) {
		Vec3 p = switch (i) {
			case 0 -> on(0, -1, -0.3);
			case 1 -> on(1, 1.6, -0.6);
			case 2 -> on(2, -0.4, -0.4);
			case 3 -> on(3, 0, 0.2);
			case 4 -> on(4, -0.4, -1.4);
			default -> on(5, 0, -0.8);
		};
		particles(ParticleTypes.END_ROD, bot.pos().add(0, 1, 0), 12, 0.8, 0.08);
		bot.teleport(p);
		particles(ParticleTypes.END_ROD, p.add(0, 1, 0), 12, 0.8, 0.08);
		Vec3 aim = switch (i) {
			case 0 -> on(0, 1, 0);
			case 1 -> on(1, 0, 0);
			case 2 -> on(2, 1.5, 0);
			case 3 -> on(3, 0, 2);
			case 4 -> on(4, 1, 0);
			default -> on(5, 0, 2);
		};
		bot.face(Anim.yawTo(p, aim));
		bot.look(aim.add(0, 0.5, 0));
	}

	/** Mining: swings and the crack overlay growing on `p` over [t0, t1]. */
	private void work(double t, double t0, double t1, int unused, BlockPos p) {
		if (t >= t0 && t < t1) {
			swingEvery(t, t0, t1);
			crack(p, (int) Math.min(9, (t - t0) / (t1 - t0) * 10));
		}
	}

	private void swingEvery(double t, double t0, double t1) {
		if (every(t, t0, t1, 0.3)) bot.swing();
	}

	@Override
	void draw(Fx fx, double t) {
		t = clock(t);
		for (int i = 0; i < NODES.length; i++) {
			Vec3 n = node(i);
			boolean done = t >= DONE[i];
			double appear = Anim.ramp(t, 0.2 + i * 0.12, 0.6 + i * 0.12);
			double pop = done ? Anim.pop(t, DONE[i], 0.4) : 1;
			fx.item(n, new ItemStack(NODES[i]), (done ? 0.75 : 0.6) * appear * pop, (float) (t * (done ? 40 : 15) + i * 40), false);
			if (!done) fx.glassBox(new net.minecraft.world.phys.AABB(n.subtract(0.45, 0.45, 0.45), n.add(0.45, 0.45, 0.45)), Fx.argb((int) (50 * appear), CYAN));
			fx.ring(n.add(0, -0.9, 0), 0.9, 0.06, Fx.argb((int) (230 * appear), done ? GREEN : DIM), done ? Anim.ramp(t, DONE[i], DONE[i] + 0.4) : appear);
			if (i + 1 < NODES.length) {
				Vec3 a = n.add(0, 0.75, 0), b = node(i + 1).add(0, -0.95, 0);
				List<Vec3> link = List.of(a, a.add(b.subtract(a).scale(0.5)).add(0, 0, -0.4), b);
				fx.ribbon(link, 0.035, Fx.argb((int) (120 * appear), 0x2C5A7A), 1);
				if (done) fx.ribbon(link, 0.06, Fx.argb(255, CYAN), Anim.ramp(t, DONE[i], DONE[i] + 0.6));
			}
		}
		// items flying from the islands to the tower
		ItemStack log = new ItemStack(Items.OAK_LOG);
		flight(fx, log, on(0, 1, 0).add(0, 1, 0), node(0), t, 4.6, DONE[0], 0.5, 2.5);
		Vec3 table = on(1, 0, 0).add(0, 1.0, 0);
		craft(fx, t, table, new Item[] {Items.OAK_LOG}, 6.0, 7.0, Items.OAK_PLANKS);
		flight(fx, new ItemStack(Items.OAK_PLANKS), table.add(0, 0.9, 0), node(1), t, 7.0, DONE[1], 0.45, 2);
		craft(fx, t, table, new Item[] {Items.OAK_PLANKS, Items.OAK_PLANKS, Items.STICK}, 7.8, 8.6, Items.WOODEN_PICKAXE);
		flight(fx, new ItemStack(Items.WOODEN_PICKAXE), table.add(0, 0.9, 0), node(2), t, 8.6, DONE[2], 0.5, 2);
		flight(fx, new ItemStack(Items.COBBLESTONE), on(2, 1.5, 0).add(0, 1, 0), node(3), t, 11.0, DONE[3], 0.45, 2);
		Vec3 tableC = on(2, -2, 0).add(0, 1.0, 0);
		craft(fx, t, tableC, new Item[] {Items.COBBLESTONE, Items.COBBLESTONE, Items.STICK}, 12.0, 12.8, Items.STONE_PICKAXE);
		flight(fx, new ItemStack(Items.STONE_PICKAXE), tableC.add(0, 0.9, 0), node(4), t, 12.8, DONE[4], 0.5, 2);
		flight(fx, new ItemStack(Items.RAW_IRON), on(3, 0, 1.5).add(0, 0.5, 0), node(5), t, 16.2, DONE[5], 0.45, 2.2);
		Vec3 furnace = on(4, 1, 0).add(0, 0.5, -0.6);
		flight(fx, new ItemStack(Items.IRON_INGOT), furnace, node(6), t, 18.8, DONE[6], 0.45, 2);
		Vec3 tableE = on(4, -2, 0).add(0, 1.0, 0);
		craft(fx, t, tableE, new Item[] {Items.IRON_INGOT, Items.IRON_INGOT, Items.STICK}, 19.3, 20.0, Items.IRON_PICKAXE);
		flight(fx, new ItemStack(Items.IRON_PICKAXE), tableE.add(0, 0.9, 0), node(7), t, 20.0, DONE[7], 0.5, 2);
		for (int k = 0; k < 3; k++) {
			Vec3 gem = on(5, -1 + k, 1.5).add(0, 0.5 + (k == 2 ? 1 : 0), 0);
			flight(fx, new ItemStack(Items.DIAMOND), gem, node(8).add((k - 1) * 0.25, 0, 0), t, 22.6 + k * 1.0, DONE[8] - (2 - k) * 0.1, 0.4, 3);
		}
	}

	/** Ingredients circle above `c` and spiral in over [t0, t1]; the result pops out at t1. */
	private static void craft(Fx fx, double t, Vec3 c, Item[] ingredients, double t0, double t1, Item result) {
		if (t >= t0 && t < t1) {
			double u = (t - t0) / (t1 - t0);
			for (int i = 0; i < ingredients.length; i++) {
				double a = i * Math.PI * 2 / ingredients.length + u * Math.PI * 4;
				double r = 0.9 * (1 - Math.pow(u, 1.6));
				fx.item(c.add(Math.cos(a) * r, 0.6 + 0.4 * Math.sin(u * Math.PI), Math.sin(a) * r), new ItemStack(ingredients[i]), 0.3 * (1 - Math.max(0, u - 0.85) / 0.15), (float) (t * 200), false);
			}
		}
		double p = Anim.pop(t, t1, 0.35);
		if (p > 0 && t < t1 + 0.6) fx.item(c.add(0, 0.9, 0), new ItemStack(result), 0.45 * p, (float) (t * 180), false);
	}
}
