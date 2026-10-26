package com.autocraft.cinema;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/** Easing and paths for scripted motion. Times are seconds into the shot. */
final class Anim {
	private Anim() {}

	static double clamp01(double v) {
		return v < 0 ? 0 : v > 1 ? 1 : v;
	}

	/** Smoothstep of an already normalised value. */
	static double smooth(double u) {
		u = clamp01(u);
		return u * u * (3 - 2 * u);
	}

	/** 0 before t0, 1 after t1, eased between. */
	static double ramp(double t, double t0, double t1) {
		return smooth((t - t0) / (t1 - t0));
	}

	/** Rises over [a, b], holds, falls over [c, d]. */
	static double window(double t, double a, double b, double c, double d) {
		return Math.min(ramp(t, a, b), 1 - ramp(t, c, d));
	}

	/** A quick pop from 0 past 1 and back (scale-in with overshoot) starting at t0. */
	static double pop(double t, double t0, double dur) {
		double u = clamp01((t - t0) / dur);
		if (u <= 0) return 0;
		return 1 + Math.sin(u * Math.PI) * 0.25 * (1 - u) - (1 - u) * (1 - u) * (1 - u);
	}

	static Vec3 lerp(Vec3 a, Vec3 b, double u) {
		return a.add(b.subtract(a).scale(u));
	}

	/** A point on an arc from a to b rising `height` in the middle. */
	static Vec3 arc(Vec3 a, Vec3 b, double u, double height) {
		return lerp(a, b, u).add(0, Math.sin(Math.PI * clamp01(u)) * height, 0);
	}

	/** Minecraft yaw (degrees) looking along a horizontal direction. */
	static float yaw(Vec3 dir) {
		return (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
	}

	static float yawTo(Vec3 from, Vec3 to) {
		return yaw(to.subtract(from));
	}

	static float pitchTo(Vec3 from, Vec3 to) {
		Vec3 d = to.subtract(from);
		return (float) Math.toDegrees(Math.atan2(-d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
	}

	/** A path: points at times, straight between them, still before the first and after the last. */
	static final class Track {
		private final List<Double> times = new ArrayList<>();
		private final List<Vec3> points = new ArrayList<>();

		Track at(double t, Vec3 p) {
			times.add(t);
			points.add(p);
			return this;
		}

		/** Continue to p at `speed` blocks per second from the last point. */
		Track to(Vec3 p, double speed) {
			double t = times.getLast() + points.getLast().distanceTo(p) / speed;
			return at(t, p);
		}

		/** Stay at the last point until t. */
		Track hold(double t) {
			return at(t, points.getLast());
		}

		double end() {
			return times.getLast();
		}

		Vec3 last() {
			return points.getLast();
		}

		Vec3 pos(double t) {
			if (t <= times.getFirst()) return points.getFirst();
			for (int i = 0; i < times.size() - 1; i++) {
				if (t <= times.get(i + 1)) {
					double span = times.get(i + 1) - times.get(i);
					return lerp(points.get(i), points.get(i + 1), span <= 0 ? 1 : (t - times.get(i)) / span);
				}
			}
			return points.getLast();
		}

		/** Direction of travel at t (zero when still). */
		Vec3 heading(double t) {
			for (int i = 0; i < times.size() - 1; i++) {
				if (t >= times.get(i) && t < times.get(i + 1)) {
					Vec3 d = points.get(i + 1).subtract(points.get(i));
					return new Vec3(d.x, 0, d.z);
				}
			}
			return Vec3.ZERO;
		}
	}
}
