package com.autocraft.cinema;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Looting an ancient city, quietly. Deep underground in a deepslate hall lit by soul lanterns, the bot sneaks in
 * with a soul lantern. Every sculk sensor and shrieker is marked and its route winds between them, never onto
 * one. At the chest it sees that the sensor beside it would hear the lid, so it puts wool in between (the
 * vibration stops at the wool), opens the chest and takes the loot. The shriek count stays at 0 of 3.
 */
final class LootShot extends Shot {
	private static final int SOUL = 0x5FE3F5, AMBER = 0xFFBF3C, RED = 0xFF4A3D, GREEN = 0x5EE36A;
	private static final int[][] SENSORS = {{-8, 1}, {-4, -2}, {0, 2}, {4, -1}, {7, 3}, {11, 4}};
	private static final int[][] SHRIEKERS = {{-6, -4}, {-2, 4}, {2, -4}, {6, -3}, {-10, -4}};
	private static final double[][] ROUTE = {{-11.5, 0.5}, {-9.5, -0.5}, {-7.5, -0.6}, {-5.5, 0.1}, {-3.5, 0.8}, {-1.5, 0.9}, {0.5, -0.3},
		{2.5, 0.4}, {4.5, 1.5}, {6.5, 0.6}, {9.5, 0.5}};
	private static final double SNEAK = 2.2;
	private static final double ENTER = 3.0, LISTEN = 13.4, WOOL = 15.4, OPEN = 17.2, TAKE = 19.4, CLOSE = 21.0, LEAVE = 21.6;
	private static final Item[] LOOT = {Items.ECHO_SHARD, Items.ENCHANTED_BOOK, Items.DISC_FRAGMENT_5, Items.ENCHANTED_GOLDEN_APPLE, Items.AMETHYST_SHARD};
	private static final String[] LABELS = {"Echo Shard ×3", "Swift Sneak III", "Disc Fragment", "Enchanted Golden Apple", "Amethyst Shard ×6"};

	private Actor bot;
	private Anim.Track in, out;
	private BlockPos chest, guard, wool;

	LootShot() {
		super("Loot", "Loot", 26, 18000);
	}

	@Override
	BlockPos offset() {
		return new BlockPos(0, 0, 70);
	}

	@Override
	void build(ServerLevel level, BlockPos at) {
		int floor = Math.max(level.getMinY() + 10, Stage.ground(level, at.getX(), at.getZ()) - 34);
		origin = new BlockPos(at.getX(), floor + 1, at.getZ());
		Random rng = new Random(5);
		for (int x = -13; x <= 13; x++) {
			for (int z = -7; z <= 7; z++) {
				for (int y = -1; y <= 7; y++) {
					boolean shell = x == -13 || x == 13 || z == -7 || z == 7 || y == -1 || y == 7;
					BlockState s;
					if (!shell) s = Blocks.AIR.defaultBlockState();
					else if (y == -1) s = rng.nextInt(10) < 3 ? Blocks.SCULK.defaultBlockState()
							: rng.nextInt(6) == 0 ? Blocks.CRACKED_DEEPSLATE_TILES.defaultBlockState() : Blocks.DEEPSLATE_TILES.defaultBlockState();
					else if (y == 7) s = rng.nextInt(5) == 0 ? Blocks.SCULK.defaultBlockState() : Blocks.DEEPSLATE_TILES.defaultBlockState();
					else if (y == 3) s = Blocks.POLISHED_DEEPSLATE.defaultBlockState();
					else s = rng.nextInt(5) == 0 ? Blocks.CRACKED_DEEPSLATE_BRICKS.defaultBlockState() : Blocks.DEEPSLATE_BRICKS.defaultBlockState();
					Stage.set(level, b(x, y, z), s);
				}
			}
		}
		// pilasters, chiseled at their feet and heads
		for (int x = -11; x <= 11; x += 4) {
			for (int z : new int[] {-6, 6}) {
				for (int y = 0; y <= 6; y++) {
					Stage.set(level, b(x, y, z), y == 0 || y == 6 ? Blocks.CHISELED_DEEPSLATE.defaultBlockState() : Blocks.POLISHED_DEEPSLATE.defaultBlockState());
				}
			}
		}
		BlockState lantern = Blocks.SOUL_LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true);
		for (int x = -9; x <= 9; x += 6) {
			for (int z : new int[] {-4, 4}) Stage.set(level, b(x, 6, z), lantern);
		}
		for (int[] p : new int[][] {{-12, -6}, {-12, 6}, {12, -6}, {12, 6}}) {
			Stage.set(level, b(p[0], -1, p[1]), Blocks.SOUL_SOIL.defaultBlockState());
			Stage.set(level, b(p[0], 0, p[1]), Blocks.SOUL_FIRE.defaultBlockState());
		}
		for (int[] p : new int[][] {{-11, -2}, {-7, 5}, {-1, -5}, {3, 5}, {9, -5}, {12, 2}}) {
			Stage.set(level, b(p[0], 0, p[1]), Blocks.CANDLE.defaultBlockState().setValue(BlockStateProperties.CANDLES, 1 + Math.floorMod(p[0], 3))
					.setValue(BlockStateProperties.LIT, true));
		}
		for (int[] s : SENSORS) Stage.set(level, b(s[0], 0, s[1]), Blocks.SCULK_SENSOR.defaultBlockState());
		for (int[] s : SHRIEKERS) Stage.set(level, b(s[0], 0, s[1]), Blocks.SCULK_SHRIEKER.defaultBlockState());
		Stage.set(level, b(11, 0, 0), Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.WEST));
	}

	@Override
	void start() {
		chest = b(11, 0, 0);
		guard = b(11, 0, 4);
		wool = b(11, 0, 2);
		in = new Anim.Track().at(ENTER, route(0));
		for (int i = 1; i < ROUTE.length; i++) in.to(route(i), SNEAK);
		out = new Anim.Track().at(LEAVE, route(ROUTE.length - 1));
		for (int i = ROUTE.length - 2; i >= ROUTE.length - 5; i--) out.to(route(i), SNEAK);
		bot = add(Actor.me(level, route(0), -90));
		bot.light = GREEN;
		bot.holdOff(Items.SOUL_LANTERN);
		bot.crouch(true);
		Vec3 c = Vec3.atCenterOf(chest);
		cam.key(0, v(-12.2, 5.6, -6.2), v(-2, 0.5, 0.5), 66)
				.key(ENTER, v(-11.8, 4.4, -5.6), v(-8, 0.8, 0.5))
				.key(8.0, v(-3.5, 2.6, -5.6), v(-2.5, 0.8, 0.5), 62)
				.key(12.6, v(5.0, 2.4, -5.4), v(7.0, 0.8, 1.0))
				.key(WOOL + 0.6, v(7.6, 2.3, -3.6), c.add(-0.4, -0.2, 2.0), 56)
				.key(TAKE + 0.8, v(8.4, 2.2, -2.6), c.add(-1.2, 0.2, 0.2), 52)
				.key(duration, v(-1, 5.2, -6.2), v(6, 0.5, 0.5), 64);
	}

	private Vec3 route(int i) {
		return v(ROUTE[i][0], 0, ROUTE[i][1]);
	}

	@Override
	void tick(double t) {
		if (t < LEAVE) {
			bot.follow(in, t);
			if (t > in.end()) bot.look(t < WOOL + 0.4 ? Vec3.atCenterOf(wool) : Vec3.atCenterOf(chest));
		} else {
			bot.follow(out, t);
			bot.look(null);
		}
		if (at(t, WOOL - 0.4)) bot.hold(Items.WOOL.pick(DyeColor.GRAY));
		if (at(t, WOOL)) {
			bot.swing();
			put(wool, Blocks.WOOL.pick(DyeColor.GRAY).defaultBlockState());
		}
		if (at(t, WOOL + 0.5)) bot.hold(Items.AIR);
		if (at(t, OPEN)) {
			bot.swing();
			level.blockEvent(chest, Blocks.CHEST, 1, 1);
			level.playLocalSound(chest, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.5f, 1, false);
		}
		if (at(t, CLOSE)) {
			level.blockEvent(chest, Blocks.CHEST, 1, 0);
			level.playLocalSound(chest, SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 0.5f, 1, false);
		}
		if (every(t, 0, duration, 0.5)) particles(ParticleTypes.SCULK_SOUL, v(0, 1.0, 0), 1, 18, 0.01);
	}

	@Override
	void draw(Fx fx, double t) {
		double marks = Anim.ramp(t, 1.2, 2.4);
		// every sensor and shrieker: "don't step here"
		for (int[] s : SENSORS) {
			boolean isGuard = s[0] == 11 && s[1] == 4;
			double hear = isGuard ? Anim.window(t, LISTEN, LISTEN + 0.3, WOOL, WOOL + 0.4) : 0;
			mark(fx, s, hear > 0 ? SOUL : RED, marks);
		}
		for (int[] s : SHRIEKERS) mark(fx, s, RED, marks);
		// the route, winding between them
		if (t > 1.8 && t < LEAVE + 2) {
			List<Vec3> pts = new ArrayList<>();
			for (int i = 0; i < ROUTE.length; i++) pts.add(route(i).add(0, 0.08, 0));
			double fadeOut = 1 - Anim.ramp(t, in.end() + 0.5, in.end() + 1.5);
			fx.ribbon(pts, 0.09, Fx.argb((int) (230 * fadeOut), AMBER), Anim.ramp(t, 1.8, 3.4));
		}
		// the soul lantern's glow at the bot's off hand, and its quiet-mode readout
		if (!bot.entity.isRemoved()) {
			Vec3 p = bot.entity.getPosition(fx.partial);
			double yr = Math.toRadians(bot.yaw());
			Vec3 left = new Vec3(Math.cos(yr), 0, Math.sin(yr)), fwd = new Vec3(-Math.sin(yr), 0, Math.cos(yr));
			Vec3 lamp = p.add(left.scale(0.42)).add(fwd.scale(0.2)).add(0, 0.62, 0);
			double flicker = 0.9 + 0.1 * Math.sin(t * 13) * Math.sin(t * 7.3);
			fx.spark(lamp, 2.2 * flicker, Fx.argb(70, SOUL));
			fx.spark(lamp, 0.35, Fx.argb(200, 0xBFF6FF));
			int a = (int) (230 * Anim.window(t, ENTER, ENTER + 0.6, LEAVE + 2, LEAVE + 3));
			fx.text(p.add(0, 2.55, 0), Component.literal("quiet · shrieks 0/3"), 0.014f, Fx.argb(a, 0xBFF6FF), 0, true, 0, true);
		}
		// the vibration the lid would make, heard by the sensor beside the chest — then stopped by the wool
		Vec3 from = Vec3.atCenterOf(chest).add(0, 0.1, 0.3), to = Vec3.atCenterOf(guard).add(0, -0.2, 0);
		double listen = Anim.window(t, LISTEN, LISTEN + 0.3, WOOL - 0.3, WOOL);
		if (listen > 0) {
			wave(fx, from, to, t - LISTEN, Fx.argb((int) (230 * listen), SOUL));
			fx.glowBox(new AABB(guard).setMaxY(guard.getY() + 0.5).inflate(0.04), Fx.argb((int) (70 * listen * (0.6 + 0.4 * Math.sin(t * 10))), SOUL));
		}
		double blocked = Anim.window(t, WOOL + 0.3, WOOL + 0.6, OPEN + 0.6, OPEN + 1.2);
		if (blocked > 0) {
			Vec3 stop = Vec3.atCenterOf(wool).add(0, 0, -0.55);
			wave(fx, from, stop, t - WOOL - 0.3, Fx.argb((int) (230 * blocked), SOUL));
			fx.spark(stop, 0.5 + 0.15 * Math.sin(t * 14), Fx.argb((int) (160 * blocked), 0xFFFFFF));
			fx.outline(wool, Fx.argb((int) (255 * blocked), GREEN), 3f);
		}
		// the loot rises out of the chest, hangs a moment with its names, then flies to the bot
		Vec3 lid = Vec3.atCenterOf(chest).add(0, 0.5, 0);
		for (int i = 0; i < LOOT.length; i++) {
			double t0 = OPEN + 0.3 + i * 0.18;
			if (t < t0) continue;
			Vec3 hover = lid.add(-0.6, 0.9 + 0.12 * Math.sin(t * 2 + i), (i - 2) * 0.55);
			double pop = Anim.pop(t, t0, 0.4);
			if (t < TAKE + i * 0.12) {
				fx.item(Anim.lerp(lid, hover, Anim.ramp(t, t0, t0 + 0.5)), new ItemStack(LOOT[i]), 0.22 * pop, (float) (t * 60 + i * 50), false);
				double la = Anim.window(t, t0 + 0.4, t0 + 0.7, TAKE - 0.3, TAKE);
				if (la > 0) fx.text(hover.add(0, 0.3, 0), Component.literal(LABELS[i]), 0.0075f, Fx.argb((int) (255 * la), 0xFFFFFF), 0, true, 0, true);
			} else {
				flight(fx, new ItemStack(LOOT[i]), hover, bot.entity.getPosition(fx.partial).add(0, 1.0, 0), t, TAKE + i * 0.12, TAKE + i * 0.12 + 0.6, 0.2, 0.5);
			}
		}
	}

	/** A "never step here" mark: a ring round the block and its outline. */
	private void mark(Fx fx, int[] s, int color, double a) {
		fx.ring(v(s[0] + 0.5, 0.06, s[1] + 0.5), 0.75, 0.05, Fx.argb((int) (230 * a), color), a);
		BlockPos p = b(s[0], 0, s[1]);
		fx.outline(new AABB(p).setMaxY(p.getY() + 0.5), Fx.argb((int) (200 * a), color), 2f);
	}

	/** A wavy line of pulses running from a to b (a vibration travelling), `since` seconds after it starts. */
	private static void wave(Fx fx, Vec3 a, Vec3 b, double since, int color) {
		List<Vec3> pts = new ArrayList<>();
		int n = 28;
		for (int i = 0; i <= n; i++) {
			double u = i / (double) n;
			pts.add(Anim.lerp(a, b, u).add(0, 0.08 * Math.sin(u * 22 - since * 9), 0));
		}
		fx.ribbon(pts, 0.03, Fx.fade(color, 0.6), Anim.clamp01(since / 0.6));
		double head = (since * 1.6) % 1.0;
		fx.spark(Anim.lerp(a, b, head), 0.25, color);
	}
}
