package com.autocraft.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

	public WorldScanner(Minecraft mc) {
		this.mc = mc;
	}

	public void tick() {
		tick++;
	}

	public void clear() {
		cache.clear();
	}

	/** Remove a position from every cached result (it was mined or is unreachable). */
	public void forget(BlockPos pos) {
		for (Cached c : cache.values()) {
			c.result.remove(pos);
		}
	}

	/** {@link #findBlocks} but reuses a result younger than {@code maxAgeTicks} for the same key. */
	public List<BlockPos> findBlocksCached(Object key, Predicate<BlockState> match, int chunkRadius, int maxResults, int maxAgeTicks) {
		Cached c = cache.get(key);
		if (c == null || tick - c.tick > maxAgeTicks) {
			c = new Cached(tick, findBlocks(match, chunkRadius, maxResults));
			cache.put(key, c);
		}
		return c.result;
	}

	/** Matching block positions in loaded chunks within {@code chunkRadius}, nearest first. */
	public List<BlockPos> findBlocks(Predicate<BlockState> match, int chunkRadius, int maxResults) {
		ClientLevel level = mc.level;
		BlockPos origin = mc.player.blockPosition();
		int cx = origin.getX() >> 4, cz = origin.getZ() >> 4;
		int minY = level.getMinY();
		List<BlockPos> found = new ArrayList<>();
		for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
			for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
				LevelChunk chunk = level.getChunkSource().getChunk(cx + dx, cz + dz, ChunkStatus.FULL, false);
				if (chunk == null) continue;
				LevelChunkSection[] sections = chunk.getSections();
				int baseX = (cx + dx) << 4, baseZ = (cz + dz) << 4;
				for (int i = 0; i < sections.length; i++) {
					LevelChunkSection section = sections[i];
					if (section == null || section.hasOnlyAir() || !section.maybeHas(match)) continue;
					int baseY = minY + (i << 4);
					for (int y = 0; y < 16; y++) {
						for (int z = 0; z < 16; z++) {
							for (int x = 0; x < 16; x++) {
								if (match.test(section.getBlockState(x, y, z))) {
									found.add(new BlockPos(baseX + x, baseY + y, baseZ + z));
								}
							}
						}
					}
				}
			}
		}
		found.sort(Comparator.comparingDouble(p -> p.distSqr(origin)));
		return found.size() > maxResults ? new ArrayList<>(found.subList(0, maxResults)) : found;
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
