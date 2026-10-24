package com.autocraft.path;

import com.autocraft.control.InvUtil;
import com.autocraft.world.BlockUtil;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import java.util.function.Predicate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jspecify.annotations.Nullable;

/**
 * World view for one search: cached block states (unloaded chunks read as null = impassable) and
 * cost helpers. Inventory-dependent values (tools, throwaway blocks) are snapshotted at creation.
 */
final class PathContext {
	static final double INF = 1e9;
	/** Fixed overhead per mined block on top of its break time (aim, swing, pick-up drift). */
	private static final double BREAK_OVERHEAD = 3;
	private static final double FALLING_BLOCK_PENALTY = 15;
	private static final double WATER_FLOOD_PENALTY = 25;

	final ClientLevel level;
	final LocalPlayer player;
	final int throwaway;
	private final int minY, maxY;
	private final Long2ObjectOpenHashMap<LevelChunk> chunks = new Long2ObjectOpenHashMap<>();
	private final Long2ObjectOpenHashMap<BlockState> states = new Long2ObjectOpenHashMap<>();
	private final Reference2IntOpenHashMap<BlockState> breakTicks = new Reference2IntOpenHashMap<>();
	private final BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();

	PathContext(ClientLevel level, LocalPlayer player, Predicate<ItemStack> scaffold) {
		this.level = level;
		this.player = player;
		this.throwaway = InvUtil.count(player, scaffold);
		this.minY = level.getMinY();
		this.maxY = level.getMaxY();
	}

	@Nullable BlockState state(int x, int y, int z) {
		if (y < minY || y > maxY) return null;
		long key = BlockPos.asLong(x, y, z);
		BlockState s = states.get(key);
		if (s != null) return s;
		LevelChunk chunk = chunk(x >> 4, z >> 4);
		if (chunk == null) return null;
		s = chunk.getBlockState(mpos.set(x, y, z));
		states.put(key, s);
		return s;
	}

	private @Nullable LevelChunk chunk(int cx, int cz) {
		long key = ChunkPos.pack(cx, cz);
		if (chunks.containsKey(key)) return chunks.get(key);
		LevelChunk c = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
		chunks.put(key, c);
		return c;
	}

	boolean passable(int x, int y, int z) {
		BlockState s = state(x, y, z);
		return s != null && BlockUtil.isPassable(level, mpos.set(x, y, z), s);
	}

	/** A player can stand on top of block (x,y,z). */
	boolean standOn(int x, int y, int z) {
		BlockState s = state(x, y, z);
		return s != null && BlockUtil.canStandOn(level, mpos.set(x, y, z), s);
	}

	boolean water(int x, int y, int z) {
		BlockState s = state(x, y, z);
		return s != null && BlockUtil.isWater(s);
	}

	boolean lava(int x, int y, int z) {
		BlockState s = state(x, y, z);
		return s != null && BlockUtil.isLava(s);
	}

	/** Can hold a placed block (air, plants, water) — never lava. */
	boolean replaceable(int x, int y, int z) {
		BlockState s = state(x, y, z);
		return s != null && s.canBeReplaced() && !BlockUtil.isLava(s);
	}

	/** Feet position a player can occupy: body fits and there is ground (or water to float in). */
	boolean standable(int x, int y, int z) {
		return passable(x, y, z) && passable(x, y + 1, z) && (standOn(x, y - 1, z) || water(x, y, z));
	}

	/** Ticks to make (x,y,z) passable: 0 if already passable, INF if it must not or cannot be mined. */
	double clearCost(int x, int y, int z) {
		BlockState s = state(x, y, z);
		if (s == null) return INF;
		mpos.set(x, y, z);
		if (BlockUtil.isPassable(level, mpos, s)) return 0;
		if (!BlockUtil.isBreakable(level, mpos, s)) return INF;
		int ticks = breakTicks.getOrDefault(s, -1);
		if (ticks < 0) {
			ticks = BlockUtil.breakTicks(player, level, mpos, s);
			breakTicks.put(s, ticks);
		}
		double cost = ticks + BREAK_OVERHEAD;
		if (BlockUtil.hasFallingBlockAbove(level, mpos)) cost += FALLING_BLOCK_PENALTY;
		if (BlockUtil.touchesWater(level, mpos)) cost += WATER_FLOOD_PENALTY;
		return cost;
	}

	/**
	 * Walking-cost multiplier for the floor under feet (x,y,z): soul sand, honey, etc. are slow; leaves are avoided
	 * (tree canopies lead nowhere and end in long falls).
	 */
	double floorMultiplier(int x, int y, int z) {
		BlockState floor = state(x, y - 1, z);
		if (floor == null) return 1;
		if (floor.is(BlockTags.LEAVES)) return 3;
		float factor = floor.getBlock().getSpeedFactor();
		return factor >= 1 ? 1 : 1 / Math.max(factor, 0.2);
	}

	/** Lava directly beside or under the feet block — one misstep from death. */
	boolean nearLava(int x, int y, int z) {
		return lava(x + 1, y, z) || lava(x - 1, y, z) || lava(x, y, z + 1) || lava(x, y, z - 1)
				|| lava(x, y - 1, z) || lava(x + 1, y - 1, z) || lava(x - 1, y - 1, z) || lava(x, y - 1, z + 1) || lava(x, y - 1, z - 1);
	}
}
