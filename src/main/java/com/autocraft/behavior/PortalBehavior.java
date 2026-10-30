package com.autocraft.behavior;

import com.autocraft.Bot;
import com.autocraft.Chat;
import com.autocraft.control.InvUtil;
import com.autocraft.craft.Planner;
import com.autocraft.craft.Stations;
import com.autocraft.craft.Workshop;
import com.autocraft.goal.Requirement;
import com.autocraft.goal.Requirement.Dim;
import com.autocraft.goal.Resources;
import com.autocraft.path.Goal;
import com.autocraft.path.Navigator;
import com.autocraft.travel.portal.CastPortalBuilder;
import com.autocraft.travel.portal.KnownPortals;
import com.autocraft.travel.portal.ObsidianCaster;
import com.autocraft.travel.portal.PortalBuilder;
import com.autocraft.travel.portal.PortalFrame;
import com.autocraft.travel.portal.PortalOptions;
import com.autocraft.world.BlockUtil;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Changes dimension when needed: gear materials only found on the other side, the job's next requirement
 * lives there, a go-to task's destination is there, or a task is done and the base it ends at is on the other
 * side. In the Overworld it compares rough time
 * costs first ({@link PortalOptions}): walking to a remembered or visible portal, casting a bucket portal at a lava
 * pool (no diamond pickaxe), or getting 10 obsidian (mined, or cast from lava and mined) and building. Portals
 * used are remembered per dimension for the trip back.
 */
public final class PortalBehavior implements Behavior {
	private static final int PORTAL_SCAN_CHUNKS = 8;
	private static final int OBSIDIAN = 10;
	private static final int CORNER_BLOCKS = 3;
	/** Inside this long without being taken (vanilla takes 4 s): still on the portal's cooldown, step out and in again. */
	private static final int PORTAL_TIMEOUT = 20 * 6;
	/** Clear of the portal this long before stepping back in: its cooldown (10 ticks) only runs down outside it. */
	private static final int OUTSIDE_TICKS = 15;
	private static final int MAX_SITE_FAILURES = 3;
	private static final int BUCKETS = CastPortalBuilder.LAVA_BUCKETS + 1;
	/** Build the bucket portal within this distance of the lava, so refills are short. */
	private static final int NEAR_LAVA = 6;

	private final Workshop workshop = new Workshop();
	private @Nullable Dim lastDim;
	private @Nullable BlockPos entering;
	private @Nullable PortalBuilder builder;
	private @Nullable ObsidianCaster caster;
	private @Nullable CastPortalBuilder bucketPortal;
	/** How this trip gets through (decided once per trip by time cost). */
	private PortalOptions.@Nullable Kind plan;
	private int insideTicks;
	/** Inside a portal on its cooldown (just came through it, or relogged in it): leave it before going back in. */
	private boolean stepOut;
	private int outsideTicks;
	private @Nullable BlockPos exit;
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

		// tools first (gear needs), then food for the trip (or home for it), then going back for our items after a
		// death — before the job's own materials, which are often among those items (a diamond pickaxe, netherite scrap)
		// — as long as there's a pickaxe to dig the way (respawned empty-handed it tried to dig down to the portal by
		// hand); then those materials, a base to fetch from, then the job; a task whose goal is met heads for its base
		Requirement materialNeed = bot.materialNeed();
		Requirement gearNeed = bot.need(Bot.NeedOwner.GEAR);
		Requirement foodNeed = bot.need(Bot.NeedOwner.FOOD);
		boolean canDig = InvUtil.find(bot.player(), s -> s.is(ItemTags.PICKAXES)) >= 0;
		Dim want = gearNeed != null ? gearNeed.dimension() : foodNeed != null ? foodNeed.dimension()
				: bot.recovery() != null && (canDig || materialNeed == null) ? bot.recovery().dim()
				: materialNeed != null ? materialNeed.dimension() : bot.finishing() != null ? bot.tripDimension()
				: bot.fetchOrder() != null ? bot.fetchOrder().dim()
				: bot.procedureDimension() != null ? bot.procedureDimension() : desiredDimension(bot);
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

	/** Where the job's next mined requirement is; else where the task is headed (a go-to destination). */
	private static @Nullable Dim desiredDimension(Bot bot) {
		Dim next = bot.nextDimension();
		return next != null ? next : bot.tripDimension();
	}

	private void arrived(Bot bot, Dim from, Dim to) {
		if (entering != null) KnownPortals.remember(from, entering);
		List<BlockPos> here = bot.scanner().findBlocks(s -> s.is(Blocks.NETHER_PORTAL), 1, 1);
		BlockPos exit = here.isEmpty() ? null : bottomOf(bot.mc().level, here.getFirst());
		if (exit != null) KnownPortals.remember(to, exit);
		entering = null;
		insideTicks = 0;
		leavePortal();
		builder = null;
		bucketPortal = null;
		plan = null;
		bot.nav().stop();
		bot.scanner().clear();
		Chat.info("Arrived in the " + to.name().toLowerCase() + (exit == null ? "." : ", portal at " + exit.toShortString() + "."));
	}

	/** The nearest portal we know of in {@code dim} (trusted while out of view), else one in sight. */
	private @Nullable BlockPos findPortal(Bot bot, Dim dim) {
		ClientLevel level = bot.mc().level;
		BlockPos feet = Navigator.feet(bot.player());
		for (BlockPos k = KnownPortals.nearest(dim, feet); k != null; k = KnownPortals.nearest(dim, feet)) {
			if (!level.isLoaded(k) || level.getBlockState(k).is(Blocks.NETHER_PORTAL)) return k;
			KnownPortals.forget(dim, k); // broken since
		}
		List<BlockPos> found = bot.scanner().findBlocksCached("portal@" + dim, s -> s.is(Blocks.NETHER_PORTAL), PORTAL_SCAN_CHUNKS, 1, 100);
		return found.isEmpty() ? null : bottomOf(level, found.getFirst());
	}

	private static BlockPos bottomOf(ClientLevel level, BlockPos portal) {
		BlockPos pos = portal;
		while (level.getBlockState(pos.below()).is(Blocks.NETHER_PORTAL)) pos = pos.below();
		return pos.immutable();
	}

	/**
	 * Walk next to the portal, step in, stand still until the dimension changes. The game counts the whole portal
	 * block, and its cooldown (set on arrival, and on every tick spent touching a portal) only runs down while the
	 * body is clear of it: arrived (or relogged) in a portal, the bot first steps out, then back in. Waiting inside
	 * on the cooldown, or "stepping out" with half its body still in the portal, it stood there until mobs and the
	 * magma floor killed it.
	 */
	private boolean enter(Bot bot, BlockPos portal) {
		LocalPlayer p = bot.player();
		ClientLevel level = bot.mc().level;
		BlockPos feet = Navigator.feet(p);
		// a Nether death respawns in the Overworld: a respawn bed by this portal first (it runs before us, when it applies)
		if (bot.dimension() == Dim.OVERWORLD) RespawnBedBehavior.noteNetherTrip(bot, portal);
		if (touchingPortal(level, p)) {
			entering = portal;
			if (stepOut && stepOut(bot, level, feet)) return true;
			bot.nav().stop();
			bot.setStatus("waiting in the portal");
			if (++insideTicks > PORTAL_TIMEOUT) {
				insideTicks = 0;
				leavePortal();
			}
			return true;
		}
		insideTicks = 0;
		if (stepOut && ++outsideTicks < OUTSIDE_TICKS) {
			bot.nav().stop();
			bot.setStatus("stepping out of the portal");
			return true;
		}
		stepOut = false;
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
			KnownPortals.forget(bot.dimension(), portal);
			Chat.warn("Can't reach the portal at " + portal.toShortString());
			return false;
		}
		bot.setStatus("walking to the portal at " + portal.toShortString());
		return true;
	}

	private void leavePortal() {
		stepOut = true;
		outsideTicks = 0;
		exit = null;
	}

	/**
	 * Walk (sneaking: lava lakes ring Nether portals) toward the middle of a cell beside the portal — the feet's own
	 * cell when only the body still reaches into the portal — until clear of it; false when boxed in.
	 */
	private boolean stepOut(Bot bot, ClientLevel level, BlockPos feet) {
		if (exit == null || feet.distManhattan(exit) > 1) exit = level.getBlockState(feet).is(Blocks.NETHER_PORTAL) ? exitFrom(level, feet) : feet;
		if (exit == null) {
			stepOut = false; // nowhere to step: wait inside after all
			return false;
		}
		bot.nav().stop();
		bot.setStatus("stepping out of the portal");
		bot.controls().faceHorizontally(Vec3.atBottomCenterOf(exit));
		bot.controls().forward();
		bot.controls().sneak();
		return true;
	}

	/** A cell beside {@code feet} to step out to: room to stand, not portal, a floor — a safe one before magma. */
	private static @Nullable BlockPos exitFrom(ClientLevel level, BlockPos feet) {
		BlockPos best = null;
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockPos c = feet.relative(d);
			BlockState at = level.getBlockState(c), floor = level.getBlockState(c.below());
			if (at.is(Blocks.NETHER_PORTAL) || !BlockUtil.isPassable(level, c, at) || !BlockUtil.isPassable(level, c.above(), level.getBlockState(c.above()))) {
				continue;
			}
			if (BlockUtil.canStandOn(level, c.below(), floor)) return c;
			if (best == null && floor.is(Blocks.MAGMA_BLOCK)) best = c; // crossed sneaking, no burns
		}
		return best;
	}

	/** Any part of the body is in a portal block (the game counts the whole block). */
	private static boolean touchingPortal(ClientLevel level, LocalPlayer p) {
		AABB box = p.getBoundingBox().deflate(1.0E-5);
		for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
			if (level.getBlockState(pos).is(Blocks.NETHER_PORTAL)) return true;
		}
		return false;
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
