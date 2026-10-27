package com.autocraft.cinema;

import com.autocraft.procedure.SculkGuard;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Looting an ancient city, quietly — in the nearest real one (found and travelled to while the screen is black; a
 * stand-in hall is built only if the world has none). A chest is picked that a sculk sensor would hear, with a
 * route to it past sensors and shriekers. The bot sneaks in with a soul lantern; every sensor and shrieker near
 * its way is marked and the route never steps on one. At the chest the vibration the lid would make reaches the
 * sensor, so the bot puts wool exactly where the mod's own quiet mode puts it (SculkGuard), the vibration now stops
 * at the wool, and it opens the chest and takes the loot. Faint invisible lights along the way let the camera see.
 */
final class LootShot extends Shot {
	private static final int SOUL = 0x5FE3F5, AMBER = 0xFFBF3C, RED = 0xFF4A3D, GREEN = 0x5EE36A;
	/** On the music: sneaking in over the quiet breakdown, the wool as the music comes back (bar 64), the lid on the next bar. */
	private final double ENTER = bar(57.35), ARRIVE = bar(63), LISTEN = bar(63.2), WOOL = bar(64), OPEN = bar(65), TAKE = bar(66.25),
			CLOSE = bar(67), LEAVE = bar(67.25);
	private static final int SCAN_RADIUS = 64, ROUTE_MIN = 12, ROUTE_MAX = 22;
	private static final Item[] LOOT = {Items.ECHO_SHARD, Items.ENCHANTED_BOOK, Items.DISC_FRAGMENT_5, Items.ENCHANTED_GOLDEN_APPLE, Items.AMETHYST_SHARD};
	private static final String[] LABELS = {"Echo Shard ×3", "Swift Sneak III", "Disc Fragment", "Enchanted Golden Apple", "Amethyst Shard ×6"};

	/** What the survey picked (filled in on the server thread by build). */
	private record Plan(BlockPos chest, BlockPos stand, List<BlockPos> listeners, List<BlockPos> wools, List<Vec3> route, List<BlockPos> loud) {}

	private Plan plan;
	private boolean real;
	private Actor bot;
	private Anim.Track in, out;

	LootShot() {
		super("Loot", "Loot", 6, 18000);
	}

	@Override
	BlockPos offset() {
		return new BlockPos(0, 0, 70);
	}

	// ---- the set: a real ancient city, surveyed --------------------------------------------------------------------

	@Override
	void build(ServerLevel level, BlockPos at) {
		BoundingBox city = findCity(level, at);
		real = city != null;
		plan = city != null ? survey(level, city) : null;
		if (plan == null) {
			real = false;
			plan = survey(level, buildHall(level, at));
		}
		if (plan == null) throw new IllegalStateException("no chest to loot in the ancient city");
		origin = plan.stand;
		light(level, plan);
	}

	/** The nearest ancient city's box, cut down to the part around its centre, or null when the world has none. */
	private static BoundingBox findCity(ServerLevel level, BlockPos at) {
		Optional<Holder.Reference<Structure>> city = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).get(BuiltinStructures.ANCIENT_CITY);
		if (city.isEmpty()) return null;
		BlockPos found = level.findNearestMapStructure(HolderSet.direct(city.get()), at, 100, false);
		if (found == null) return null;
		ChunkAccess chunk = level.getChunk(found.getX() >> 4, found.getZ() >> 4);
		StructureStart start = chunk.getStartForStructure(city.get().value());
		if (start == null || !start.isValid()) return null;
		BoundingBox box = start.getBoundingBox();
		BlockPos c = box.getCenter();
		return new BoundingBox(Math.max(box.minX(), c.getX() - SCAN_RADIUS), box.minY(), Math.max(box.minZ(), c.getZ() - SCAN_RADIUS),
				Math.min(box.maxX(), c.getX() + SCAN_RADIUS), box.maxY(), Math.min(box.maxZ(), c.getZ() + SCAN_RADIUS));
	}

	/** Find the chests, sensors and shriekers, and pick the chest that shows quiet looting best. */
	private static Plan survey(Level level, BoundingBox box) {
		List<BlockPos> chests = new ArrayList<>(), sensors = new ArrayList<>(), loud = new ArrayList<>();
		for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
			for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
				ChunkAccess chunk = level.getChunk(cx, cz);
				LevelChunkSection[] sections = chunk.getSections();
				for (int si = 0; si < sections.length; si++) {
					LevelChunkSection sec = sections[si];
					if (sec.hasOnlyAir() || !sec.maybeHas(s -> s.is(Blocks.CHEST) || loud(s))) continue;
					int y0 = level.getSectionYFromSectionIndex(si) << 4;
					for (int y = 0; y < 16; y++) {
						if (y0 + y < box.minY() || y0 + y > box.maxY()) continue;
						for (int x = 0; x < 16; x++) {
							for (int z = 0; z < 16; z++) {
								BlockState s = sec.getBlockState(x, y, z);
								BlockPos p = new BlockPos((cx << 4) + x, y0 + y, (cz << 4) + z);
								if (s.is(Blocks.CHEST)) chests.add(p);
								else if (loud(s)) {
									loud.add(p);
									if (!s.is(Blocks.SCULK_SHRIEKER)) sensors.add(p);
								}
							}
						}
					}
				}
			}
		}
		BlockPos centre = box.getCenter();
		chests.sort((a, b) -> Double.compare(a.distSqr(centre), b.distSqr(centre)));
		Plan best = null;
		double bestScore = -1;
		for (BlockPos chest : chests.subList(0, Math.min(40, chests.size()))) {
			Plan p = plan(level, chest, sensors, loud);
			if (p == null) continue;
			int near = 0;
			for (BlockPos l : p.loud) near++;
			double score = near * 2 + Math.min(2, p.listeners.size()) * 3 + p.route.size() * 0.1;
			if (score > bestScore) {
				bestScore = score;
				best = p;
			}
		}
		return best;
	}

	/** One chest: where to stand, who'd hear it, where the wool goes, and a quiet way in. Null if it won't do. */
	private static Plan plan(Level level, BlockPos chest, List<BlockPos> sensors, List<BlockPos> loud) {
		BlockPos stand = null;
		Direction facing = level.getBlockState(chest).getValue(ChestBlock.FACING);
		List<BlockPos> tries = new ArrayList<>();
		for (int dy : new int[] {0, -1, 1}) tries.add(chest.relative(facing).above(dy));
		for (Direction d : Direction.Plane.HORIZONTAL) {
			for (int dy : new int[] {0, -1, 1}) tries.add(chest.relative(d).above(dy));
		}
		for (BlockPos t : tries) {
			if (standable(level, t)) {
				stand = t;
				break;
			}
		}
		if (stand == null) return null;
		Vec3 eye = feet(level, stand).add(0, 1.62, 0);
		List<BlockPos> listeners = new ArrayList<>(), wools = new ArrayList<>();
		for (BlockPos s : sensors) {
			int range = SculkGuard.range(level.getBlockState(s));
			if (s.distSqr(chest) > (double) range * range || SculkGuard.occluded(level, chest, s)) continue;
			BlockPos spot = null;
			for (BlockPos w : SculkGuard.woolSpots(level, chest, s)) {
				if (w.equals(stand) || w.equals(stand.above()) || Vec3.atCenterOf(w).distanceTo(eye) > 4.5) continue;
				spot = w;
				break;
			}
			if (spot == null) return null;   // a sensor we couldn't silence: the mod leaves such a chest alone
			listeners.add(s);
			wools.add(spot);
		}
		if (listeners.isEmpty()) return null;
		List<BlockPos> path = way(level, stand, loud);
		if (path == null) return null;
		List<Vec3> route = new ArrayList<>();
		for (BlockPos f : path) route.add(feet(level, f));
		List<BlockPos> near = new ArrayList<>();
		for (BlockPos l : loud) {
			for (Vec3 r : route) {
				if (Vec3.atCenterOf(l).distanceToSqr(r) < 9) {
					near.add(l);
					break;
				}
			}
		}
		for (BlockPos l : listeners) if (!near.contains(l)) near.add(l);
		return new Plan(chest, stand, listeners, wools, route, near);
	}

	/**
	 * A quiet way in: breadth-first from where the bot will stand, walking (8 ways, a step up or down, short drops),
	 * never onto a sensor or shrieker, never mining or placing. The start is the spot 12–22 steps out whose way passes
	 * the most sculk; the result runs from there to the chest.
	 */
	private static List<BlockPos> way(Level level, BlockPos stand, List<BlockPos> loud) {
		Map<BlockPos, BlockPos> parent = new HashMap<>();
		Map<BlockPos, Integer> dist = new HashMap<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		parent.put(stand, stand);
		dist.put(stand, 0);
		queue.add(stand);
		while (!queue.isEmpty()) {
			BlockPos f = queue.poll();
			int d = dist.get(f);
			if (d >= ROUTE_MAX) continue;
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					if (dx == 0 && dz == 0) continue;
					boolean diagonal = dx != 0 && dz != 0;
					if (diagonal && !(open(level, f.offset(dx, 0, 0)) && open(level, f.offset(dx, 1, 0)) && open(level, f.offset(0, 0, dz))
							&& open(level, f.offset(0, 1, dz)))) continue;
					for (int dy : diagonal ? new int[] {0} : new int[] {0, 1, -1, -2}) {
						BlockPos n = f.offset(dx, dy, dz);
						if (parent.containsKey(n) || !standable(level, n)) continue;
						if (dy == 1 && !open(level, f.above(2))) continue;
						if (dy < 0 && !(open(level, f.offset(dx, 0, dz)) && open(level, f.offset(dx, 1, dz)))) continue;
						parent.put(n, f);
						dist.put(n, d + 1);
						queue.add(n);
						break;
					}
				}
			}
		}
		BlockPos start = null;
		double best = -1;
		for (Map.Entry<BlockPos, Integer> e : dist.entrySet()) {
			int d = e.getValue();
			if (d < 6) continue;
			int passes = 0;
			for (BlockPos c = e.getKey(); !c.equals(stand); c = parent.get(c)) {
				for (BlockPos l : loud) if (l.distSqr(c) <= 6) passes++;
			}
			double score = (d >= ROUTE_MIN ? 100 : 0) + passes * 3 + d;
			if (score > best) {
				best = score;
				start = e.getKey();
			}
		}
		if (start == null) return null;
		List<BlockPos> path = new ArrayList<>();
		for (BlockPos c = start; ; c = parent.get(c)) {
			path.add(c);
			if (c.equals(stand)) break;
		}
		return path;
	}

	private static boolean loud(BlockState s) {
		return s.is(Blocks.SCULK_SENSOR) || s.is(Blocks.CALIBRATED_SCULK_SENSOR) || s.is(Blocks.SCULK_SHRIEKER);
	}

	/** Nothing to bump into (and not sculk). */
	private static boolean open(Level level, BlockPos p) {
		return Walker.open(level, p, LootShot::loud);
	}

	private static boolean standable(Level level, BlockPos f) {
		return Walker.standable(level, f, LootShot::loud);
	}

	private static Vec3 feet(Level level, BlockPos f) {
		return Walker.feet(level, f);
	}

	/** Faint, invisible lights along the way (light blocks) so the camera can see in the dark. */
	private static void light(ServerLevel level, Plan plan) {
		BlockState dim = Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 9);
		for (int i = 0; i < plan.route.size(); i += 3) lamp(level, BlockPos.containing(plan.route.get(i)), dim);
		lamp(level, plan.stand, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 12));
	}

	private static void lamp(ServerLevel level, BlockPos feet, BlockState light) {
		for (BlockPos p : new BlockPos[] {feet.above(2), feet.above()}) {
			if (level.getBlockState(p).isAir()) {
				Stage.set(level, p, light);
				return;
			}
		}
	}

	/** Only when the world has no ancient city: a deepslate hall with sensors, shriekers and a chest, near you. */
	private BoundingBox buildHall(ServerLevel level, BlockPos at) {
		int floor = Math.max(level.getMinY() + 10, Stage.ground(level, at.getX(), at.getZ()) - 34);
		BlockPos o = new BlockPos(at.getX(), floor + 1, at.getZ());
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
					Stage.set(level, o.offset(x, y, z), s);
				}
			}
		}
		for (int x = -11; x <= 11; x += 4) {
			for (int z : new int[] {-6, 6}) {
				for (int y = 0; y <= 6; y++) {
					Stage.set(level, o.offset(x, y, z), y == 0 || y == 6 ? Blocks.CHISELED_DEEPSLATE.defaultBlockState() : Blocks.POLISHED_DEEPSLATE.defaultBlockState());
				}
			}
		}
		BlockState lantern = Blocks.SOUL_LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true);
		for (int x = -9; x <= 9; x += 6) {
			for (int z : new int[] {-4, 4}) Stage.set(level, o.offset(x, 6, z), lantern);
		}
		for (int[] p : new int[][] {{-11, -2}, {-7, 5}, {-1, -5}, {3, 5}, {9, -5}, {12, 2}}) {
			Stage.set(level, o.offset(p[0], 0, p[1]), Blocks.CANDLE.defaultBlockState().setValue(BlockStateProperties.CANDLES, 1 + Math.floorMod(p[0], 3))
					.setValue(BlockStateProperties.LIT, true));
		}
		for (int[] s : new int[][] {{-8, 1}, {-4, -2}, {0, 2}, {4, -1}, {7, 3}, {11, 4}}) Stage.set(level, o.offset(s[0], 0, s[1]), Blocks.SCULK_SENSOR.defaultBlockState());
		for (int[] s : new int[][] {{-6, -4}, {-2, 4}, {2, -4}, {6, -3}, {-10, -4}}) Stage.set(level, o.offset(s[0], 0, s[1]), Blocks.SCULK_SHRIEKER.defaultBlockState());
		Stage.set(level, o.offset(11, 0, 0), Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.WEST));
		return new BoundingBox(o.getX() - 12, o.getY() - 1, o.getZ() - 6, o.getX() + 12, o.getY() + 6, o.getZ() + 6);
	}

	// ---- the take ------------------------------------------------------------------------------------------------

	@Override
	void start() {
		Vec3 first = plan.route.getFirst(), stand = plan.route.getLast();
		double length = 0;
		for (int i = 1; i < plan.route.size(); i++) length += plan.route.get(i - 1).distanceTo(plan.route.get(i));
		double speed = Math.max(1.3, Math.min(2.8, length / (ARRIVE - ENTER)));
		in = new Anim.Track().at(ENTER, first);
		for (int i = 1; i < plan.route.size(); i++) in.to(plan.route.get(i), speed);
		out = new Anim.Track().at(LEAVE, stand);
		for (int i = plan.route.size() - 2; i >= Math.max(0, plan.route.size() - 6); i--) out.to(plan.route.get(i), speed);
		bot = add(Actor.me(level, first, Anim.yawTo(first, plan.route.get(Math.min(1, plan.route.size() - 1)))));
		bot.light = GREEN;
		bot.holdOff(Items.SOUL_LANTERN);
		bot.crouch(true);
		// framing: along the way in, from behind and to one side of it, riding with the bot; then close on the chest
		Vec3 d = new Vec3(stand.x - first.x, 0, stand.z - first.z);
		d = d.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : d.normalize();
		Vec3 side = new Vec3(-d.z, 0, d.x);
		Vec3 chest = Vec3.atCenterOf(plan.chest), wool = Vec3.atCenterOf(plan.wools.getFirst());
		Vec3 behind = d.scale(-1);
		cam.key(0, on(bot, behind.x * 4.2 + side.x * 1.6, 2.6, behind.z * 4.2 + side.z * 1.6), on(bot, 0, 0.9, 0), 64)
				.key((ENTER + ARRIVE) / 2, on(bot, behind.x * 3.3 + side.x * 1.8, 2.0, behind.z * 3.3 + side.z * 1.8), on(bot, d.x * 1.5, 0.8, d.z * 1.5), 60)
				.key(LISTEN - 0.2, stand.add(behind.scale(2.6)).add(side.scale(2.3)).add(0, 1.9, 0), chest.lerp(wool, 0.5), 56)
				.key(WOOL + 0.6, stand.add(behind.scale(1.7)).add(side.scale(2.0)).add(0, 1.5, 0), wool, 50)
				.key(TAKE + 0.8, stand.add(behind.scale(1.3)).add(side.scale(1.7)).add(0, 1.4, 0), chest.add(0, 0.6, 0), 50)
				.key(duration, stand.add(behind.scale(4.8)).add(side.scale(2.6)).add(0, 3.2, 0), stand.add(0, 0.6, 0), 62);
		cam.watch(on(bot, 0, 1.0, 0), 0, LISTEN, 0.55);
		cam.watch(on(bot, 0, 1.0, 0), LEAVE, duration, 0.5);
	}

	@Override
	void tick(double t) {
		if (t < LEAVE) {
			bot.follow(in, t);
			if (t > in.end()) bot.look(t < WOOL + 0.6 ? Vec3.atCenterOf(plan.wools.getFirst()) : Vec3.atCenterOf(plan.chest));
		} else {
			bot.follow(out, t);
			bot.look(null);
		}
		if (at(t, WOOL - 0.4)) bot.hold(Items.WOOL.pick(DyeColor.WHITE));
		for (int i = 0; i < plan.wools.size(); i++) {
			if (at(t, WOOL + i * 0.5)) {
				bot.look(Vec3.atCenterOf(plan.wools.get(i)));
				bot.swing();
				put(plan.wools.get(i), Blocks.WOOL.pick(DyeColor.WHITE).defaultBlockState());
			}
		}
		if (at(t, WOOL + plan.wools.size() * 0.5 + 0.3)) bot.hold(Items.AIR);
		if (at(t, OPEN)) {
			bot.swing();
			level.blockEvent(plan.chest, Blocks.CHEST, 1, 1);
			level.playLocalSound(plan.chest, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.5f, 1, false);
		}
		if (at(t, CLOSE)) {
			level.blockEvent(plan.chest, Blocks.CHEST, 1, 0);
			level.playLocalSound(plan.chest, SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 0.5f, 1, false);
		}
		if (every(t, 0, duration, 0.5)) particles(ParticleTypes.SCULK_SOUL, bot.pos().add(0, 1.5, 0), 1, 10, 0.01);
	}

	@Override
	void end() {
		if (level != null && plan != null) {   // the next take finds the city as it was
			for (BlockPos w : plan.wools) place(w, Blocks.AIR.defaultBlockState());
		}
		super.end();
	}

	@Override
	void draw(Fx fx, double t) {
		double marks = Anim.ramp(t, 1.0, 2.2);
		boolean[] heard = new boolean[plan.loud.size()];
		for (int i = 0; i < plan.loud.size(); i++) {
			BlockPos l = plan.loud.get(i);
			heard[i] = plan.listeners.contains(l) && Anim.window(t, LISTEN, LISTEN + 0.3, WOOL, WOOL + 0.4) > 0;
			mark(fx, l, heard[i] ? SOUL : RED, marks);
		}
		// the route, winding between them
		if (t > 1.0 && t < LEAVE + 2) {
			List<Vec3> pts = new ArrayList<>();
			for (Vec3 r : plan.route) pts.add(r.add(0, 0.08, 0));
			double fadeOut = 1 - Anim.ramp(t, in.end() + 0.5, in.end() + 1.5);
			fx.ribbon(pts, 0.09, Fx.argb((int) (230 * fadeOut), AMBER), Anim.ramp(t, 1.0, 2.6));
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
		// the vibration the lid would make, heard by each sensor — then stopped by the wool
		Vec3 from = Vec3.atCenterOf(plan.chest);
		for (int i = 0; i < plan.listeners.size(); i++) {
			BlockPos sensor = plan.listeners.get(i), w = plan.wools.get(i);
			double listen = Anim.window(t, LISTEN, LISTEN + 0.3, WOOL - 0.3, WOOL);
			if (listen > 0) {
				wave(fx, from, Vec3.atCenterOf(sensor), t - LISTEN, Fx.argb((int) (230 * listen), SOUL));
				fx.glowBox(new AABB(sensor).setMaxY(sensor.getY() + 0.5).inflate(0.04), Fx.argb((int) (70 * listen * (0.6 + 0.4 * Math.sin(t * 10))), SOUL));
			}
			double placed = WOOL + i * 0.5;
			double blocked = Anim.window(t, placed + 0.3, placed + 0.6, OPEN + 0.6, OPEN + 1.2);
			if (blocked > 0) {
				Vec3 stop = Vec3.atCenterOf(w).add(Vec3.atCenterOf(w).subtract(from).normalize().scale(-0.55));
				wave(fx, from, stop, t - placed - 0.3, Fx.argb((int) (230 * blocked), SOUL));
				fx.spark(stop, 0.5 + 0.15 * Math.sin(t * 14), Fx.argb((int) (160 * blocked), 0xFFFFFF));
				fx.outline(w, Fx.argb((int) (255 * blocked), GREEN), 3f);
			}
		}
		// the loot rises out of the chest, hangs a moment with its names, then flies to the bot
		Vec3 lid = Vec3.atCenterOf(plan.chest).add(0, 0.5, 0);
		Vec3 toBot = Vec3.atCenterOf(plan.stand).subtract(Vec3.atCenterOf(plan.chest)).normalize();
		Vec3 across = new Vec3(-toBot.z, 0, toBot.x);
		for (int i = 0; i < LOOT.length; i++) {
			double t0 = OPEN + 0.3 + i * 0.18;
			if (t < t0) continue;
			Vec3 hover = lid.add(toBot.scale(0.6)).add(across.scale((i - 2) * 0.55)).add(0, 0.9 + 0.12 * Math.sin(t * 2 + i), 0);
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
	private void mark(Fx fx, BlockPos p, int color, double a) {
		fx.ring(Vec3.atBottomCenterOf(p).add(0, 0.52, 0), 0.75, 0.05, Fx.argb((int) (230 * a), color), a);
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
