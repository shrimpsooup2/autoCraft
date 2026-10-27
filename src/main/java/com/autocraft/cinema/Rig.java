package com.autocraft.cinema;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jspecify.annotations.Nullable;

/**
 * The camera operator between a shot's scripted move and the screen. It steadies the move a little (riding keys
 * follow a bot's every step otherwise), and keeps the camera out of blocks: when the scripted spot is inside a
 * block or something stands between it and what it looks at, it picks the smallest nearby move that sees clearly —
 * up, in toward the subject, or to the side — and eases there; when the way is clear again it eases back. Easing
 * means it may pass through a block for a moment rather than jump; if nothing nearby sees clearly it stays put.
 * Everything runs on film time, so a recording comes out the same at any frame rate.
 */
final class Rig {
	/** Each of the two smoothing stages for avoiding blocks (seconds); the steadying of the scripted move. */
	private static final double EASE = 0.22, STEADY = 0.1;
	/** A scripted jump this far in one frame is a cut: no easing across it. */
	private static final double CUT = 3;
	/** Blocks this close to what's looked at belong to the subject (a chest, the floor it stands on), not in the way. */
	private static final double SUBJECT = 1.1;
	/** How much room the camera wants around it. */
	private static final double ROOM = 0.25;

	record View(Vec3 pos, Vec3 look) {}

	private double lastT = -1;
	private @Nullable Vec3 scripted, steadyPos, steadyLook;
	private Vec3 goal = Vec3.ZERO, eased = Vec3.ZERO, offset = Vec3.ZERO;
	private @Nullable View cached;
	private double cachedT = Double.NaN;

	void reset() {
		lastT = -1;
		scripted = steadyPos = steadyLook = null;
		goal = eased = offset = Vec3.ZERO;
		cached = null;
		cachedT = Double.NaN;
	}

	/** Where the camera really goes, for the scripted position and look point at film time t. */
	View place(Level level, Vec3 pos, Vec3 look, double t) {
		if (t == cachedT && cached != null) return cached;   // asked twice a frame (view, then field of view)
		boolean cut = scripted == null || scripted.distanceTo(pos) > CUT || t < lastT;
		double dt = cut ? 0 : t - lastT;
		scripted = pos;
		lastT = t;
		if (cut) {
			steadyPos = pos;
			steadyLook = look;
		} else {
			double k = 1 - Math.exp(-dt / STEADY);
			steadyPos = Anim.lerp(steadyPos, pos, k);
			steadyLook = Anim.lerp(steadyLook, look, k);
		}
		goal = choose(level, steadyPos, steadyLook);
		if (cut) {
			eased = offset = goal;
		} else {
			double k = 1 - Math.exp(-dt / EASE);
			eased = Anim.lerp(eased, goal, k);
			offset = Anim.lerp(offset, eased, k);
		}
		Vec3 at = steadyPos.add(offset);
		View v = finite(at) && finite(steadyLook) ? new View(at, steadyLook) : new View(pos, look);
		cachedT = t;
		cached = v;
		return v;
	}

	/** The offset to aim for: none if the scripted spot sees clearly, else the smallest move that does, else the last one. */
	private Vec3 choose(Level level, Vec3 pos, Vec3 look) {
		if (clear(level, pos, look)) return Vec3.ZERO;
		if (goal.lengthSqr() > 0 && clear(level, pos.add(goal), look)) return goal;   // stay with what worked
		for (Vec3 o : candidates(pos, look)) {
			if (clear(level, pos.add(o), look)) return o;
		}
		return goal;
	}

	/** Moves to try, smallest first: up, in toward the subject, to either side, and mixes of those. */
	private static List<Vec3> candidates(Vec3 pos, Vec3 look) {
		Vec3 to = look.subtract(pos);
		double dist = to.length();
		Vec3 f = dist > 1e-6 ? to.scale(1 / dist) : new Vec3(0, 0, 1);
		Vec3 side = new Vec3(-f.z, 0, f.x);
		side = side.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : side.normalize();
		Vec3 up = new Vec3(0, 1, 0);
		List<Vec3> out = new ArrayList<>();
		for (double d : new double[] {0.6, 1.2, 2, 3, 4.5}) {
			out.add(up.scale(d));
			out.add(side.scale(d));
			out.add(side.scale(-d));
			if (dist - d > 2) out.add(f.scale(d));
			if (dist - d > 2.5) out.add(f.scale(d).add(up.scale(d * 0.6)));
			out.add(side.scale(d * 0.8).add(up.scale(d * 0.6)));
			out.add(side.scale(-d * 0.8).add(up.scale(d * 0.6)));
		}
		if (dist > 2.5) out.add(f.scale(dist - 2));   // last resort: right up close
		out.sort((a, b) -> Double.compare(a.lengthSqr(), b.lengthSqr()));
		return out;
	}

	/** Room around the camera, and nothing solid between it and (nearly) what it looks at. */
	private static boolean clear(Level level, Vec3 cam, Vec3 look) {
		if (!level.noCollision(new AABB(cam, cam).inflate(ROOM))) return false;
		Vec3 to = look.subtract(cam);
		double dist = to.length();
		if (dist <= SUBJECT) return true;
		Vec3 end = cam.add(to.scale((dist - SUBJECT) / dist));
		return level.clip(new ClipContext(cam, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty())).getType()
				== HitResult.Type.MISS;
	}

	private static boolean finite(Vec3 v) {
		return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
	}
}
