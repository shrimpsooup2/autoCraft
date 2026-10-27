package com.autocraft.cinema;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.world.phys.Vec3;

/**
 * A camera move: keys of (time, position, point looked at, field of view). Positions and look points follow
 * Catmull-Rom splines through the keys, so a move never stops at a key; it eases in at the first key and out at
 * the last. A key's position or look point may move with something (an actor): it's read when the frame is drawn,
 * so a key can ride alongside a walking bot. Watches turn the view toward a subject for a while, whatever the keys
 * say, so the players stay in the picture. A cut splits the move: the keys on either side make separate moves.
 */
final class Cam {
	record Key(double t, Supplier<Vec3> pos, Supplier<Vec3> look, double fov) {}

	record Pose(Vec3 pos, Vec3 look, float yaw, float pitch, float fov) {}

	private record Watch(Supplier<Vec3> subject, double t0, double t1, double weight) {}

	private final List<Key> keys = new ArrayList<>();
	private final List<Watch> watches = new ArrayList<>();
	private final List<Double> cuts = new ArrayList<>();

	Cam key(double t, Vec3 pos, Vec3 look) {
		return key(t, () -> pos, () -> look);
	}

	Cam key(double t, Vec3 pos, Vec3 look, double fov) {
		return key(t, () -> pos, () -> look, fov);
	}

	Cam key(double t, Supplier<Vec3> pos, Supplier<Vec3> look) {
		return key(t, pos, look, keys.isEmpty() ? 70 : keys.getLast().fov);
	}

	Cam key(double t, Supplier<Vec3> pos, Supplier<Vec3> look, double fov) {
		keys.add(new Key(t, pos, look, fov));
		keys.sort((a, b) -> Double.compare(a.t, b.t));
		return this;
	}

	/** Between t0 and t1 (eased in and out over 0.6 s) look at the subject, by `weight` (1 = straight at it). */
	Cam watch(Supplier<Vec3> subject, double t0, double t1, double weight) {
		watches.add(new Watch(subject, t0, t1, weight));
		return this;
	}

	/** A hard cut at t: keys before it and keys from it on are two separate moves. */
	Cam cut(double t) {
		cuts.add(t);
		return this;
	}

	boolean isEmpty() {
		return keys.isEmpty();
	}

	private int segment(double t) {
		int s = 0;
		for (double c : cuts) {
			if (t >= c) s++;
		}
		return s;
	}

	Pose at(double t) {
		List<Key> ks = keys;
		if (!cuts.isEmpty()) {
			int s = segment(t);
			List<Key> mine = new ArrayList<>();
			for (Key k : keys) {
				if (segment(k.t) == s) mine.add(k);
			}
			if (!mine.isEmpty()) ks = mine;
		}
		Key first = ks.getFirst(), last = ks.getLast();
		Vec3 pos, look;
		double fov;
		if (ks.size() == 1 || t <= first.t) {
			pos = first.pos.get();
			look = first.look.get();
			fov = first.fov;
		} else if (t >= last.t) {
			pos = last.pos.get();
			look = last.look.get();
			fov = last.fov;
		} else {
			int i = 0;
			while (i < ks.size() - 2 && t >= ks.get(i + 1).t) i++;
			Key k1 = ks.get(i), k2 = ks.get(i + 1);
			Key k0 = ks.get(Math.max(0, i - 1)), k3 = ks.get(Math.min(ks.size() - 1, i + 2));
			double u = (t - k1.t) / (k2.t - k1.t);
			if (ks.size() == 2) {
				u = Anim.smooth(u);
			} else if (i == 0) {
				u = easeIn(u);
			} else if (i == ks.size() - 2) {
				u = 1 - easeIn(1 - u);
			}
			Vec3 p1 = k1.pos.get(), p2 = k2.pos.get(), l1 = k1.look.get(), l2 = k2.look.get();
			Vec3 p0 = k0 == k1 ? p1 : k0.pos.get(), p3 = k3 == k2 ? p2 : k3.pos.get();
			Vec3 l0 = k0 == k1 ? l1 : k0.look.get(), l3 = k3 == k2 ? l2 : k3.look.get();
			pos = spline(p0, p1, p2, p3, u);
			look = spline(l0, l1, l2, l3, u);
			fov = k1.fov + (k2.fov - k1.fov) * u;
		}
		for (Watch w : watches) {
			double e = Anim.window(t, w.t0, w.t0 + 0.6, w.t1 - 0.6, w.t1) * w.weight;
			if (e > 0) look = Anim.lerp(look, w.subject.get(), e);
		}
		return pose(pos, look, fov);
	}

	/** Starts at rest and leaves at the speed the next segment continues with. */
	private static double easeIn(double u) {
		return u * u * (2 - u);
	}

	static Vec3 spline(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, double u) {
		double u2 = u * u, u3 = u2 * u;
		return new Vec3(cr(p0.x, p1.x, p2.x, p3.x, u, u2, u3), cr(p0.y, p1.y, p2.y, p3.y, u, u2, u3), cr(p0.z, p1.z, p2.z, p3.z, u, u2, u3));
	}

	private static double cr(double a, double b, double c, double d, double u, double u2, double u3) {
		return 0.5 * (2 * b + (-a + c) * u + (2 * a - 5 * b + 4 * c - d) * u2 + (-a + 3 * b - 3 * c + d) * u3);
	}

	static Pose pose(Vec3 pos, Vec3 look, double fov) {
		Vec3 d = look.subtract(pos);
		double h = Math.sqrt(d.x * d.x + d.z * d.z);
		float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		float pitch = (float) Math.toDegrees(Math.atan2(-d.y, h));
		return new Pose(pos, look, yaw, pitch, (float) fov);
	}
}
