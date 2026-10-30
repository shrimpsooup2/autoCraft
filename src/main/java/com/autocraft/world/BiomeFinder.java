package com.autocraft.world;

import java.util.HashSet;
import java.util.Set;
import java.util.function.LongPredicate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

/** Reads biomes of loaded chunks to point exploration at the right terrain. */
public final class BiomeFinder {
	private BiomeFinder() {}

	private static final long AROUND_MS = 10_000;
	private static @Nullable Set<ResourceKey<Biome>> around;
	private static @Nullable ClientLevel aroundLevel;
	private static long aroundChunk;
	private static long aroundAt;

	/**
	 * Biomes of the loaded chunks around {@code from}: one sample per chunk at the surface and one at {@code from}'s
	 * height (caves have biomes of their own). Kept while {@code from} stays in its chunk, for ten seconds at most
	 * (chunks load in around a new arrival).
	 */
	public static Set<ResourceKey<Biome>> around(ClientLevel level, BlockPos from, int chunkRadius) {
		int cx = from.getX() >> 4, cz = from.getZ() >> 4;
		long chunk = ChunkPos.pack(cx, cz), now = System.currentTimeMillis();
		if (around != null && level == aroundLevel && chunk == aroundChunk && now - aroundAt < AROUND_MS) return around;
		Set<ResourceKey<Biome>> found = new HashSet<>();
		for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
			for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
				if (!level.hasChunk(cx + dx, cz + dz)) continue;
				int x = ((cx + dx) << 4) + 8, z = ((cz + dz) << 4) + 8;
				level.getBiome(new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z)).unwrapKey().ifPresent(found::add);
				level.getBiome(new BlockPos(x, from.getY(), z)).unwrapKey().ifPresent(found::add);
			}
		}
		around = found;
		aroundLevel = level;
		aroundChunk = chunk;
		aroundAt = now;
		return found;
	}

	public static boolean in(ClientLevel level, BlockPos pos, Set<ResourceKey<Biome>> biomes) {
		Holder<Biome> here = level.getBiome(pos);
		for (ResourceKey<Biome> key : biomes) {
			if (here.is(key)) return true;
		}
		return false;
	}

	/**
	 * Centre of the nearest loaded chunk (rings outward) whose biome is one of {@code biomes}, sampled at
	 * the surface (or at {@code from}'s height where there is a roof, e.g. the Nether); chunks for which
	 * {@code skip} returns true (by {@link ChunkPos#pack}) are ignored.
	 */
	public static @Nullable BlockPos nearest(ClientLevel level, BlockPos from, Set<ResourceKey<Biome>> biomes, int chunkRadius,
			boolean surface, LongPredicate skip) {
		int cx = from.getX() >> 4, cz = from.getZ() >> 4;
		for (int r = 0; r <= chunkRadius; r++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r || !level.hasChunk(cx + dx, cz + dz)) continue;
					if (skip.test(ChunkPos.pack(cx + dx, cz + dz))) continue;
					int x = ((cx + dx) << 4) + 8, z = ((cz + dz) << 4) + 8;
					int y = surface ? level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) : from.getY();
					BlockPos sample = new BlockPos(x, y, z);
					if (in(level, sample, biomes)) return sample;
				}
			}
		}
		return null;
	}
}
