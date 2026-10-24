package com.autocraft.craft;

import com.autocraft.Bot;
import com.autocraft.behavior.Harvest;
import com.autocraft.control.InvUtil;
import com.autocraft.goal.Requirement.Dim;
import com.autocraft.path.Navigator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Crafting tables and furnaces: which ones count as "nearby" (one rule for planners and for the code that walks
 * to them), which ones the bot placed itself, and picking those back up before it moves on.
 */
public final class Stations {
	/** A station within this distance is used instead of placing one. */
	public static final int USE_RADIUS = 16;
	/** Placed stations within this distance are picked up when work here is over. */
	public static final int PICKUP_RADIUS = 32;
	/** Within this distance of the base its tables and furnaces are used (walking there beats crafting new ones). */
	public static final int BASE_USE_DISTANCE = 64;
	private static final int BASE_STATION_RADIUS = 8;
	private static final long BASE_SCAN_MS = 30_000;

	private static final Map<BlockPos, Dim> placed = new LinkedHashMap<>();
	/** Furnaces found busy with someone else's items; never reused. */
	private static final Set<BlockPos> rejected = new HashSet<>();
	private static final Map<Block, List<BlockPos>> baseStations = new HashMap<>();
	private static @Nullable BlockPos baseScannedFor;
	private static long baseScannedAt;

	private Stations() {}

	/**
	 * Station to use: the nearest within {@link #USE_RADIUS}; otherwise, when near the base and not carrying one to
	 * put down, a base station. Null means place our own (or the planner makes one).
	 */
	public static @Nullable BlockPos nearby(Bot bot, Block block) {
		BlockPos feet = Navigator.feet(bot.player());
		BlockPos best = null;
		double bestDist = USE_RADIUS * USE_RADIUS;
		for (BlockPos b : bot.scanner().findBlocks(s -> s.is(block), 1, 16)) {
			if (rejected.contains(b)) continue;
			double d = b.distSqr(feet);
			if (d <= bestDist) {
				bestDist = d;
				best = b;
			}
		}
		if (best != null || InvUtil.count(bot.player(), s -> s.is(block.asItem())) > 0) return best;
		List<BlockPos> atBase = atBase(bot, block);
		return atBase.isEmpty() ? null : atBase.getFirst();
	}

	/** Free stations of {@code block} at the base when the bot is within {@link #BASE_USE_DISTANCE} of it. */
	public static List<BlockPos> atBase(Bot bot, Block block) {
		BlockPos base = bot.base();
		if (base == null || bot.baseDimension() != bot.dimension() || base.distSqr(bot.player().blockPosition()) > BASE_USE_DISTANCE * BASE_USE_DISTANCE
				|| !bot.level().isLoaded(base)) {
			return List.of();
		}
		long now = System.currentTimeMillis();
		if (!base.equals(baseScannedFor) || now - baseScannedAt > BASE_SCAN_MS) {
			baseStations.clear();
			baseScannedFor = base;
			baseScannedAt = now;
			for (BlockPos pos : BlockPos.betweenClosed(base.offset(-BASE_STATION_RADIUS, -4, -BASE_STATION_RADIUS),
					base.offset(BASE_STATION_RADIUS, 4, BASE_STATION_RADIUS))) {
				Block b = bot.level().getBlockState(pos).getBlock();
				if (b == Blocks.CRAFTING_TABLE || b == Blocks.FURNACE) baseStations.computeIfAbsent(b, k -> new ArrayList<>()).add(pos.immutable());
			}
		}
		List<BlockPos> out = new ArrayList<>(baseStations.getOrDefault(block, List.of()));
		out.removeIf(rejected::contains);
		return out;
	}

	/** Planner for side work (gear, builds): job loot is never used up as an ingredient. */
	public static Planner planner(Bot bot) {
		return planner(bot, bot::isJobTarget);
	}

	/** Planner with station availability decided by the same rule the crafter/smelter use. */
	public static Planner planner(Bot bot, Predicate<ItemStack> protect) {
		return new Planner(bot.player(), bot.dimension(), nearby(bot, Blocks.CRAFTING_TABLE) != null, nearby(bot, Blocks.FURNACE) != null, protect);
	}

	public static void notePlaced(Bot bot, BlockPos pos) {
		placed.put(pos.immutable(), bot.dimension());
	}

	public static void reject(BlockPos pos) {
		rejected.add(pos.immutable());
	}

	/** Our nearest placed station within {@link #PICKUP_RADIUS} that's still there; forgets vanished ones. */
	static @Nullable BlockPos nearestPlaced(Bot bot) {
		BlockPos feet = Navigator.feet(bot.player());
		BlockPos best = null;
		double bestDist = PICKUP_RADIUS * PICKUP_RADIUS;
		for (Iterator<Map.Entry<BlockPos, Dim>> it = placed.entrySet().iterator(); it.hasNext();) {
			Map.Entry<BlockPos, Dim> e = it.next();
			if (e.getValue() != bot.dimension()) continue;
			BlockPos pos = e.getKey();
			double d = pos.distSqr(feet);
			if (d > bestDist) continue;
			if (!bot.level().isLoaded(pos)) continue;
			BlockState s = bot.level().getBlockState(pos);
			if (!s.is(Blocks.CRAFTING_TABLE) && !s.is(Blocks.FURNACE)) {
				it.remove(); // already picked up or broken
				continue;
			}
			bestDist = d;
			best = pos;
		}
		return best;
	}

	static void forget(BlockPos pos) {
		placed.remove(pos);
	}

	/** Picks up the bot's own stations around it, one at a time. */
	public static final class Cleanup {
		private @Nullable Harvest pickup;
		private @Nullable BlockPos target;
		private int failures;

		/** True while picking something up (call every tick until false). */
		public boolean tick(Bot bot) {
			if (pickup != null) {
				bot.setStatus("picking up our " + bot.level().getBlockState(target).getBlock().getName().getString().toLowerCase());
				Harvest.Status s = pickup.tick(bot);
				if (s == Harvest.Status.RUNNING) return true;
				if (s == Harvest.Status.FAILED && ++failures < 3) {
					pickup = null;
					return true;
				}
				forget(target);
				pickup = null;
				target = null;
				failures = 0;
			}
			target = nearestPlaced(bot);
			if (target == null) return false;
			pickup = new Harvest(target, s -> s.is(Items.CRAFTING_TABLE) || s.is(Items.FURNACE));
			return true;
		}

		public void reset() {
			pickup = null;
			target = null;
			failures = 0;
		}
	}
}
