package com.autocraft.control;

import java.util.Random;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/**
 * Human-like view turning. A new look target starts a cubic Bezier curve in (yaw, pitch) space from the current
 * view: the control points bend the path a little to one side (hands arc, they don't move in straight lines) and
 * the progress along it follows a minimum-jerk profile (slow start, fast middle, gentle stop). Bigger turns take
 * longer (Fitts-like), with some jitter. Small target changes during a turn (a walking mob, the next path node)
 * move the curve's end instead of restarting it. Advanced every frame and every tick by wall-clock time.
 */
final class Aim {
	private static final double MIN_MS = 60;
	private static final double MS_PER_DEGREE = 1.3;
	private static final double MAX_MS = 260;
	/** Target moves smaller than this re-aim the running curve instead of starting a new one. */
	private static final float RETARGET_DEGREES = 4;
	private static final float SETTLED_DEGREES = 0.5F;

	private final Random random = new Random();
	private boolean active;
	/** Start, two control points and end; yaw unwrapped (continuous with the player's current yaw). */
	private float sy, sp, ay, ap, by, bp, ey, ep;
	private long startNanos;
	private double durationMs;

	boolean settled() {
		return !active;
	}

	/** Where the view is heading (the current view when not turning). */
	float targetYaw(LocalPlayer p) {
		return active ? ey : p.getYRot();
	}

	void target(LocalPlayer p, float yaw, float pitch) {
		pitch = Mth.clamp(pitch, -90.0F, 90.0F);
		float curY = p.getYRot(), curP = p.getXRot();
		if (active) {
			float endY = ey + Mth.wrapDegrees(yaw - ey);
			if (Math.abs(endY - ey) < RETARGET_DEGREES && Math.abs(pitch - ep) < RETARGET_DEGREES) {
				by += endY - ey; // drag the second control point along so the arrival stays smooth
				bp += pitch - ep;
				ey = endY;
				ep = pitch;
				return;
			}
		}
		float endY = curY + Mth.wrapDegrees(yaw - curY);
		float dy = endY - curY, dp = pitch - curP;
		double dist = Math.sqrt(dy * dy + dp * dp);
		if (dist < SETTLED_DEGREES) {
			active = false;
			set(p, endY, pitch);
			return;
		}
		// perpendicular unit vector for the sideways bend
		float nx = (float) (-dp / dist), nz = (float) (dy / dist);
		float bendA = (float) (random.nextGaussian() * 0.10 * dist);
		float bendB = (float) (random.nextGaussian() * 0.05 * dist);
		sy = curY;
		sp = curP;
		ay = curY + dy * 0.30F + nx * bendA;
		ap = curP + dp * 0.30F + nz * bendA;
		by = curY + dy * 0.85F + nx * bendB;
		bp = curP + dp * 0.85F + nz * bendB;
		ey = endY;
		ep = pitch;
		durationMs = Mth.clamp(MIN_MS + MS_PER_DEGREE * dist, MIN_MS, MAX_MS) * (0.85 + 0.3 * random.nextDouble());
		startNanos = System.nanoTime();
		active = true;
	}

	/** Move the view along the curve to where it should be now. */
	void update(LocalPlayer p) {
		if (!active) return;
		double t = Math.min(1, (System.nanoTime() - startNanos) / 1e6 / durationMs);
		double s = t * t * t * (t * (t * 6 - 15) + 10); // minimum jerk
		double u = 1 - s;
		double w0 = u * u * u, w1 = 3 * u * u * s, w2 = 3 * u * s * s, w3 = s * s * s;
		set(p, (float) (w0 * sy + w1 * ay + w2 * by + w3 * ey), (float) (w0 * sp + w1 * ap + w2 * bp + w3 * ep));
		if (t >= 1) active = false;
	}

	/** Jump to the target now (emergencies, actions that use the view itself). */
	void finish(LocalPlayer p) {
		if (!active) return;
		active = false;
		set(p, ey, ep);
	}

	void snap(LocalPlayer p, float yaw, float pitch) {
		active = false;
		set(p, p.getYRot() + Mth.wrapDegrees(yaw - p.getYRot()), Mth.clamp(pitch, -90.0F, 90.0F));
	}

	void cancel() {
		active = false;
	}

	private static void set(LocalPlayer p, float yaw, float pitch) {
		p.setYRot(yaw); // unwrapped on purpose: wrapping mid-turn makes the camera spin for a frame
		p.setXRot(Mth.clamp(pitch, -90.0F, 90.0F));
		p.setYHeadRot(yaw);
	}
}
