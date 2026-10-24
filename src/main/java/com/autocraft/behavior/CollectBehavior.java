package com.autocraft.behavior;

import com.autocraft.Bot;
import com.autocraft.Chat;
import com.autocraft.control.InvUtil;
import com.autocraft.craft.Stations;
import com.autocraft.goal.Job;
import com.autocraft.goal.Requirement;
import com.autocraft.path.Goal;
import com.autocraft.path.Navigator;
import com.autocraft.travel.BoatTravel;
import com.autocraft.travel.ElytraTravel;
import com.autocraft.world.BiomeFinder;
import com.autocraft.world.BlockUtil;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.Nullable;

/**
 * The actual job: pick up matching drops, mine the nearest good source block, otherwise explore
 * (ores: go to the best Y level and tunnel; surface resources: keep walking the current heading).
 */
public final class CollectBehavior implements Behavior {
	private static final int SCAN_CHUNKS = 5;
	private static final int SCAN_MAX = 96;
	private static final int SCAN_MAX_AGE = 40;
	private static final double DROP_RADIUS = 12;
	private static final int BLACKLIST_TICKS = 20 * 60 * 5;
	private static final int TUNNEL_LEG = 24;
	private static final int WALK_LEG = 64;
	private static final int BIOME_SEARCH_CHUNKS = 12;
	private static final int FLY_LEG = 1000;

	private final Map<BlockPos, Long> blacklist = new HashMap<>();
	private final Set<Integer> unreachableDrops = new HashSet<>();
	private long tick;
	private @Nullable String lastTarget;
	private @Nullable Harvest harvest;
	private @Nullable BoatTravel boat;
	private @Nullable Goal exploreGoal;
	private @Nullable Direction heading;
	private @Nullable ItemEntity chasing;
	private final LongOpenHashSet exhaustedBiomeChunks = new LongOpenHashSet();
	private final Stations.Cleanup cleanup = new Stations.Cleanup();

	@Override
	public String name() {
		return "collect";
	}

	@Override
	public void reset() {
		blacklist.clear();
		unreachableDrops.clear();
		lastTarget = null;
		harvest = null;
		boat = null;
		exploreGoal = null;
		chasing = null;
		exhaustedBiomeChunks.clear();
		cleanup.reset();
	}

	@Override
	public boolean tick(Bot bot) {
		tick++;
		Requirement target = bot.currentTarget();
		if (target == null || target.dimension() != bot.dimension()) return false;
		LocalPlayer p = bot.player();
		Set<Block> harvestable = harvestableSources(p, target);
		if (harvestable.isEmpty()) return false; // gear planner will bring the right tool
		onTargetChange(bot, target);

		if (boat != null) {
			BoatTravel.Status s = boat.tick(bot);
			if (s == BoatTravel.Status.RUNNING) return true;
			if (s == BoatTravel.Status.FAILED) turn();
			boat = null;
			exploreGoal = null;
		}
		if (harvest != null) {
			Harvest.Status s = harvest.tick(bot);
			if (s == Harvest.Status.RUNNING) {
				bot.setStatus("mining " + target.name() + " at " + harvest.target().toShortString());
				return true;
			}
			if (s == Harvest.Status.FAILED) blacklist.put(harvest.target(), tick + BLACKLIST_TICKS);
			bot.scanner().forget(harvest.target());
			harvest = null;
		}
		if (chaseDrops(bot, target)) return true;

		BlockPos source = pickSource(bot, target, harvestable);
		if (source != null) {
			exploreGoal = null;
			bot.noteFound(target.name());
			harvest = new Harvest(source, target.counts());
			harvest.tick(bot);
			bot.setStatus("mining " + target.name() + " at " + source.toShortString());
			return true;
		}
		explore(bot, target);
		return true;
	}

	private void onTargetChange(Bot bot, Requirement target) {
		if (target.name().equals(lastTarget)) return;
		Job job = bot.job();
		if (lastTarget != null && job != null) {
			for (Requirement r : job.requirements()) {
				if (r.name().equals(lastTarget) && job.missing(bot.player(), r) == 0) Chat.info("Collected " + r.amount() + " " + r.name() + ".");
			}
		}
		lastTarget = target.name();
		exhaustedBiomeChunks.clear();
		chasing = null;
		exploreGoal = null;
		if (harvest != null && harvest.isMining()) return; // a second from done: finish it rather than waste the work
		harvest = null;
		bot.nav().stop();
	}

	/** Source blocks we can actually get drops from with the tools carried. */
	private static Set<Block> harvestableSources(LocalPlayer p, Requirement target) {
		Set<Block> out = new HashSet<>();
		for (Block b : target.sources()) {
			if (InvUtil.canHarvest(p, b.defaultBlockState())) out.add(b);
		}
		return out;
	}

	private boolean chaseDrops(Bot bot, Requirement target) {
		if (chasing == null || !chasing.isAlive()) {
			chasing = null;
			List<ItemEntity> drops = bot.scanner().items(DROP_RADIUS, target.counts());
			drops.removeIf(e -> unreachableDrops.contains(e.getId()));
			if (drops.isEmpty()) return false;
			chasing = drops.getFirst();
		}
		bot.nav().setGoal(new Goal.Near(chasing.blockPosition(), 0));
		if (bot.nav().tick() == Navigator.Status.FAILED) {
			unreachableDrops.add(chasing.getId());
			chasing = null;
			bot.nav().stop();
			return false;
		}
		bot.setStatus("picking up " + target.name());
		return true;
	}

	/**
	 * Nearest safe source block, weighting vertical distance (digging is slower than walking). Blocks touching
	 * water (lake beds, flooded caves) are skipped — mining there is 5x slower and drowns the bot — unless
	 * searching dry ground has already failed.
	 */
	private @Nullable BlockPos pickSource(Bot bot, Requirement target, Set<Block> harvestable) {
		ClientLevel level = bot.mc().level;
		BlockPos feet = Navigator.feet(bot.player());
		String key = target.name() + "@" + target.dimension();
		List<BlockPos> found = bot.scanner().findBlocksCached(key, s -> harvestable.contains(s.getBlock()), SCAN_CHUNKS, SCAN_MAX, SCAN_MAX_AGE);
		boolean allowWet = bot.fruitlessLegs(target.name()) >= 2;
		BlockPos best = null;
		double bestScore = Double.MAX_VALUE;
		for (BlockPos pos : found) {
			Long until = blacklist.get(pos);
			if (until != null && until > tick) continue;
			if (!BlockUtil.isBreakable(level, pos, level.getBlockState(pos))) continue;
			if (!allowWet && BlockUtil.touchesWater(level, pos)) continue;
			double dx = pos.getX() - feet.getX(), dy = pos.getY() - feet.getY(), dz = pos.getZ() - feet.getZ();
			double score = Math.sqrt(dx * dx + dz * dz) + Math.abs(dy) * 2;
			if (score < bestScore) {
				bestScore = score;
				best = pos;
			}
		}
		return best == null ? null : BlockUtil.tallPlantCut(level, best);
	}

	private void explore(Bot bot, Requirement target) {
		LocalPlayer p = bot.player();
		if (exploreGoal == null && cleanup.tick(bot)) return; // leaving the area: take our tables and furnaces
		if (heading == null) heading = Direction.fromYRot(p.getYRot());
		if (exploreGoal == null) exploreGoal = nextLeg(bot, target, Navigator.feet(p));
		if (boat == null && target.strategy() != Requirement.Strategy.UNDERGROUND && exploreGoal instanceof Goal.XZ(int x, int z)) {
			BlockPos dest = new BlockPos(x, p.getBlockY(), z);
			if (BoatTravel.worthIt(bot, dest)) {
				boat = new BoatTravel(dest);
				bot.nav().stop();
				return;
			}
		}
		if (exploreGoal instanceof Goal.XZ(int x, int z) && bot.startFlight(new BlockPos(x, p.getBlockY(), z), () -> spotted(bot, target))) return;
		bot.nav().setGoal(exploreGoal);
		Navigator.Status s = bot.nav().tick();
		if (s == Navigator.Status.FAILED) {
			turn();
			exploreGoal = null;
			bot.nav().stop();
			bot.noteFruitlessLeg(target.name());
		} else if (s == Navigator.Status.ARRIVED) {
			exploreGoal = null;
			bot.nav().stop();
			bot.noteFruitlessLeg(target.name());
		}
		bot.setStatus("exploring " + heading.getName() + " for " + target.name());
	}

	/** From the air: a harvestable source of the target in the loaded area below (ends an exploration flight). */
	private static boolean spotted(Bot bot, Requirement target) {
		Set<Block> harvestable = harvestableSources(bot.player(), target);
		return !harvestable.isEmpty() && !bot.scanner().findBlocks(s -> harvestable.contains(s.getBlock()), 3, 1).isEmpty();
	}

	private Goal nextLeg(Bot bot, Requirement target, BlockPos feet) {
		ClientLevel level = bot.mc().level;
		if (!target.biomes().isEmpty()) {
			Goal toBiome = biomeLeg(bot, level, target, feet);
			if (toBiome != null) return toBiome;
		}
		int dx = heading.getStepX(), dz = heading.getStepZ();
		if (target.strategy() == Requirement.Strategy.UNDERGROUND) {
			int minY = level.getMinY() + 5, maxY = target.dimension() == Requirement.Dim.NETHER ? 118 : level.getMaxY() - 5;
			int y = Mth.clamp(target.preferredY(), minY, maxY);
			if (Math.abs(feet.getY() - y) > 2) return new Goal.YLevel(y);
			return new Goal.XYZ(feet.getX() + dx * TUNNEL_LEG, y, feet.getZ() + dz * TUNNEL_LEG);
		}
		int leg = ElytraTravel.canFly(bot.player()) ? FLY_LEG : WALK_LEG; // with wings, scout much further per leg
		return new Goal.XZ(feet.getX() + dx * leg, feet.getZ() + dz * leg);
	}

	/**
	 * Head for the nearest loaded chunk of a preferred biome. Biome area we've stood in without finding
	 * anything in scan range is marked exhausted so the search crawls on instead of circling back.
	 */
	private @Nullable Goal biomeLeg(Bot bot, ClientLevel level, Requirement target, BlockPos feet) {
		if (BiomeFinder.in(level, feet, target.biomes())) {
			int cx = feet.getX() >> 4, cz = feet.getZ() >> 4;
			for (int dx = -SCAN_CHUNKS; dx <= SCAN_CHUNKS; dx++) {
				for (int dz = -SCAN_CHUNKS; dz <= SCAN_CHUNKS; dz++) exhaustedBiomeChunks.add(ChunkPos.pack(cx + dx, cz + dz));
			}
		}
		boolean surface = target.dimension() == Requirement.Dim.OVERWORLD;
		BlockPos spot = BiomeFinder.nearest(level, feet, target.biomes(), BIOME_SEARCH_CHUNKS, surface, exhaustedBiomeChunks::contains);
		if (spot == null) return null;
		heading = Direction.getApproximateNearest(spot.getX() - feet.getX(), 0, spot.getZ() - feet.getZ());
		bot.setStatus("heading for " + level.getBiome(spot).getRegisteredName() + " to find " + target.name());
		return new Goal.XZ(spot.getX(), spot.getZ());
	}

	private void turn() {
		if (heading != null) heading = heading.getClockWise();
	}
}
