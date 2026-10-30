package com.autocraft.path;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.core.BlockPos;

/**
 * Moves the executor tried and couldn't make (wedged on a corner against a water current, a pillar that never rose
 * in water...): kept out of plans for a while. Without this the search, seeing the same world, chose the same
 * first step every time and the bot replanned the identical path for half an hour.
 */
final class AvoidedMoves {
	private static final long TTL_NANOS = 120_000_000_000L;

	private final Long2LongOpenHashMap until = new Long2LongOpenHashMap();

	void add(BlockPos from, BlockPos to) {
		until.put(key(from.getX(), from.getY(), from.getZ(), to.getX(), to.getY(), to.getZ()), System.nanoTime() + TTL_NANOS);
	}

	boolean contains(int fx, int fy, int fz, int tx, int ty, int tz) {
		if (until.isEmpty()) return false;
		long k = key(fx, fy, fz, tx, ty, tz);
		long t = until.get(k);
		if (t == 0) return false;
		if (t < System.nanoTime()) {
			until.remove(k);
			return false;
		}
		return true;
	}

	private static long key(int fx, int fy, int fz, int tx, int ty, int tz) {
		return BlockPos.asLong(fx, fy, fz) * 0x9E3779B97F4A7C15L ^ BlockPos.asLong(tx, ty, tz);
	}
}
