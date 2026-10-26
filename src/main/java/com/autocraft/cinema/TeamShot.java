package com.autocraft.cinema;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Building together. The schematic hangs over the empty site as a see-through ghost, like Litematica shows it.
 * You host a team; three friends join and their bots beam in beside you. You start the build: chat gives out the
 * roles (you place, one digs the site out, the others gather into the chest), lights go green, labels pop up.
 * Then a time-lapse: the sun sweeps over, the house rises layer by layer as you place it, the digger clears the
 * site, the gatherers run between the trees, the rocks and the chest. At the end a flag goes up.
 */
final class TeamShot extends Shot {
	private static final int CYAN = 0x39D0FF, GREEN = 0x5EE36A;
	private static final String[] MATES = {"Alex", "Kai", "Mira"};
	private static final String[] ROLES = {"digger", "gatherer", "gatherer"};
	private static final double GHOST = 0.8, HOST = 3.2, JOIN0 = 4.6, JOIN_GAP = 1.2, ORDER = 9.0, GO = 9.4, BUILD0 = 11.0, BUILD1 = 24.0,
			POLE = 24.8, FLAG = 25.8;
	private static final long DAY0 = 1500, DAY1 = 10500;
	private static final double FAST = 9;

	private Blueprint plan;
	private BlockPos corner, depot, pole;
	private final List<BlockPos> boulders = new ArrayList<>();
	private List<Fx.GhostBlock> ghosts = List.of();
	private int placed;
	private Actor me;
	private final Actor[] mates = new Actor[3];
	private final Anim.Track[] runs = new Anim.Track[3];
	private final List<List<double[]>> carrying = new ArrayList<>();
	private final List<double[]> digs = new ArrayList<>();
	private Vec3 gather, drop, centre;
	private Vec3[] spots, front;
	private double leaderAngle = Double.NaN;
	private Anim.Track leaderToPole;

	TeamShot() {
		super("Team", "Build together", 30, DAY0);
	}

	@Override
	BlockPos offset() {
		return new BlockPos(70, 0, 75);
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

	@Override
	void start() {
		ghosts = plan.ghosts(corner);
		placed = 0;
		leaderAngle = Double.NaN;
		int r = plan.reach();
		centre = new Vec3(corner.getX() + plan.size.getX() / 2.0, origin.getY(), corner.getZ() + plan.size.getZ() / 2.0);
		gather = v(-(r + 7) + 0.5, 0, 0.5);
		drop = Vec3.atBottomCenterOf(depot).add(-1.0, 0, 0);
		spots = new Vec3[] {gather.add(-1.6, 0, 1.8), gather.add(-2.3, 0, -0.4), gather.add(-1.4, 0, -2.6)};
		Vec3 p = Vec3.atBottomCenterOf(pole);
		front = new Vec3[] {p.add(-2.0, 0, -1.6), p.add(-2.6, 0, 0.2), p.add(-1.8, 0, 2.0)};
		me = add(Actor.me(level, gather.add(1.6, 0, 0), 90));
		me.light = FilmHud.RED;
		Vec3[] sources = {gather.add(-5.2, 0, -5.0), gather.add(-8.2, 0, -0.2), gather.add(-4.0, 0, 5.9)};
		carrying.clear();
		digs.clear();
		for (int i = 0; i < 3; i++) {
			Anim.Track tr = new Anim.Track().at(BUILD0 + 0.25 * i, spots[i]);
			if (i == 0) {   // the digger clears the site first
				for (BlockPos b : boulders) {
					tr.to(Vec3.atBottomCenterOf(b).add(-1.1, 0, 0), FAST * 1.3);
					digs.add(new double[] {tr.end(), tr.end() + 0.45});
					tr.hold(tr.end() + 0.5);
				}
			}
			List<double[]> carry = new ArrayList<>();
			while (tr.end() < BUILD1 - 2.0) {
				tr.to(sources[i], FAST).hold(tr.end() + 0.9);
				double leave = tr.end();
				tr.to(drop.add(0, 0, (i - 1) * 0.6), FAST);
				carry.add(new double[] {leave, tr.end()});
				tr.hold(tr.end() + 0.35);
			}
			tr.to(front[i], 5);
			runs[i] = tr;
			carrying.add(carry);
		}
		double sy = plan.size.getY(), d = r * 1.7 + 7;
		Vec3 look = centre.add(0, sy * 0.4, 0);
		cam.key(0, centre.add(-r * 1.5 - 5, sy + 9, -r * 1.3 - 2), look, 64)
				.key(HOST - 0.5, gather.add(6.5, 3.0, -7.0), gather.add(-1.5, 1.2, 0), 58)
				.key(GO + 1.2, gather.add(5.0, 2.4, -5.2), gather.add(-1.2, 1.3, 0));
		for (int k = 0; k <= 4; k++) {
			double a = Math.PI + 0.75 - k * 0.42;
			double tk = BUILD0 + 0.6 + k * (BUILD1 - BUILD0 - 0.6) / 4;
			cam.key(tk, centre.add(Math.cos(a) * d, sy * 0.9 + 5, Math.sin(a) * d), look, 66);
		}
		cam.key(FLAG - 0.4, p.add(6.5, 3.2, -6.0), p.add(0, 3.2, 0), 58)
				.key(FLAG + 2.6, p.add(5.5, 4.6, -5.0), p.add(0, 4.8, 0))
				.key(duration, p.add(9, 7.5, -10), centre.add(0, sy * 0.45, 0), 66);
	}

	@Override
	void tick(double t) {
		// friends' bots beam in beside you
		for (int i = 0; i < 3; i++) {
			double tj = JOIN0 + i * JOIN_GAP;
			if (at(t, tj)) {
				mates[i] = add(Actor.player(level, spots[i], -90, MATES[i]));
				mates[i].light = FilmHud.RED;
				particles(ParticleTypes.END_ROD, spots[i].add(0, 1, 0), 24, 1.2, 0.08);
			}
		}
		if (t < BUILD0) me.look(t > JOIN0 - 1 ? spots[1].add(0, 1.5, 0) : null);
		for (int i = 0; i < 3; i++) {
			if (mates[i] != null && t < BUILD0) mates[i].look(me.eyes());
		}
		// roles: lights go green one by one
		for (int k = 0; k < 4; k++) {
			Actor a = k == 0 ? me : mates[k - 1];
			if (a == null) continue;
			double tk = GO + 0.35 * k;
			if (at(t, tk)) a.light = GREEN;
			a.lightFlash = Anim.window(t, tk - 0.1, tk, tk + 0.1, tk + 0.5);
		}
		if (t >= BUILD0 && t < BUILD1 + 0.6) build(t);
		if (t >= BUILD0) {
			for (int i = 0; i < 3; i++) work(i, t);
		}
		if (at(t, BUILD1 + 0.05)) {
			Vec3 at = me.pos(), stand = Vec3.atBottomCenterOf(pole).add(1.3, 0, -0.9);
			leaderToPole = new Anim.Track().at(BUILD1 + 0.05, at).to(stand, 9).hold(POLE);
		}
		if (leaderToPole != null && t > BUILD1) {
			me.follow(leaderToPole, t);
			if (t >= POLE - 0.3) me.look(Vec3.atCenterOf(pole.above(Math.min(5, (int) ((t - POLE) / 0.13)))));
		}
		for (int k = 0; k < 6; k++) {
			if (at(t, POLE + k * 0.13)) {
				me.swing();
				put(pole.above(k), Blocks.OAK_FENCE.defaultBlockState());
			}
		}
		if (t > FLAG + 2.2) me.look(Vec3.atCenterOf(pole.above(5)));
		if (at(t, FLAG + 2.3)) {
			Vec3 top = Vec3.atCenterOf(pole.above(7));
			particles(ParticleTypes.FIREWORK, top, 50, 2.0, 0.25);
			particles(ParticleTypes.END_ROD, top, 20, 2.5, 0.1);
			for (Actor a : actors) a.lightFlash = 1;
		}
		if (t > FLAG + 2.3) {
			for (Actor a : actors) a.lightFlash = Math.max(0, 1 - (t - FLAG - 2.3) * 2);
		}
	}

	/** Time-lapse: blocks go in at the pace of the clock, the sun sweeps over, you walk round placing them. */
	private void build(double t) {
		double u = Anim.clamp01((t - BUILD0) / (BUILD1 - BUILD0));
		int target = (int) Math.round(plan.count() * u);
		for (; placed < target; placed++) place(ghosts.get(placed).pos(), plan.states.get(placed));
		if (every(t, BUILD0, BUILD1, 0.1)) Stage.command("time set " + (long) (DAY0 + (DAY1 - DAY0) * u));
		if (t >= BUILD1) return;
		BlockPos cur = ghosts.get(Math.min(placed, ghosts.size() - 1)).pos();
		double want = Math.atan2(cur.getZ() + 0.5 - centre.z, cur.getX() + 0.5 - centre.x);
		if (Double.isNaN(leaderAngle)) leaderAngle = Math.atan2(me.pos().z - centre.z, me.pos().x - centre.x);
		double diff = Math.atan2(Math.sin(want - leaderAngle), Math.cos(want - leaderAngle));
		leaderAngle += Math.max(-0.12, Math.min(0.12, diff * 0.2));
		me.moveTo(perimeter(leaderAngle)).look(Vec3.atCenterOf(cur));
		if (every(t, BUILD0, BUILD1, 0.15)) me.swing();
	}

	/** The point at `angle` on a ring path just outside the build's footprint. */
	private Vec3 perimeter(double angle) {
		double hx = plan.size.getX() / 2.0 + 1.6, hz = plan.size.getZ() / 2.0 + 1.6;
		double c = Math.cos(angle), s = Math.sin(angle);
		double k = Math.min(Math.abs(c) < 1e-6 ? Double.MAX_VALUE : hx / Math.abs(c), Math.abs(s) < 1e-6 ? Double.MAX_VALUE : hz / Math.abs(s));
		return centre.add(c * k, 0, s * k);
	}

	private void work(int i, double t) {
		Actor a = mates[i];
		if (a == null) return;
		a.follow(runs[i], t);
		boolean still = runs[i].heading(t).lengthSqr() < 1e-6;
		if (i == 0) {
			for (int k = 0; k < digs.size(); k++) {
				double[] d = digs.get(k);
				BlockPos b = boulders.get(k);
				if (t >= d[0] && t < d[1]) {
					a.look(Vec3.atCenterOf(b));
					crack(b, (int) ((t - d[0]) / (d[1] - d[0]) * 10));
					if (every(t, d[0], d[1], 0.15)) a.swing();
				}
				if (at(t, d[1])) dig(b);
			}
		}
		if (t > BUILD1) {
			a.look(still ? Vec3.atCenterOf(pole.above(4)) : null);
			return;
		}
		if (still && !inDrop(a.pos())) {
			a.look(null);
			if (every(t, BUILD0, BUILD1, 0.22)) a.swing();
		}
	}

	private boolean inDrop(Vec3 p) {
		return p.distanceToSqr(drop) < 2.5;
	}

	@Override
	void draw(Fx fx, double t) {
		// the ghost, fading in, then shrinking away block by block as the real ones go in
		int ghostAlpha = (int) (125 * Anim.ramp(t, GHOST, GHOST + 1.6));
		fx.ghosts(ghosts, placed, ghosts.size(), ghostAlpha);
		AABB box = new AABB(Vec3.atLowerCornerOf(corner), Vec3.atLowerCornerOf(corner.offset(plan.size)));
		double edge = Anim.window(t, GHOST, GHOST + 1.0, BUILD1, BUILD1 + 0.3);
		fx.outline(box, Fx.argb((int) (220 * edge), 0x8FE0FF), 2f);
		double done = Anim.window(t, BUILD1, BUILD1 + 0.3, BUILD1 + 1.6, BUILD1 + 2.6);
		if (done > 0) fx.outline(box.inflate(0.05), Fx.argb((int) (255 * done), GREEN), 4f);
		// beams for the bots joining
		for (int i = 0; i < 3; i++) {
			double tj = JOIN0 + i * JOIN_GAP;
			double beam = Anim.window(t, tj - 0.8, tj - 0.3, tj + 0.1, tj + 0.7);
			if (beam <= 0) continue;
			Vec3 s = spots[i];
			fx.ribbon(List.of(s, s.add(0, 40, 0)), 0.5 * beam, Fx.argb((int) (200 * beam), CYAN), 1);
			fx.ring(s.add(0, 0.05, 0), 0.4 + 0.9 * Anim.ramp(t, tj - 0.8, tj + 0.4), 0.06, Fx.argb((int) (230 * beam), CYAN), 1);
		}
		// role labels
		double roles = Anim.window(t, GO, GO + 0.5, BUILD0 + 2.5, BUILD0 + 3.2);
		if (roles > 0) {
			label(fx, me, "placer", roles);
			for (int i = 0; i < 3; i++) label(fx, mates[i], ROLES[i], roles);
		}
		// placing: a thread of light from your hand to the block going in
		if (t >= BUILD0 && t < BUILD1 && placed > 0) {
			Vec3 cell = Vec3.atCenterOf(ghosts.get(placed - 1).pos());
			Vec3 hand = me.entity.getPosition(fx.partial).add(0, 1.3, 0);
			fx.ribbon(List.of(hand, cell), 0.04, Fx.argb(150, CYAN), 1);
			fx.spark(cell, 0.9, Fx.argb(150, CYAN));
		}
		// what the gatherers carry to the chest
		for (int i = 0; i < 3; i++) {
			if (mates[i] == null) continue;
			Item item = i == 2 ? Items.COBBLESTONE : Items.OAK_LOG;
			for (double[] w : carrying.get(i)) {
				if (t >= w[0] && t < w[1]) fx.item(mates[i].entity.getPosition(fx.partial).add(0, 2.35, 0), new ItemStack(item), 0.22, (float) (t * 200), false);
			}
		}
		// progress over the build
		double prog = Anim.window(t, BUILD0 - 0.4, BUILD0, BUILD1 + 1.0, BUILD1 + 1.6);
		if (prog > 0) {
			Vec3 top = centre.add(0, plan.size.getY() + 2.6, 0);
			String s = plan.name + "   " + placed + " / " + plan.count();
			fx.text(top, Component.literal(s), 0.06f, Fx.argb((int) (255 * prog), 0xFFFFFF), 0, true, 0, true);
			double w = Math.max(6, plan.size.getX() * 0.5);
			Vec3 a = top.add(-w / 2, -0.9, 0), b = top.add(w / 2, -0.9, 0);
			fx.ribbon(List.of(a, b), 0.12, Fx.argb((int) (90 * prog), 0x2C5A7A), 1);
			fx.ribbon(List.of(a, b), 0.16, Fx.argb((int) (255 * prog), placed >= plan.count() ? GREEN : CYAN), placed / (double) plan.count());
		}
		// the flag going up
		if (t >= FLAG) {
			double u = Anim.ramp(t, FLAG, FLAG + 2.2);
			Vec3 at = Vec3.atBottomCenterOf(pole).add(0.62, 1.4 + 3.6 * u, 0);
			fx.item(at, new ItemStack(Items.BANNER.pick(DyeColor.RED)), 0.62, 0, true);
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
		double[] times = {HOST, JOIN0, JOIN0 + JOIN_GAP, JOIN0 + 2 * JOIN_GAP, ORDER + 0.2};
		hud.chat(FilmHud.upTo(t, times, lines), 1 - Anim.ramp(t, BUILD0 + 1.5, BUILD0 + 2.5));
		typing(hud, t, HOST - 1.3, HOST, "!ac team host");
		typing(hud, t, ORDER - 1.3, ORDER, "!ac build " + plan.name + " here");
	}

	private static void typing(FilmHud hud, double t, double t0, double t1, String cmd) {
		if (t < t0 || t >= t1) return;
		int n = Math.min(cmd.length(), (int) ((t - t0) / (t1 - t0 - 0.25) * cmd.length()));
		hud.chatInput(cmd.substring(0, Math.max(0, n)), true, 1);
	}
}
