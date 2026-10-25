package com.autocraft.behavior;

import com.autocraft.Bot;
import com.autocraft.Chat;
import com.autocraft.control.InvUtil;
import com.autocraft.craft.Planner;
import com.autocraft.craft.Stations;
import com.autocraft.craft.Workshop;
import com.autocraft.goal.Job;
import com.autocraft.goal.Requirement;
import com.autocraft.goal.Requirement.Dim;
import com.autocraft.goal.Resources;
import com.autocraft.path.Goal;
import com.autocraft.path.Navigator;
import com.autocraft.travel.portal.CastPortalBuilder;
import com.autocraft.travel.portal.ObsidianCaster;
import com.autocraft.travel.portal.PortalBuilder;
import com.autocraft.travel.portal.PortalFrame;
import com.autocraft.travel.portal.PortalOptions;
import com.autocraft.world.BlockUtil;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Changes dimension when needed: gear materials only found on the other side, the job's next requirement
 * lives there, or the job is done and the base is on the other side. In the Overworld it compares rough time
 * costs first ({@link PortalOptions}): walking to a remembered or visible portal, casting a bucket portal at a lava
 * pool (no diamond pickaxe), or getting 10 obsidian (mined, or cast from lava and mined) and building. Portals
 * used are remembered per dimension for the trip back.
 */
public final class PortalBehavior implements Behavior {
	private static final int PORTAL_SCAN_CHUNKS = 8;
	private static final int OBSIDIAN = 10;
	private static final int CORNER_BLOCKS = 3;
	private static final int PORTAL_TIMEOUT = 20 * 15;
	private static final int MAX_SITE_FAILURES = 3;
	private static final int BUCKETS = CastPortalBuilder.LAVA_BUCKETS + 1;
	/** Build the bucket portal within this distance of the lava, so refills are short. */
	private static final int NEAR_LAVA = 6;

	private final Map<Dim, BlockPos> known = new EnumMap<>(Dim.class);
	private final Workshop workshop = new Workshop();
	private @Nullable Dim lastDim;
	private @Nullable BlockPos entering;
	private @Nullable PortalBuilder builder;
	private @Nullable ObsidianCaster caster;
	private @Nullable CastPortalBuilder bucketPortal;
	/** How this trip gets through (decided once per trip by time cost). */
	private PortalOptions.@Nullable Kind plan;
	private int insideTicks;
	private int castFailures;
	private int siteFailures;
	private int bucketPortalFailures;

	@Override
	public String name() {
		return "portal";
	}

	@Override
	public void reset() {
		builder = null;
		caster = null;
		bucketPortal = null;
		plan = null;
		workshop.reset();
		insideTicks = 0;
		castFailures = 0;
		siteFailures = 0;
		bucketPortalFailures = 0;
	}

	@Override
	public boolean tick(Bot bot) {
		Dim now = bot.dimension();
		if (lastDim != null && now != lastDim) arrived(bot, lastDim, now);
		lastDim = now;

		// material needs (iron for a new pickaxe, quartz for observers or comparators) decide first, then going back
		// for our items after a death, a base to fetch from, then the job
		Requirement materialNeed = bot.materialNeed();
		Dim want = materialNeed != null ? materialNeed.dimension() : bot.recovery() != null ? bot.recovery().dim()
				: bot.fetchOrder() != null ? bot.fetchOrder().dim() : bot.procedureDimension() != null ? bot.procedureDimension() : desiredDimension(bot);
		if (want == null || want == now) {
			if (materialNeed == null) bot.setNeed(Bot.NeedOwner.TRAVEL, null);
			return false;
		}

		BlockPos portal = findPortal(bot, now);
		if (now == Dim.NETHER) {
			if (portal != null) {
				bot.setNeed(Bot.NeedOwner.TRAVEL, null);
				return enter(bot, portal);
			}
			Chat.warn("No portal back to the Overworld found nearby.");
			bot.stop("lost in the Nether");
			return true;
		}
		if (plan == null || plan == PortalOptions.Kind.EXISTING && portal == null) {
			List<PortalOptions.Option> options = PortalOptions.evaluate(bot, portal);
			plan = options.getFirst().kind();
			Chat.info("Getting to the Nether: " + PortalOptions.describe(options) + " → " + options.getFirst() + ".");
		}
		return switch (plan) {
			case EXISTING -> {
				bot.setNeed(Bot.NeedOwner.TRAVEL, null);
				if (enter(bot, portal)) yield true;
				plan = null; // unreachable: weigh the options again
				yield true;
			}
			case BUCKET_PORTAL -> bucketPortal(bot);
			case OBSIDIAN -> build(bot);
		};
	}

	/** Where the job's next mined requirement is; with the job done, the base (to deposit). */
	private static @Nullable Dim desiredDimension(Bot bot) {
		Dim next = bot.nextDimension();
		if (next != null) return next;
		Job job = bot.job();
		return job != null && job.isComplete(bot.player()) && bot.base() != null ? bot.baseDimension() : null;
	}

	private void arrived(Bot bot, Dim from, Dim to) {
		if (entering != null) known.put(from, entering);
		List<BlockPos> here = bot.scanner().findBlocks(s -> s.is(Blocks.NETHER_PORTAL), 1, 1);
		if (!here.isEmpty()) known.put(to, bottomOf(bot.mc().level, here.getFirst()));
		entering = null;
		insideTicks = 0;
		builder = null;
		bucketPortal = null;
		plan = null;
		bot.nav().stop();
		bot.scanner().clear();
		Chat.info("Arrived in the " + to.name().toLowerCase() + (here.isEmpty() ? "." : ", portal at " + known.get(to).toShortString() + "."));
	}

	private @Nullable BlockPos findPortal(Bot bot, Dim dim) {
		ClientLevel level = bot.mc().level;
		BlockPos k = known.get(dim);
		if (k != null && (!level.isLoaded(k) || level.getBlockState(k).is(Blocks.NETHER_PORTAL))) return k; // trust memory when out of view
		List<BlockPos> found = bot.scanner().findBlocksCached("portal@" + dim, s -> s.is(Blocks.NETHER_PORTAL), PORTAL_SCAN_CHUNKS, 1, 100);
		return found.isEmpty() ? null : bottomOf(level, found.getFirst());
	}

	private static BlockPos bottomOf(ClientLevel level, BlockPos portal) {
		BlockPos pos = portal;
		while (level.getBlockState(pos.below()).is(Blocks.NETHER_PORTAL)) pos = pos.below();
		return pos.immutable();
	}

	/** Walk next to the portal, step in, stand still until the dimension changes. */
	private boolean enter(Bot bot, BlockPos portal) {
		LocalPlayer p = bot.player();
		ClientLevel level = bot.mc().level;
		BlockPos feet = Navigator.feet(p);
		if (level.getBlockState(feet).is(Blocks.NETHER_PORTAL)) {
			entering = portal;
			bot.nav().stop();
			bot.setStatus("waiting in the portal");
			if (++insideTicks > PORTAL_TIMEOUT) {
				insideTicks = 0;
				bot.controls().back(); // step out and retry
			}
			return true;
		}
		insideTicks = 0;
		Goal beside = new Goal.Beside(portal);
		if (beside.isEnd(feet.getX(), feet.getY(), feet.getZ()) && Navigator.settled(p)) {
			bot.nav().stop();
			bot.controls().faceHorizontally(Vec3.atBottomCenterOf(portal));
			bot.controls().forward();
			bot.setStatus("stepping into the portal");
			return true;
		}
		if (bot.startFlight(portal)) return true;
		bot.nav().setGoal(beside);
		if (bot.nav().tick() == Navigator.Status.FAILED) {
			bot.nav().stop();
			known.remove(bot.dimension());
			Chat.warn("Can't reach the portal at " + portal.toShortString());
			return false;
		}
		bot.setStatus("walking to the portal at " + portal.toShortString());
		return true;
	}

	/** Gather obsidian (or cast it), then build and light a portal here. */
	private boolean build(Bot bot) {
		LocalPlayer p = bot.player();
		if (InvUtil.count(p, s -> s.is(Items.FLINT_AND_STEEL)) == 0) {
			bot.setStatus("waiting for flint and steel");
			return false; // the gear planner makes it as part of the Nether kit
		}
		if (builder == null && InvUtil.count(p, s -> s.is(Items.OBSIDIAN)) < OBSIDIAN) return getObsidian(bot);
		if (builder == null && InvUtil.count(p, InvUtil::isThrowaway) < CORNER_BLOCKS) {
			bot.setNeed(Bot.NeedOwner.TRAVEL, buildingBlocks(CORNER_BLOCKS));
			return false;
		}
		bot.setNeed(Bot.NeedOwner.TRAVEL, null);
		if (builder == null) {
			PortalFrame site = PortalFrame.find(bot);
			if (site == null) {
				// nowhere to build here: walk a little and look again
				BlockPos feet = Navigator.feet(p);
				bot.nav().setGoal(new Goal.XZ(feet.getX() + 12, feet.getZ() + 12));
				bot.nav().tick();
				bot.setStatus("looking for space to build a portal");
				return true;
			}
			builder = new PortalBuilder(site);
		}
		PortalBuilder.Status s = builder.tick(bot);
		if (s == PortalBuilder.Status.RUNNING) return true;
		builder = null;
		if (s == PortalBuilder.Status.DONE) {
			Chat.info("Portal built and lit.");
		} else if (++siteFailures >= MAX_SITE_FAILURES) {
			Chat.warn("Could not build a portal after " + siteFailures + " tries.");
			bot.stop("portal building failed");
		}
		return true;
	}

	private boolean getObsidian(Bot bot) {
		LocalPlayer p = bot.player();
		boolean visible = !bot.scanner().findBlocks(s -> s.is(Blocks.OBSIDIAN), 5, 1).isEmpty();
		if (caster == null && !visible && castFailures < 3 && InvUtil.count(p, s -> s.is(Items.WATER_BUCKET)) > 0) {
			caster = new ObsidianCaster();
		}
		if (caster != null) {
			ObsidianCaster.Status s = caster.tick(bot);
			if (s == ObsidianCaster.Status.RUNNING) return true;
			if (s == ObsidianCaster.Status.FAILED) castFailures++;
			caster = null;
		}
		bot.setNeed(Bot.NeedOwner.TRAVEL, Resources.get("obsidian", OBSIDIAN));
		return false;
	}

	/** Bucket portal: buckets (made from iron), a water bucket, throwaway blocks, then cast the frame by the lava. */
	private boolean bucketPortal(Bot bot) {
		LocalPlayer p = bot.player();
		if (workshop.tick(bot) == Workshop.Status.RUNNING) return true;
		if (bucketPortal == null) {
			if (InvUtil.count(p, s -> s.is(Items.FLINT_AND_STEEL)) == 0) {
				bot.setStatus("waiting for flint and steel");
				return false; // the gear planner makes it as part of the Nether kit
			}
			int buckets = InvUtil.count(p, s -> s.is(Items.BUCKET) || s.is(Items.LAVA_BUCKET) || s.is(Items.WATER_BUCKET));
			if (buckets < BUCKETS) {
				Planner.Step step = Stations.planner(bot).next(Items.BUCKET, InvUtil.count(p, s -> s.is(Items.BUCKET)) + BUCKETS - buckets);
				switch (step) {
					case Planner.Done d -> {
					}
					case Planner.Gather g -> {
						if (workshop.packUp(bot)) return true;
						bot.setNeed(Bot.NeedOwner.TRAVEL, g.requirement());
						return false;
					}
					case Planner.Unavailable u -> {
						return fallBack("can't make buckets (" + u.what() + ")");
					}
					default -> {
						bot.setNeed(Bot.NeedOwner.TRAVEL, null);
						workshop.start(step);
						return true;
					}
				}
			}
			if (InvUtil.count(p, s -> s.is(Items.WATER_BUCKET)) == 0) return fillWater(bot);
			if (InvUtil.count(p, InvUtil::isThrowaway) < CastPortalBuilder.BLOCKS_NEEDED) {
				bot.setNeed(Bot.NeedOwner.TRAVEL, buildingBlocks(CastPortalBuilder.BLOCKS_NEEDED));
				return false;
			}
			bot.setNeed(Bot.NeedOwner.TRAVEL, null);
			if (workshop.packUp(bot)) return true;
			BlockPos lava = nearest(bot, s -> BlockUtil.isLava(s) && s.getFluidState().isSource(), 5);
			if (lava == null) return fallBack("no lava pool in sight");
			if (lava.distSqr(Navigator.feet(p)) > NEAR_LAVA * NEAR_LAVA) {
				bot.setStatus("heading to the lava pool for a bucket portal");
				bot.nav().setGoal(new Goal.Near(lava, NEAR_LAVA - 2));
				if (bot.nav().tick() == Navigator.Status.FAILED) return fallBack("can't reach the lava pool");
				return true;
			}
			bot.nav().stop();
			PortalFrame site = PortalFrame.find(bot);
			if (site == null) return fallBack("no room for a portal by the lava");
			bucketPortal = new CastPortalBuilder(site);
		}
		switch (bucketPortal.tick(bot)) {
			case RUNNING -> {
				return true;
			}
			case DONE -> {
				Chat.info("Bucket portal built and lit.");
				bucketPortal = null;
				return true;
			}
			case FAILED -> {
				bucketPortal = null;
				if (++bucketPortalFailures >= 2) return fallBack("casting the frame failed twice");
				return true;
			}
		}
		return true;
	}

	private boolean fallBack(String why) {
		Chat.warn("Bucket portal: " + why + "; getting obsidian instead.");
		bucketPortal = null;
		plan = PortalOptions.Kind.OBSIDIAN;
		return true;
	}

	/** Fill an empty bucket at the nearest water source. */
	private boolean fillWater(Bot bot) {
		LocalPlayer p = bot.player();
		BlockPos water = nearest(bot, s -> BlockUtil.isWater(s) && s.getFluidState().isSource(), 4);
		if (water == null) return fallBack("no water nearby for the bucket");
		bot.setStatus("filling a water bucket");
		if (!p.isWithinBlockInteractionRange(water, -0.5)) {
			bot.nav().setGoal(new Goal.Reach(water, 3.5));
			if (bot.nav().tick() == Navigator.Status.FAILED) return fallBack("can't reach water");
			return true;
		}
		bot.nav().stop();
		if (InvUtil.select(p, s -> s.is(Items.BUCKET))) {
			bot.controls().lookAt(Vec3.atCenterOf(water));
			bot.controls().useItem(net.minecraft.world.InteractionHand.MAIN_HAND);
		}
		return true;
	}

	private static @Nullable BlockPos nearest(Bot bot, java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> match, int chunks) {
		BlockPos feet = Navigator.feet(bot.player());
		return bot.scanner().findBlocks(match, chunks, 32).stream().filter(pos -> bot.level().getBlockState(pos.above()).isAir())
				.min(Comparator.comparingDouble(pos -> pos.distSqr(feet))).orElse(null);
	}

	private static Requirement buildingBlocks(int count) {
		return new Requirement("building blocks", InvUtil::isThrowaway,
				Set.of(Blocks.STONE, Blocks.COBBLESTONE, Blocks.DEEPSLATE, Blocks.DIRT, Blocks.GRASS_BLOCK),
				count, Requirement.Strategy.ANYWHERE, 0, Dim.OVERWORLD);
	}
}
