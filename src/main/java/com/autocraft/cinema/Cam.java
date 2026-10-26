package com.autocraft.cinema;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/**
 * A camera move: keys of (time, position, point looked at, field of view). Positions and look points follow
 * Catmull-Rom splines through the keys, so a move never stops at a key; it eases in at the first key and out at
 * the last.
 */
final class Cam {
	record Key(double t, Vec3 pos, Vec3 look, double fov) {}

	record Pose(Vec3 pos, float yaw, float pitch, float fov) {}

	private final List<Key> keys = new ArrayList<>();

	Cam key(double t, Vec3 pos, Vec3 look) {
		return key(t, pos, look, keys.isEmpty() ? 70 : keys.getLast().fov);
	}

	Cam key(double t, Vec3 pos, Vec3 look, double fov) {
		keys.add(new Key(t, pos, look, fov));
		return this;
	}

	boolean isEmpty() {
		return keys.isEmpty();
	}

	Pose at(double t) {
		Key first = keys.getFirst(), last = keys.getLast();
		if (keys.size() == 1 || t <= first.t) return pose(first.pos, first.look, first.fov);
		if (t >= last.t) return pose(last.pos, last.look, last.fov);
		int i = 0;
		while (i < keys.size() - 2 && t >= keys.get(i + 1).t) i++;
		Key k1 = keys.get(i), k2 = keys.get(i + 1);
		Key k0 = keys.get(Math.max(0, i - 1)), k3 = keys.get(Math.min(keys.size() - 1, i + 2));
		double u = (t - k1.t) / (k2.t - k1.t);
		if (keys.size() == 2) {
			u = Anim.smooth(u);
		} else if (i == 0) {
			u = easeIn(u);
		} else if (i == keys.size() - 2) {
			u = 1 - easeIn(1 - u);
		}
		Vec3 pos = spline(k0.pos, k1.pos, k2.pos, k3.pos, u);
		Vec3 look = spline(k0.look, k1.look, k2.look, k3.look, u);
		return pose(pos, look, k1.fov + (k2.fov - k1.fov) * u);
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
		return new Pose(pos, yaw, pitch, (float) fov);
	}
}
