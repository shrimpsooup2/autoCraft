package com.autocraft.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The bot's eyes. Block scans walk loaded chunk sections and skip any section whose palette cannot
 * contain a match, so even wide scans for rare ores are cheap. Results are cached per key.
 */
public final class WorldScanner {
	private final Minecraft mc;
	private final Map<Object, Cached> cache = new HashMap<>();
	private long tick;

	private record Cached(long tick, List<BlockPos> result) {}

	/** A test on a matching block's position, without a BlockPos made for every block looked at. */
	@FunctionalInterface
	public interface Where {
		Where ANYWHERE = (x, y, z) -> true;

		boolean test(int x, int y, int z);
	}

	public WorldScanner(Minecraft mc) {
		this.mc = mc;
	}

	public void tick() {
		tick++;
	}

	public void clear() {
		cache.clear();
	}

	/** Drop one cached result: what it was filtered by just changed. */
	public void invalidate(Object key) {
		cache.remove(key);
	}

	/** Remove a position from every cached result (it was mined or is unreachable). */
	public void forget(BlockPos pos) {
		for (Cached c : cache.values()) {
			c.result.remove(pos);
		}
	}

	/** {@link #findBlocks} but reuses a result younger than {@code maxAgeTicks} for the same key. */
	public List<BlockPos> findBlocksCached(Object key, Predicate<BlockState> match, int chunkRadius, int maxResults, int maxAgeTicks) {
		return findBlocksCached(key, match, Where.ANYWHERE, chunkRadius, maxResults, maxAgeTicks);
	}

	/** {@link #findBlocks(Predicate, Where, int, int)} but reuses a result younger than {@code maxAgeTicks} for the same key. */
	public List<BlockPos> findBlocksCached(Object key, Predicate<BlockState> match, Where where, int chunkRadius, int maxResults, int maxAgeTicks) {
		Cached c = cache.get(key);
		if (c == null || tick - c.tick > maxAgeTicks) {
			c = new Cached(tick, findBlocks(match, where, chunkRadius, maxResults));
			cache.put(key, c);
		}
		return c.result;
	}

	public List<BlockPos> findBlocks(Predicate<BlockState> match, int chunkRadius, int maxResults) {
		return findBlocks(match, Where.ANYWHERE, chunkRadius, maxResults);
	}

	/**
	 * The {@code maxResults} matching positions nearest the player (straight-line) in loaded chunks within
	 * {@code chunkRadius}, nearest first. Chunks are visited in rings outward; once {@code maxResults} are known,
	 * chunks and sections that can't hold anything nearer are skipped and the scan ends at the first ring wholly
	 * beyond the farthest kept result. Common blocks (stone, logs in a forest) still cost only a few chunks, and a
	 * block just across a chunk border beats a farther one in the bot's own chunk (stopping after the first ring
	 * that had enough matches used to send the bot to a tree 20 blocks off with one right next to it).
	 */
	public List<BlockPos> findBlocks(Predicate<BlockState> match, Where where, int chunkRadius, int maxResults) {
		BlockPos origin = mc.player.blockPosition();
		Nearest nearest = new Nearest(maxResults);
		int cx = origin.getX() >> 4, cz = origin.getZ() >> 4;
		for (int r = 0; r <= chunkRadius; r++) {
			boolean ringInRange = false;
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
					long horizontal = horizontalDistSqr(origin, cx + dx, cz + dz);
					if (horizontal >= nearest.bound()) continue;
					ringInRange = true;
					scanChunk(cx + dx, cz + dz, origin, horizontal, match, where, nearest);
				}
			}
			if (!ringInRange) break; // every chunk farther out is farther still
		}
		return nearest.sorted();
	}

	/** Squared horizontal distance from {@code o} to the nearest column of chunk (chunkX, chunkZ). */
	private static long horizontalDistSqr(BlockPos o, int chunkX, int chunkZ) {
		long dx = axisGap(o.getX(), chunkX << 4), dz = axisGap(o.getZ(), chunkZ << 4);
		return dx * dx + dz * dz;
	}

	/** Distance along one axis from {@code v} to the 16-block span starting at {@code min} (0 inside it). */
	private static long axisGap(int v, int min) {
		return v < min ? min - v : v > min + 15 ? v - (min + 15) : 0;
	}

	private void scanChunk(int chunkX, int chunkZ, BlockPos o, long horizontal, Predicate<BlockState> match, Where where, Nearest out) {
		ClientLevel level = mc.level;
		LevelChunk chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
		if (chunk == null) return;
		LevelChunkSection[] sections = chunk.getSections();
		int baseX = chunkX << 4, baseZ = chunkZ << 4, minY = level.getMinY();
		int home = Math.clamp((o.getY() - minY) >> 4, 0, sections.length - 1);
		// sections nearest the player's height first (home, below, above, ...): the bound tightens early
		for (int k = 0; k < 2 * sections.length; k++) {
			int i = (k & 1) == 0 ? home + k / 2 : home - (k + 1) / 2;
			if (i < 0 || i >= sections.length) continue;
			LevelChunkSection section = sections[i];
			if (section == null || section.hasOnlyAir()) continue;
			int baseY = minY + (i << 4);
			long gapY = axisGap(o.getY(), baseY);
			if (horizontal + gapY * gapY >= out.bound() || !section.maybeHas(match)) continue;
			for (int y = 0; y < 16; y++) {
				long dy = baseY + y - o.getY();
				for (int z = 0; z < 16; z++) {
					long dz = baseZ + z - o.getZ();
					for (int x = 0; x < 16; x++) {
						long dx = baseX + x - o.getX();
						long d = dx * dx + dy * dy + dz * dz;
						if (d < out.bound() && match.test(section.getBlockState(x, y, z)) && where.test(baseX + x, baseY + y, baseZ + z)) {
							out.offer(baseX + x, baseY + y, baseZ + z, d);
						}
					}
				}
			}
		}
	}

	/** A chunk's key in {@link #chunksWith}'s result. */
	public static long chunkKey(int chunkX, int chunkZ) {
		return (long) chunkX & 0xFFFFFFFFL | ((long) chunkZ & 0xFFFFFFFFL) << 32;
	}

	/**
	 * Loaded chunks within {@code chunkRadius} holding at least {@code minCount} matching blocks ({@link #chunkKey}s):
	 * where a structure built of them lies, and how far it reaches.
	 */
	public Set<Long> chunksWith(Predicate<BlockState> match, int chunkRadius, int minCount) {
		BlockPos origin = mc.player.blockPosition();
		int cx = origin.getX() >> 4, cz = origin.getZ() >> 4;
		Set<Long> out = new HashSet<>();
		for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
			for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
				if (countUpTo(cx + dx, cz + dz, match, minCount) >= minCount) out.add(chunkKey(cx + dx, cz + dz));
			}
		}
		return out;
	}

	/** Matching blocks in a loaded chunk, counted up to {@code limit}. */
	private int countUpTo(int chunkX, int chunkZ, Predicate<BlockState> match, int limit) {
		LevelChunk chunk = mc.level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
		if (chunk == null) return 0;
		int n = 0;
		for (LevelChunkSection section : chunk.getSections()) {
			if (section == null || section.hasOnlyAir() || !section.maybeHas(match)) continue;
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						if (match.test(section.getBlockState(x, y, z)) && ++n >= limit) return n;
					}
				}
			}
		}
		return n;
	}

	/** The k nearest positions offered so far (a max-heap on squared distance). */
	private static final class Nearest {
		private record Hit(BlockPos pos, long distSqr) {}

		private final int k;
		private final PriorityQueue<Hit> heap = new PriorityQueue<>((a, b) -> Long.compare(b.distSqr, a.distSqr));

		Nearest(int k) {
			this.k = Math.max(1, k);
		}

		/** Anything at this squared distance or farther can't make the cut. */
		long bound() {
			return heap.size() < k ? Long.MAX_VALUE : heap.peek().distSqr;
		}

		void offer(int x, int y, int z, long distSqr) {
			heap.add(new Hit(new BlockPos(x, y, z), distSqr));
			if (heap.size() > k) heap.poll();
		}

		List<BlockPos> sorted() {
			List<Hit> hits = new ArrayList<>(heap);
			hits.sort(Comparator.comparingLong(Hit::distSqr));
			List<BlockPos> out = new ArrayList<>(hits.size());
			for (Hit h : hits) out.add(h.pos);
			return out;
		}
	}

	// ---- entities -------------------------------------------------------------------------------

	public List<Entity> entities(double radius, Predicate<Entity> filter) {
		Vec3 c = mc.player.position();
		AABB box = new AABB(c, c).inflate(radius);
		return mc.level.getEntities(mc.player, box, e -> e.isAlive() && filter.test(e));
	}

	/** Living hostile mobs within {@code radius}, nearest first. */
	public List<LivingEntity> hostiles(double radius) {
		List<LivingEntity> out = new ArrayList<>();
		for (Entity e : entities(radius, e -> e instanceof Enemy && e instanceof LivingEntity)) {
			out.add((LivingEntity) e);
		}
		out.sort(Comparator.comparingDouble(e -> e.distanceToSqr(mc.player)));
		return out;
	}

	/** Dropped items matching {@code filter} within {@code radius}, nearest first. */
	public List<ItemEntity> items(double radius, Predicate<ItemStack> filter) {
		List<ItemEntity> out = new ArrayList<>();
		for (Entity e : entities(radius, e -> e instanceof ItemEntity)) {
			ItemEntity item = (ItemEntity) e;
			if (filter.test(item.getItem())) out.add(item);
		}
		out.sort(Comparator.comparingDouble(e -> e.distanceToSqr(mc.player)));
		return out;
	}
}
