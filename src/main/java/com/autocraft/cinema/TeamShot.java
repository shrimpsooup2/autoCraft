package com.autocraft.cinema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;
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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Building together, on the build-up and the finale. The schematic hangs over the empty site as a see-through
 * ghost, like Litematica shows it. You host a team; three friends join and their bots beam in beside you (one a
 * beat); you start the build: roles go out (you place, one digs the site out, the others gather into the chest),
 * lights go green. On the finale's first bar the time-lapse starts — the sun sweeps over, the house rises layer by
 * layer — and every couple of bars the camera drops in on one player in slow motion: the digger breaking a boulder
 * on the site, a gatherer chopping, a gatherer at the chest, and you placing: always standing where you could
 * really reach the block (on the ground, or up on the walls already built), hopping to the next spot when it's out
 * of reach. Then a flag goes up.
 */
final class TeamShot extends Shot {
	private static final int CYAN = 0x39D0FF, GREEN = 0x5EE36A;
	private static final String[] MATES = {"Alex", "Kai", "Mira"};
	private static final String[] ROLES = {"digger", "gatherer", "gatherer"};
	private static final double REACH = 4.5, WALK = 4.3, SLOW = 0.45;
	/** Bars of the song. */
	private static final double GHOST = 68.15, HOST = 68.9, ORDER = 71.6, GO = 71.75, BUILD0 = 73, BUILD1 = 79.5, POLE = 79.5, FLAG = 79.9, FIREWORKS = 80.75;
	private static final double[] JOIN = {69.5, 70.0, 70.5};
	/** The slow-motion drop-ins (bars): the digger, a gatherer chopping, a gatherer at the chest, you placing. */
	private static final double[][] FOCUS = {{73.75, 74.75}, {75.25, 76.25}, {76.75, 77.75}, {78.25, 79.25}};

	private Blueprint plan;
	private BlockPos corner, depot, pole;
	private final List<BlockPos> boulders = new ArrayList<>();
	private List<Fx.GhostBlock> ghosts = List.of();
	private int placed;
	private Actor me;
	private final Actor[] mates = new Actor[3];
	private Vec3 gather, dropSpot, centre;
	private Vec3[] spots, front, sources;
	/** Where you stand to place each block, in build order. */
	private Vec3[] stands = new Vec3[0];
	private double endStory, rate;
	private Warp story;
	private double prevStory = -1;
	private Loop[] loops;
	/** The digger, in story seconds: when it sets off for each boulder, starts breaking it, and it breaks; and when it's done. */
	private double[] digWalk = new double[0], digBreak = new double[0], digAt = new double[0];
	private double digEnd;
	private boolean lidOpen;
	private Anim.Track leaderToPole;

	/** A gatherer's round: from the chest to a source, work, back carrying, drop it in. In story seconds. */
	private record Loop(Vec3 home, Vec3 source, double walk, double work, double drop, double offset, Item carry) {
		double period() {
			return 2 * walk + work + drop;
		}

		double phase(double s) {
			double p = (s + offset) % period();
			return p < 0 ? p + period() : p;
		}

		Vec3 pos(double s) {
			double p = phase(s);
			if (p < walk) return Anim.lerp(home, source, p / walk);
			if (p < walk + work) return source;
			if (p < 2 * walk + work) return Anim.lerp(source, home, (p - walk - work) / walk);
			return home;
		}

		boolean working(double s) {
			double p = phase(s);
			return p >= walk && p < walk + work;
		}

		boolean carrying(double s) {
			double p = phase(s);
			return p >= walk + work && p < 2 * walk + work;
		}

		boolean dropping(double s) {
			return phase(s) >= 2 * walk + work;
		}
	}

	TeamShot() {
		super("Team", "Build together", 7, DAY0);
	}

	private static final long DAY0 = 1500, DAY1 = 10500;

	@Override
	BlockPos offset() {
		return new BlockPos(40, 0, 220);   // well away from the other sets: travelled to while the screen is black
	}

	@Override
	void build(ServerLevel level, BlockPos at) {
		plan = Blueprint.load();
		int r = plan.reach();
		int y = Stage.pad(level, at.getX(), at.getZ(), r + 14);
		origin = new BlockPos(at.getX(), y + 1, at.getZ());
		corner = plan.corner(new BlockPos(at.getX(), y, at.getZ()));
		int gx = -(r + 7);
		depot = b(gx, 0, -2);
		Stage.set(level, depot, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.WEST));
		Sets.oak(level, origin.getX() + gx - 6, origin.getY(), origin.getZ() - 6, 5);
		Sets.oak(level, origin.getX() + gx - 9, origin.getY(), origin.getZ() - 1, 6);
		Stage.fill(level, origin.getX() + gx - 5, origin.getY(), origin.getZ() + 7, origin.getX() + gx - 3, origin.getY() + 1, origin.getZ() + 8,
				Blocks.STONE.defaultBlockState());
		Random rng = new Random(3);
		boulders.clear();
		for (int i = 0; i < 3; i++) {
			BlockPos p = corner.offset(2 + rng.nextInt(Math.max(1, plan.size.getX() - 4)), 1, 2 + rng.nextInt(Math.max(1, plan.size.getZ() - 4)));
			Stage.set(level, p, Blocks.MOSSY_COBBLESTONE.defaultBlockState());
			boulders.add(p);
		}
		boulders.sort((a, b) -> Integer.compare(a.getX(), b.getX()));
		pole = b(gx + 3, 0, 4);
	}

	// ---- setting up: the people, the plan for where you stand, the story clock -----------------------------------

	@Override
	void start() {
		ghosts = plan.ghosts(corner);
		placed = 0;
		int r = plan.reach();
		centre = new Vec3(corner.getX() + plan.size.getX() / 2.0, origin.getY(), corner.getZ() + plan.size.getZ() / 2.0);
		gather = v(-(r + 7) + 0.5, 0, 0.5);
		dropSpot = Vec3.atBottomCenterOf(depot).add(-1.0, 0, 0);
		spots = new Vec3[] {gather.add(-1.6, 0, 1.8), gather.add(-2.3, 0, -0.4), gather.add(-1.4, 0, -2.6)};
		Vec3 pp = Vec3.atBottomCenterOf(pole);
		front = new Vec3[] {pp.add(-2.0, 0, -1.6), pp.add(-2.6, 0, 0.2), pp.add(-1.8, 0, 2.0)};
		sources = new Vec3[] {gather.add(-5.2, 0, -5.0), gather.add(-8.2, 0, -0.2), gather.add(-4.0, 0, 5.9)};
		me = add(Actor.me(level, gather.add(1.6, 0, 0), 90));
		me.light = FilmHud.RED;
		// the story: the build takes endStory seconds at four blocks a second; the drop-ins land on its chronology
		endStory = Math.max(16, plan.count() / 4.0);
		rate = plan.count() / endStory;
		double q1 = 0.25 * endStory, q2 = 0.45 * endStory, q3 = 0.75 * endStory, slow = Score.BAR * SLOW;
		story = new Warp(new double[][] {{l(BUILD0), 0}, {l(FOCUS[0][0]), 1.0}, {l(FOCUS[0][1]), 1.0 + slow}, {l(FOCUS[1][0]), q1},
			{l(FOCUS[1][1]), q1 + slow}, {l(FOCUS[2][0]), q2}, {l(FOCUS[2][1]), q2 + slow}, {l(FOCUS[3][0]), q3}, {l(FOCUS[3][1]), q3 + slow},
			{l(BUILD1), endStory}, {l(BUILD1) + 2, endStory + 2 * Score.BAR}});
		// the gatherers' rounds, timed so each is doing its thing when the camera drops in on it
		double walkA = sources[0].distanceTo(dropSpot) / WALK, walkB = sources[2].distanceTo(dropSpot) / WALK, walkD = sources[1].distanceTo(dropSpot) / WALK;
		double sA = q1 + slow / 2, sB = q2 + slow / 2;
		Loop a = new Loop(dropSpot, sources[0], walkA, 2.6, 0.7, 0, Items.OAK_LOG);
		a = new Loop(a.home, a.source, a.walk, a.work, a.drop, walkA + 1.3 - sA, a.carry);
		Loop b = new Loop(dropSpot.add(0, 0, 0.6), sources[2], walkB, 2.2, 0.8, 0, Items.COBBLESTONE);
		b = new Loop(b.home, b.source, b.walk, b.work, b.drop, 2 * walkB + 2.2 + 0.4 - sB, b.carry);
		// the digger: the walk to the first boulder before the camera drops in, the break during it, then the rest quickly
		int nb = boulders.size();
		digWalk = new double[nb];
		digBreak = new double[nb];
		digAt = new double[nb];
		for (int k = 0; k < nb; k++) {
			digWalk[k] = k == 0 ? 0.1 : digAt[k - 1];
			digBreak[k] = k == 0 ? 1.05 : digWalk[k] + 0.3;
			digAt[k] = k == 0 ? 1.6 : digBreak[k] + 0.5;
		}
		digEnd = nb == 0 ? 0 : digAt[nb - 1];
		Loop d = new Loop(dropSpot.add(0, 0, -0.6), sources[1], walkD, 2.4, 0.6, -digEnd + 0.3, Items.OAK_LOG);
		loops = new Loop[] {d, a, b};
		planStands();
		camera();
	}

	/** Bars of the song into this shot (for the story clock). */
	private double l(double globalBar) {
		return globalBar - 68;
	}

	private double storyAt(double t) {
		return Math.max(0, story.story(t));
	}

	/**
	 * Where you stand for every block, in build order: you keep your spot while the next block is in reach and not
	 * where you're standing; otherwise you move to the nearest spot that is — on the ground or on top of blocks
	 * already placed, with room for you, within reach — as a real builder would.
	 */
	private void planStands() {
		int n = ghosts.size();
		stands = new Vec3[n];
		Map<Long, Integer> index = new HashMap<>();
		boolean[] solid = new boolean[n];
		for (int i = 0; i < n; i++) {
			BlockPos p = ghosts.get(i).pos();
			index.put(p.asLong(), i);
			solid[i] = !plan.states.get(i).getCollisionShape(level, p).isEmpty();
		}
		BlockPos cur = BlockPos.containing(gather.add(4, 0, 0));
		for (int i = 0; i < n; i++) {
			BlockPos c = ghosts.get(i).pos();
			if (!canStand(cur, c, i, index, solid)) {
				BlockPos best = null;
				double bestScore = Double.MAX_VALUE;
				for (int dx = -4; dx <= 4; dx++) {
					for (int dz = -4; dz <= 4; dz++) {
						for (int dy = -4; dy <= 2; dy++) {
							BlockPos f = c.offset(dx, dy, dz);
							if (!canStand(f, c, i, index, solid)) continue;
							double score = f.distSqr(cur) + Math.abs(f.getY() - (c.getY() - 1)) * 0.5;
							if (score < bestScore) {
								bestScore = score;
								best = f;
							}
						}
					}
				}
				if (best != null) cur = best;
			}
			stands[i] = Vec3.atBottomCenterOf(cur);
		}
	}

	private boolean canStand(BlockPos f, BlockPos c, int i, Map<Long, Integer> index, boolean[] solid) {
		if (f.equals(c) || f.above().equals(c)) return false;
		if (Vec3.atCenterOf(c).distanceTo(Vec3.atBottomCenterOf(f).add(0, 1.62, 0)) > REACH) return false;
		if (taken(f, i, index, solid) || taken(f.above(), i, index, solid)) return false;
		BlockPos under = f.below();
		if (under.getY() == origin.getY() - 1) return true;   // the lawn
		Integer k = index.get(under.asLong());
		return k != null && k < i && solid[k];
	}

	private boolean taken(BlockPos p, int i, Map<Long, Integer> index, boolean[] solid) {
		if (p.getY() < origin.getY()) return true;
		Integer k = index.get(p.asLong());
		return k != null && k < i && solid[k];
	}

	/** Where you are at story time s: on your spot for the block in hand, hopping across when the spot changes. */
	private Vec3 placerAt(double s) {
		if (stands.length == 0) return gather;
		double f = s * rate;
		int i = Math.min(stands.length - 1, (int) f);
		if (s < 0.4) return Anim.arc(gather.add(1.6, 0, 0), stands[0], Anim.smooth(s / 0.4), 0.6);
		Vec3 cur = stands[i], prev = stands[Math.max(0, i - 1)];
		if (cur.equals(prev)) return cur;
		double u = Anim.clamp01((f - i) / 0.6);
		return Anim.arc(prev, cur, Anim.smooth(u), cur.y > prev.y ? 0.7 : 0.25);
	}

	private Vec3 currentCell() {
		return ghosts.isEmpty() ? centre : Vec3.atCenterOf(ghosts.get(Math.min(placed, ghosts.size() - 1)).pos());
	}

	private Supplier<Vec3> onMate(int i, double dx, double dy, double dz) {
		return () -> {
			Actor a = mates[i];
			Vec3 p = a != null ? a.entity.getPosition(partialTick()) : spots[i];
			return p.add(dx, dy, dz);
		};
	}

	private void camera() {
		int r = plan.reach();
		double sy = plan.size.getY(), d = r * 1.7 + 7;
		Vec3 look = centre.add(0, sy * 0.4, 0);
		Vec3 pp = Vec3.atBottomCenterOf(pole);
		// the ghost over the empty site, then the team gathering
		cam.key(0, centre.add(-r * 1.5 - 5, sy + 9, -r * 1.3 - 2), look, 64)
				.key(bar(69.25), gather.add(6.5, 3.0, -7.0), gather.add(-1.5, 1.2, 0), 58)
				.key(bar(72.5), gather.add(5.0, 2.4, -5.2), gather.add(-1.2, 1.3, 0));
		// time-lapse orbit between the drop-ins
		double[] orbit = {BUILD0 + 0.4, 75.0, 76.5, 78.0, BUILD1};
		for (int k = 0; k < orbit.length; k++) {
			double a = Math.PI + 0.75 - k * 0.42;
			cam.key(bar(orbit[k]), centre.add(Math.cos(a) * d, sy * 0.9 + 5, Math.sin(a) * d), look, 66);
		}
		// the drop-ins: close on each player, riding with them
		cam.key(bar(FOCUS[0][0] + 0.15), onMate(0, -2.4, 1.7, -2.0), onMate(0, 0.6, 0.6, 0.4), 52)
				.key(bar(FOCUS[0][1] - 0.1), onMate(0, -2.0, 1.4, -1.6), onMate(0, 0.8, 0.5, 0.6), 50);
		cam.key(bar(FOCUS[1][0] + 0.15), onMate(1, 2.6, 1.8, 1.8), onMate(1, -0.6, 1.2, -0.6), 52)
				.key(bar(FOCUS[1][1] - 0.1), onMate(1, 2.2, 1.5, 1.4), onMate(1, -0.8, 1.2, -0.8), 50);
		Vec3 chest = Vec3.atCenterOf(depot);
		cam.key(bar(FOCUS[2][0] + 0.15), chest.add(-3.6, 2.0, 2.4), chest.add(-0.6, 0.2, 0.2), 52)
				.key(bar(FOCUS[2][1] - 0.1), chest.add(-3.0, 1.7, 1.8), chest.add(-0.6, 0.3, 0.4), 50);
		cam.key(bar(FOCUS[3][0] + 0.15), on(me, -2.8, 2.2, -2.4), this::currentCell, 54)
				.key(bar(FOCUS[3][1] - 0.1), on(me, -2.2, 1.8, -2.0), this::currentCell, 52);
		for (int k = 0; k < 2; k++) cam.watch(onMate(k, 0, 1.0, 0), bar(FOCUS[k][0]), bar(FOCUS[k][1]), 0.5);
		cam.watch(onMate(2, 0, 1.0, 0), bar(FOCUS[2][0]), bar(FOCUS[2][1]), 0.4);
		cam.watch(on(me, 0, 1.2, 0), bar(FOCUS[3][0]), bar(FOCUS[3][1]), 0.35);
		// the flag
		cam.key(bar(FLAG - 0.1), pp.add(6.5, 3.2, -6.0), pp.add(0, 3.2, 0), 58)
				.key(bar(FIREWORKS), pp.add(5.5, 4.6, -5.0), pp.add(0, 4.8, 0))
				.key(duration, pp.add(9, 7.5, -10), centre.add(0, sy * 0.45, 0), 66);
	}

	// ---- playing it ----------------------------------------------------------------------------------------------

	@Override
	void tick(double t) {
		// friends' bots beam in beside you, one a beat
		for (int i = 0; i < 3; i++) {
			if (at(t, bar(JOIN[i]))) {
				mates[i] = add(Actor.player(level, spots[i], -90, MATES[i]));
				mates[i].light = FilmHud.RED;
				particles(ParticleTypes.END_ROD, spots[i].add(0, 1, 0), 24, 1.2, 0.08);
			}
		}
		if (t < bar(BUILD0)) {
			me.look(t > bar(JOIN[0]) - 1 ? spots[1].add(0, 1.5, 0) : null);
			for (Actor m : mates) if (m != null) m.look(me.eyes());
		}
		for (int k = 0; k < 4; k++) {   // roles: lights go green one a beat
			Actor a = k == 0 ? me : mates[k - 1];
			if (a == null) continue;
			double tk = bar(GO + k * 0.25);
			if (at(t, tk)) a.light = GREEN;
			a.lightFlash = Anim.window(t, tk - 0.1, tk, tk + 0.1, tk + 0.5);
		}
		double s = storyAt(t);
		if (t >= bar(BUILD0) && t < bar(BUILD1) + 0.6) build(t, s);
		if (t >= bar(BUILD0)) work(t, s);
		prevStory = s;
		// the flag
		if (at(t, bar(BUILD1) + 0.05)) {
			me.free = false;
			leaderToPole = new Anim.Track().at(bar(BUILD1) + 0.05, me.pos()).to(Vec3.atBottomCenterOf(pole).add(1.3, 0, -0.9), 12).hold(bar(POLE) + 0.5);
		}
		if (leaderToPole != null && t > bar(BUILD1)) {
			me.follow(leaderToPole, t);
			me.look(Vec3.atCenterOf(pole.above(4)));
		}
		for (int k = 0; k < 6; k++) {
			if (at(t, bar(POLE) + 0.06 * k)) {
				me.swing();
				put(pole.above(k), Blocks.OAK_FENCE.defaultBlockState());
			}
		}
		if (at(t, bar(FIREWORKS))) {
			Vec3 top = Vec3.atCenterOf(pole.above(7));
			particles(ParticleTypes.FIREWORK, top, 50, 2.0, 0.25);
			particles(ParticleTypes.END_ROD, top, 20, 2.5, 0.1);
			for (Actor a : actors) a.lightFlash = 1;
		}
		if (t > bar(FIREWORKS)) {
			for (Actor a : actors) a.lightFlash = Math.max(0, 1 - (t - bar(FIREWORKS)) * 2);
		}
	}

	/** True on the tick the story clock passes `when`. */
	private boolean atStory(double s, double when) {
		return prevStory < when && s >= when;
	}

	/** Blocks go in at four a story second (so slow motion slows them too); the sun sweeps over; you build from real spots. */
	private void build(double t, double s) {
		int target = (int) Math.min(plan.count(), Math.floor(s * rate));
		boolean any = placed < target;
		for (; placed < target; placed++) place(ghosts.get(placed).pos(), plan.states.get(placed));
		if (every(t, bar(BUILD0), bar(BUILD1), 0.1)) Stage.command("time set " + (long) (DAY0 + (DAY1 - DAY0) * Anim.clamp01(s / endStory)));
		if (t >= bar(BUILD1)) return;
		me.free = true;   // perched where it can reach the block (on the walls it has built), not dropped to the floor
		me.moveTo(placerAt(s)).look(currentCell());
		if (any) me.swing();
		me.hold(placed < plan.count() ? plan.states.get(Math.min(placed, plan.count() - 1)).getBlock().asItem() : Items.AIR);
	}

	private void work(double t, double s) {
		boolean anyDropping = false;
		for (int i = 0; i < 3; i++) {
			Actor a = mates[i];
			if (a == null) continue;
			if (t >= bar(BUILD1)) {   // gather in front of the flag
				Vec3 at = front[i];
				a.moveTo(a.pos().add(at.subtract(a.pos()).scale(0.2))).look(Vec3.atCenterOf(pole.above(4)));
				continue;
			}
			if (i == 0 && s < digEnd) {   // the digger clears the site first: walk to each boulder, break it
				int k = 0;
				while (k < boulders.size() - 1 && s >= digAt[k]) k++;
				BlockPos boulder = boulders.get(k);
				Vec3 stand = Vec3.atBottomCenterOf(boulder).add(-1.1, 0, 0);
				Vec3 from = k == 0 ? spots[0] : Vec3.atBottomCenterOf(boulders.get(k - 1)).add(-1.1, 0, 0);
				if (s < digBreak[k]) {
					a.moveTo(Anim.lerp(from, stand, Anim.ramp(s, digWalk[k], digBreak[k]))).look(null);
				} else {
					a.moveTo(stand).look(Vec3.atCenterOf(boulder));
					crack(boulder, (int) Math.min(9, (s - digBreak[k]) / (digAt[k] - digBreak[k]) * 10));
					if (every(t, 0, duration, 0.25)) a.swing();
				}
				for (int b = 0; b < boulders.size(); b++) {
					if (atStory(s, digAt[b])) dig(boulders.get(b));
				}
				continue;
			}
			Loop loop = loops[i];
			a.moveTo(loop.pos(s));
			if (loop.working(s)) {
				a.look(loop.source.add(-0.6, 1.2, -0.6));
				if (every(t, 0, duration, 0.25)) a.swing();
			} else if (loop.dropping(s)) {
				a.look(Vec3.atCenterOf(depot));
				anyDropping = true;
			} else {
				a.look(null);
			}
		}
		if (anyDropping != lidOpen && t < bar(BUILD1)) {   // the chest opens while someone drops things in
			lidOpen = anyDropping;
			level.blockEvent(depot, Blocks.CHEST, 1, lidOpen ? 1 : 0);
			level.playLocalSound(depot, lidOpen ? SoundEvents.CHEST_OPEN : SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 0.35f, 1, false);
		}
	}

	@Override
	void draw(Fx fx, double t) {
		double s = storyAt(t);
		// the ghost, fading in, then shrinking away block by block as the real ones go in
		int ghostAlpha = (int) (125 * Anim.ramp(t, bar(GHOST), bar(GHOST) + 1.2));
		fx.ghosts(ghosts, placed, ghosts.size(), ghostAlpha);
		AABB box = new AABB(Vec3.atLowerCornerOf(corner), Vec3.atLowerCornerOf(corner.offset(plan.size)));
		fx.outline(box, Fx.argb((int) (220 * Anim.window(t, bar(GHOST), bar(GHOST) + 0.8, bar(BUILD1), bar(BUILD1) + 0.3)), 0x8FE0FF), 2f);
		double done = Anim.window(t, bar(BUILD1), bar(BUILD1) + 0.3, bar(BUILD1) + 1.4, bar(BUILD1) + 2.2);
		if (done > 0) fx.outline(box.inflate(0.05), Fx.argb((int) (255 * done), GREEN), 4f);
		// beams for the bots joining
		for (int i = 0; i < 3; i++) {
			double tj = bar(JOIN[i]);
			double beam = Anim.window(t, tj - 0.6, tj - 0.25, tj + 0.1, tj + 0.6);
			if (beam <= 0) continue;
			Vec3 sp = spots[i];
			fx.ribbon(List.of(sp, sp.add(0, 40, 0)), 0.5 * beam, Fx.argb((int) (200 * beam), CYAN), 1);
			fx.ring(sp.add(0, 0.05, 0), 0.4 + 0.9 * Anim.ramp(t, tj - 0.6, tj + 0.4), 0.06, Fx.argb((int) (230 * beam), CYAN), 1);
		}
		// role labels
		double roles = Anim.window(t, bar(GO), bar(GO) + 0.4, bar(BUILD0) + 0.8, bar(BUILD0) + 1.4);
		if (roles > 0) {
			label(fx, me, "placer", roles);
			for (int i = 0; i < 3; i++) label(fx, mates[i], ROLES[i], roles);
		}
		// placing: a thread of light from your hand to the block going in
		if (t >= bar(BUILD0) && t < bar(BUILD1) && placed > 0) {
			Vec3 cell = Vec3.atCenterOf(ghosts.get(placed - 1).pos());
			Vec3 hand = me.entity.getPosition(fx.partial).add(0, 1.3, 0);
			fx.ribbon(List.of(hand, cell), 0.04, Fx.argb(150, CYAN), 1);
			fx.spark(cell, 0.9, Fx.argb(150, CYAN));
		}
		// what the gatherers carry to the chest
		for (int i = 0; i < 3; i++) {
			if (mates[i] == null || loops == null || t >= bar(BUILD1) || (i == 0 && s < digEnd)) continue;
			if (loops[i].carrying(s)) {
				fx.item(mates[i].entity.getPosition(fx.partial).add(0, 2.35, 0), new ItemStack(loops[i].carry), 0.22, (float) (t * 200), false);
			}
		}
		// progress over the build
		double prog = Anim.window(t, bar(BUILD0) - 0.3, bar(BUILD0), bar(BUILD1) + 0.8, bar(BUILD1) + 1.3);
		if (prog > 0) {
			Vec3 top = centre.add(0, plan.size.getY() + 2.6, 0);
			fx.text(top, Component.literal(plan.name + "   " + placed + " / " + plan.count()), 0.06f, Fx.argb((int) (255 * prog), 0xFFFFFF), 0, true, 0, true);
			double w = Math.max(6, plan.size.getX() * 0.5);
			Vec3 a = top.add(-w / 2, -0.9, 0), b = top.add(w / 2, -0.9, 0);
			fx.ribbon(List.of(a, b), 0.12, Fx.argb((int) (90 * prog), 0x2C5A7A), 1);
			fx.ribbon(List.of(a, b), 0.16, Fx.argb((int) (255 * prog), placed >= plan.count() ? GREEN : CYAN), placed / (double) plan.count());
		}
		// the flag going up
		if (t >= bar(FLAG)) {
			double u = Anim.ramp(t, bar(FLAG), bar(FIREWORKS));
			fx.item(Vec3.atBottomCenterOf(pole).add(0.62, 1.4 + 3.6 * u, 0), new ItemStack(Items.BANNER.pick(DyeColor.RED)), 0.62, 0, true);
		}
	}

	private static void label(Fx fx, Actor a, String text, double alpha) {
		if (a == null || a.entity.isRemoved()) return;
		Vec3 p = a.entity.getPosition(fx.partial).add(0, a.entity.getBbHeight() + 0.85, 0);
		fx.text(p, Component.literal(text), 0.016f, Fx.argb((int) (255 * alpha), 0xFFFFFF), Fx.argb((int) (110 * alpha), 0x000000), true, 0, true);
	}

	@Override
	void hud(FilmHud hud, double t) {
		List<Component> lines = List.of(FilmHud.ac("Team server open. Others run: !ac team join 192.168.1.20:" + com.autocraft.team.Team.DEFAULT_PORT + " K7QW2"),
				FilmHud.notice(MATES[0] + " joined the game"), FilmHud.notice(MATES[1] + " joined the game"), FilmHud.notice(MATES[2] + " joined the game"),
				FilmHud.ac("Team build: you place, " + MATES[0] + " digs the site out, the others gather; materials go to the chest at "
						+ depot.toShortString() + "."));
		double[] times = {bar(HOST), bar(JOIN[0]), bar(JOIN[1]), bar(JOIN[2]), bar(ORDER)};
		hud.chat(FilmHud.upTo(t, times, lines), 1 - Anim.ramp(t, bar(BUILD0) + 0.6, bar(BUILD0) + 1.4));
		typing(hud, t, bar(68.25), bar(HOST), "!ac team host");
		typing(hud, t, bar(71.0), bar(ORDER), "!ac build " + plan.name + " here");
	}

	private static void typing(FilmHud hud, double t, double t0, double t1, String cmd) {
		if (t < t0 || t >= t1) return;
		int n = Math.min(cmd.length(), (int) ((t - t0) / (t1 - t0 - 0.15) * cmd.length()));
		hud.chatInput(cmd.substring(0, Math.max(0, n)), true, 1);
	}
}
