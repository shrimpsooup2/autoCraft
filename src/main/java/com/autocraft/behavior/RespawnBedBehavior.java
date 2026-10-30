package com.autocraft.behavior;

import com.autocraft.Bot;
import com.autocraft.Chat;
import com.autocraft.Settings;
import com.autocraft.SpawnPoint;
import com.autocraft.build.PlacementSolver;
import com.autocraft.control.InvUtil;
import com.autocraft.craft.Planner;
import com.autocraft.craft.Stations;
import com.autocraft.craft.Workshop;
import com.autocraft.goal.Job;
import com.autocraft.goal.Requirement;
import com.autocraft.loot.LootSites;
import com.autocraft.path.Goal;
import com.autocraft.path.Navigator;
import com.autocraft.procedure.Procedures;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.client.gui.screens.InBedChatScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * Respawn beds (setting, off by default). Working in the Overworld more than {@link Settings#respawnBedDistance} blocks
 * from the spawn point ({@link SpawnPoint}) when dying looks likely, put a bed down near the work and click it, so a
 * death means a short walk back to the dropped items instead of a trip across the world. Likely means one of:
 * <ul>
 * <li>it already died during this job;</li>
 * <li>a Nether trip is about to start (a Nether death respawns in the Overworld): the bed goes by the portal;</li>
 * <li>the job's next item comes from a guarded structure (trial chamber, ancient city, stronghold, mansion, outpost,
 * dungeon) and its tell blocks or guards are in sight;</li>
 * <li>the job hunts hostile mobs (creepers for gunpowder);</li>
 * <li>night, hostile mobs around and weak armor.</li>
 * </ul>
 * In Java Edition clicking a bed sets the respawn point before the daytime and monster checks, so it works at noon and
 * with zombies around; at night with none around the player falls asleep, and the bot gets straight up again. One of our
 * beds within {@link #REUSE_RADIUS} is clicked instead of making one; else a carried bed goes down, else the planner
 * gets one (three wool of a color and three planks: the collector gathers as a {@link Bot.NeedOwner#BED} need). Before
 * work at a guarded structure a bed is got ahead of time, still up where the sheep are.
 */
public final class RespawnBedBehavior implements Behavior {
	private static final int CHECK_EVERY = 20;
	/** A bed of ours this close to the work is used again instead of making another. */
	private static final int REUSE_RADIUS = 64;
	/** At most one new bed this often: work that travels (a long exploration) would otherwise leave a trail of them. */
	private static final long NEW_BED_INTERVAL = 20 * 60 * 10;
	/** After a failed attempt, no new one for this long. */
	private static final long RETRY_AFTER = 20 * 60 * 5;
	/** Nothing makes a bed here (no sheep, no wool): don't ask again for this long. */
	private static final long UNAVAILABLE_QUIET = 20 * 60 * 15;
	/** Getting a bed (wool, planks, crafting) may take this long. */
	private static final long GET_LIMIT = 20 * 60 * 5;
	/** Walking to one site to place the bed; then another site is tried. */
	private static final int SITE_WALK_LIMIT = 20 * 45;
	/** Getting to the bed and having it take, all told. */
	private static final long USE_LIMIT = 20 * 90;
	/** The server's answer to a bed click comes back within this. */
	private static final int REPLY_TICKS = 60;
	private static final int PLACE_CONFIRM_TICKS = 10;
	private static final int MAX_CLICKS = 3;
	private static final int MAX_SITES = 4;
	private static final int MAX_CRAFT_FAILURES = 3;
	/** The portal behavior walking to a Nether portal within this distance: the trip is about to start. */
	private static final int PORTAL_NEAR = 24;
	/** Night: this many hostile mobs within {@link #NIGHT_RADIUS}, and armor below {@link #WEAK_ARMOR} (iron chestplate + leggings is 11). */
	private static final int NIGHT_MOBS = 2;
	private static final double NIGHT_RADIUS = 24;
	private static final int WEAK_ARMOR = 10;
	/** A guarded structure is "here" when its tell blocks are within this many chunks or its guards within {@link #GUARD_RADIUS}. */
	private static final int SITE_CHUNKS = 2;
	private static final double GUARD_RADIUS = 48;
	private static final int SITE_SCAN_AGE = 100;
	private static final double REACH = 4.5;

	private enum Phase { IDLE, GET, PLACE, USE }

	/** A Nether portal the portal behavior is walking to, and the game time it said so. */
	private static @Nullable BlockPos portalAhead;
	private static long portalAheadAt = Long.MIN_VALUE;

	private final Workshop workshop = new Workshop();
	private Phase phase = Phase.IDLE;
	private int sinceCheck = CHECK_EVERY;
	/** Where the work is: the bed goes down near here. */
	private @Nullable BlockPos anchor;
	/** Only get a bed now (a guarded structure is next but not here yet); it goes down once there. */
	private boolean getOnly;
	private long phaseStart;
	private int craftFailures;
	private BedSite.@Nullable Site site;
	private final Set<BlockPos> rejectedSites = new HashSet<>();
	private int sitesTried;
	private int walkTicks;
	private PlacementSolver.@Nullable Solution aiming;
	private int waitTicks;
	private int clicks;
	/** Foot or head of the bed being clicked. */
	private @Nullable BlockPos bed;
	private boolean newBed;
	private boolean closer;
	// these outlive reset(): they're about the world, not the job
	private long quietUntil;
	private long lastNewBed = -1;
	/** Our beds that wouldn't take (blocked, occupied, out of reach): not tried again this session. */
	private final Set<BlockPos> unusable = new LinkedHashSet<>();

	@Override
	public String name() {
		return "bed";
	}

	@Override
	public void reset() {
		workshop.reset();
		phase = Phase.IDLE;
		sinceCheck = CHECK_EVERY;
		anchor = null;
		getOnly = false;
		craftFailures = 0;
		site = null;
		rejectedSites.clear();
		sitesTried = 0;
		walkTicks = 0;
		aiming = null;
		waitTicks = 0;
		clicks = 0;
		bed = null;
		newBed = false;
		closer = false;
	}

	/** The portal behavior is heading for {@code portal} to go to the Nether: a respawn bed may belong next to it. */
	public static void noteNetherTrip(Bot bot, BlockPos portal) {
		if (Navigator.feet(bot.player()).distSqr(portal) > PORTAL_NEAR * PORTAL_NEAR) return;
		portalAhead = portal.immutable();
		portalAheadAt = bot.level().getGameTime();
	}

	@Override
	public boolean tick(Bot bot) {
		Settings settings = Settings.get();
		if (!settings.respawnBeds || !bot.hasWork() || bot.level().dimension() != Level.OVERWORLD) {
			if (phase != Phase.IDLE) abandon(bot); // turned off, or taken out of the Overworld halfway: beds explode elsewhere
			return false;
		}
		long now = bot.level().getGameTime();
		switch (workshop.tick(bot)) {
			case RUNNING -> {
				return true;
			}
			case TRAVEL -> {
				return false;
			}
			case FAILED, WAITING -> {
				if (++craftFailures >= MAX_CRAFT_FAILURES) return fail(bot, "making a bed kept failing", RETRY_AFTER);
			}
			case DONE, IDLE -> {
			}
		}
		return switch (phase) {
			case IDLE -> consider(bot, settings, now);
			case GET -> get(bot, now);
			case PLACE -> place(bot, now);
			case USE -> use(bot, now);
		};
	}

	// ---- when -----------------------------------------------------------------------------------

	/** Far from the spawn point with a reason to expect a death: start (see the class comment). */
	private boolean consider(Bot bot, Settings settings, long now) {
		boolean portalFresh = portalAhead != null && now - portalAheadAt <= 1;
		if (!portalFresh && ++sinceCheck < CHECK_EVERY) return false;
		sinceCheck = 0;
		if (now < quietUntil || bot.quiet()) return false; // quiet: an ancient city hears every block placed
		BlockPos work = portalFresh ? portalAhead : Navigator.feet(bot.player());
		SpawnPoint.Spot spawn = SpawnPoint.current();
		double far = spawn == null ? Double.POSITIVE_INFINITY : spawn.distance(work, SpawnPoint.dimensionOf(bot.level()));
		LootSites.Site guarded = guardedSiteNext(bot);
		String why = far <= settings.respawnBedDistance ? null
				: portalFresh ? "a Nether trip is next (a death there respawns in the Overworld)" : likelyDeath(bot, guarded);
		if (why == null) {
			// nothing to do here — except a bed to take along to a guarded structure that's next: it's usually far off and
			// underground (trial chambers, ancient cities), where no sheep live
			if (guarded == null || siteHere(bot, guarded) || carryingBed(bot.player()) || !newBedAllowed(now)) return false;
			getOnly = true;
			Chat.info("Respawn bed: a " + guarded.name() + " is next; getting a bed to take along.");
			return begin(bot, Phase.GET, work, now);
		}
		String from = Double.isInfinite(far) ? "in another dimension" : (int) far + " blocks away";
		BlockPos ours = ourBedNear(bot, work, spawn);
		if (ours != null) {
			Chat.info("Respawn bed: the spawn point is " + from + " and " + why + "; using our bed at " + ours.toShortString() + ".");
			bed = ours;
			newBed = false;
			return begin(bot, Phase.USE, work, now);
		}
		if (!newBedAllowed(now)) return false;
		Chat.info("Respawn bed: the spawn point is " + from + " and " + why + "; putting a bed down here.");
		return begin(bot, carryingBed(bot.player()) ? Phase.PLACE : Phase.GET, work, now);
	}

	private boolean begin(Bot bot, Phase next, BlockPos work, long now) {
		anchor = work;
		phase = next;
		phaseStart = now;
		bot.nav().stop();
		return true;
	}

	private boolean newBedAllowed(long now) {
		return lastNewBed < 0 || now - lastNewBed >= NEW_BED_INTERVAL;
	}

	/**
	 * Why a death looks likely here, or null: died already on this job, hunting hostile mobs, at a guarded structure,
	 * or night with mobs around and weak armor.
	 */
	private static @Nullable String likelyDeath(Bot bot, LootSites.@Nullable Site guarded) {
		int deaths = bot.jobDeaths();
		if (deaths > 0) return "we already died " + (deaths == 1 ? "once" : deaths + " times") + " on this job";
		Requirement target = bot.currentTarget();
		Set<EntityType<?>> hunted = new LinkedHashSet<>(bot.huntTargets());
		if (target != null) hunted.addAll(target.mobs());
		String monsters = String.join(", ", hunted.stream().filter(t -> t.getCategory() == MobCategory.MONSTER)
				.map(t -> t.getDescription().getString().toLowerCase()).toList());
		if (!monsters.isEmpty()) return "the work is hunting hostile mobs (" + monsters + ")";
		if (guarded != null && siteHere(bot, guarded)) return "the work is in a " + guarded.name();
		LocalPlayer p = bot.player();
		if (bot.level().isDarkOutside() && p.getArmorValue() < WEAK_ARMOR) {
			int mobs = bot.scanner().hostiles(NIGHT_RADIUS).size();
			if (mobs >= NIGHT_MOBS) return "it's night with " + mobs + " hostile mobs around and weak armor";
		}
		return null;
	}

	/** The guarded Overworld structure the job's next item comes from (by its procedure), or null. */
	private static LootSites.@Nullable Site guardedSiteNext(Bot bot) {
		Job job = bot.job();
		Requirement next = job == null ? null : job.nextUnmet(bot.player());
		if (next == null || !next.isCrafted()) return null;
		LootSites.Site site = Procedures.dangerousSite(next.product());
		return site != null && site.dim() == Requirement.Dim.OVERWORLD ? site : null;
	}

	/** Its tell blocks or guards are around: we're at it. */
	private static boolean siteHere(Bot bot, LootSites.Site site) {
		if (!bot.scanner().findBlocksCached("bed:" + site.name(), site.tell(), SITE_CHUNKS, 1, SITE_SCAN_AGE).isEmpty()) return true;
		return !site.tellMobs().isEmpty() && !bot.scanner().entities(GUARD_RADIUS, e -> site.tellMobs().contains(e.getType())).isEmpty();
	}

	/**
	 * Our bed nearest to {@code work} within {@link #REUSE_RADIUS} that's still there (gone ones are forgotten), not the
	 * spawn point itself (clicking that changes nothing: it would be clicked again every second).
	 */
	private @Nullable BlockPos ourBedNear(Bot bot, BlockPos work, SpawnPoint.@Nullable Spot spawn) {
		ClientLevel level = bot.level();
		BlockPos best = null;
		for (SpawnPoint.Bed b : SpawnPoint.beds()) {
			BlockPos pos = b.pos();
			if (unusable.contains(pos) || pos.distSqr(work) > REUSE_RADIUS * REUSE_RADIUS) continue;
			if (spawn != null && spawn.kind() == SpawnPoint.Kind.BED && spawn.pos().distManhattan(pos) <= 1) continue;
			if (level.isLoaded(pos) && !level.getBlockState(pos).is(BlockTags.BEDS)) {
				SpawnPoint.removeBed(pos); // broken since
				continue;
			}
			if (best == null || pos.distSqr(work) < best.distSqr(work)) best = pos;
		}
		return best;
	}

	private static boolean carryingBed(LocalPlayer p) {
		return InvUtil.find(p, s -> s.is(ItemTags.BEDS)) >= 0;
	}

	// ---- getting one ----------------------------------------------------------------------------

	/** Carry a bed: from the planner (craft, or wool and planks gathered by the collector as the BED need). */
	private boolean get(Bot bot, long now) {
		LocalPlayer p = bot.player();
		if (carryingBed(p)) {
			bot.setNeed(Bot.NeedOwner.BED, null);
			if (workshop.packUp(bot)) return true; // the crafting table we put down
			if (getOnly) {
				finish(bot);
				return false;
			}
			return enter(bot, Phase.PLACE, now);
		}
		if (now - phaseStart > GET_LIMIT) return fail(bot, "getting a bed took too long", RETRY_AFTER);
		if (bot.need(Bot.NeedOwner.BED) != null && ++sinceCheck < CHECK_EVERY) return false; // the collector is on it
		sinceCheck = 0;
		Planner.Step step = Stations.planner(bot).bed();
		switch (step) {
			case Planner.Gather g -> {
				if (workshop.packUp(bot)) return true;
				Requirement before = bot.need(Bot.NeedOwner.BED);
				if (before == null || !before.name().equals(g.requirement().name())) Chat.info("Gathering " + g.requirement() + " for a bed.");
				bot.setNeed(Bot.NeedOwner.BED, g.requirement());
				return false;
			}
			case Planner.Unavailable u -> {
				return fail(bot, "can't get a bed here (needs " + u.what() + ")", UNAVAILABLE_QUIET);
			}
			case Planner.Done d -> {
				return true; // carried after all: placed next tick
			}
			case Planner.Special sp -> {
				if (workshop.packUp(bot)) return true;
				bot.setNeed(Bot.NeedOwner.BED, null);
				workshop.start(sp);
				bot.setStatus("respawn bed: getting wool");
				return true;
			}
			default -> {
				bot.setNeed(Bot.NeedOwner.BED, null);
				workshop.start(step);
				return true;
			}
		}
	}

	// ---- putting it down ------------------------------------------------------------------------

	/** Walk behind a fitting site near the work and place the bed facing away from us, so it lies the way we picked. */
	private boolean place(Bot bot, long now) {
		LocalPlayer p = bot.player();
		ClientLevel level = bot.level();
		if (site != null && level.getBlockState(site.foot()).is(BlockTags.BEDS)) return placed(bot, now);
		if (!carryingBed(p)) return enter(bot, Phase.GET, now); // thrown out or used up somehow: get another
		if (site == null) {
			if (sitesTried >= MAX_SITES) return fail(bot, "no bed would go down near " + anchor.toShortString(), RETRY_AFTER);
			site = BedSite.find(bot, anchor, rejectedSites);
			if (site == null) return fail(bot, "no flat, dry spot clear of mobs for a bed near " + anchor.toShortString(), RETRY_AFTER);
			sitesTried++;
			walkTicks = 0;
			clicks = 0;
			aiming = null;
		}
		if (waitTicks > 0) {
			waitTicks--;
			return true;
		}
		if (!BedSite.stillFits(level, site) || clicks >= MAX_CLICKS) return rejectSite(bot);
		if (!Navigator.feet(p).equals(site.stand()) || !Navigator.settled(p)) {
			if (++walkTicks > SITE_WALK_LIMIT) return rejectSite(bot);
			bot.setStatus("respawn bed: walking to a spot for it at " + site.foot().toShortString());
			bot.nav().setGoal(new Goal.Block(site.stand()));
			if (bot.nav().tick() == Navigator.Status.FAILED) return rejectSite(bot);
			return true;
		}
		bot.nav().stop();
		bot.setStatus("respawn bed: putting it down at " + site.foot().toShortString());
		int slot = InvUtil.find(p, s -> s.is(ItemTags.BEDS));
		ItemStack stack = p.getInventory().getItem(slot);
		if (aiming == null) {
			if (!(stack.getItem() instanceof BlockItem item)) return rejectSite(bot);
			BlockState want = item.getBlock().defaultBlockState().setValue(BedBlock.FACING, site.facing());
			aiming = PlacementSolver.solve(p, site.foot(), want, stack, REACH);
			if (aiming == null) return rejectSite(bot);
		}
		if (!InvUtil.select(p, s -> s.is(ItemTags.BEDS))) return true;
		bot.controls().face(aiming.yaw(), aiming.pitch());
		if (!bot.controls().aimed()) return true; // which way the bed lies comes from the facing the server knows
		bot.mc().gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(aiming.hit(), aiming.face(), aiming.against(), false));
		p.swing(InteractionHand.MAIN_HAND, p.getMainHandItem().getAttackAnimation(), false);
		clicks++;
		waitTicks = PLACE_CONFIRM_TICKS;
		aiming = null;
		return true;
	}

	private boolean placed(Bot bot, long now) {
		SpawnPoint.addBed(site.foot());
		lastNewBed = now;
		bed = site.foot();
		newBed = true;
		return enter(bot, Phase.USE, now);
	}

	private boolean rejectSite(Bot bot) {
		bot.nav().stop();
		rejectedSites.add(site.foot());
		site = null;
		waitTicks = 0;
		return true;
	}

	// ---- using it -------------------------------------------------------------------------------

	/** Stand by the bed, click it (not sneaking: that would skip the bed), and wait for the game's answer. */
	private boolean use(Bot bot, long now) {
		LocalPlayer p = bot.player();
		ClientLevel level = bot.level();
		if (p.isSleeping()) { // night, no monsters: asleep, and the spawn point was set first
			wakeUp(bot, p);
			SpawnPoint.set(bed, SpawnPoint.dimensionOf(level), SpawnPoint.Kind.BED, "the bed took");
			finish(bot);
			return true;
		}
		if (waitTicks > 0) {
			switch (SpawnPoint.reply()) {
				case SET, ALREADY -> {
					finish(bot);
					return true;
				}
				case TOO_FAR -> {
					closer = true;
					waitTicks = 0;
				}
				case OBSTRUCTED -> {
					return unusableBed(bot, now, "something is in the way above it");
				}
				case OCCUPIED -> {
					return unusableBed(bot, now, "it's occupied");
				}
				case NONE -> {
					bot.setStatus("respawn bed: waiting for the game to confirm the bed");
					waitTicks--;
					return true;
				}
			}
		}
		if (now - phaseStart > USE_LIMIT) return unusableBed(bot, now, "it never took");
		BlockState state = level.getBlockState(bed);
		if (level.isLoaded(bed) && !state.is(BlockTags.BEDS)) {
			SpawnPoint.removeBed(bed);
			return fail(bot, "the bed at " + bed.toShortString() + " is gone", RETRY_AFTER);
		}
		if (!inRange(p, bed, state)) {
			bot.setStatus("respawn bed: walking to the bed at " + bed.toShortString());
			bot.nav().setGoal(new Goal.Near(bed, closer ? 1 : 2));
			if (bot.nav().tick() == Navigator.Status.FAILED) return unusableBed(bot, now, "it can't be reached");
			return true;
		}
		bot.nav().stop();
		if (clicks >= MAX_CLICKS) return unusableBed(bot, now, "the game never confirmed it");
		if (p.isShiftKeyDown()) return true; // a sneaking click with something in hand skips the bed
		bot.setStatus("respawn bed: setting the spawn point at the bed at " + bed.toShortString());
		SpawnPoint.bedClicked(bed, now);
		bot.controls().interact(bed);
		clicks++;
		waitTicks = REPLY_TICKS;
		return true;
	}

	/**
	 * Close enough for the server: vanilla allows 3 blocks across and 2 up or down from either half, measured from the
	 * feet; 2 and 1 leave a margin ({@link #closer}, after a "too far away": 1).
	 */
	private boolean inRange(LocalPlayer p, BlockPos bedPos, BlockState state) {
		if (!state.is(BlockTags.BEDS)) return false;
		BlockPos other = state.getValue(BedBlock.PART) == BedPart.FOOT ? bedPos.relative(state.getValue(BedBlock.FACING))
				: bedPos.relative(state.getValue(BedBlock.FACING).getOpposite());
		double reach = closer ? 1.5 : 2.5;
		for (BlockPos half : new BlockPos[] {bedPos, other}) {
			double dx = Math.abs(p.getX() - (half.getX() + 0.5)), dy = Math.abs(p.getY() - half.getY()), dz = Math.abs(p.getZ() - (half.getZ() + 0.5));
			if (dx <= reach && dz <= reach && dy <= 1.5 && Navigator.settled(p)) return true;
		}
		return false;
	}

	/** Get straight up again: sleeping isn't the point (and would skip the night for everyone in singleplayer). */
	private static void wakeUp(Bot bot, LocalPlayer p) {
		p.connection.send(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.STOP_SLEEPING));
		if (bot.mc().gui.screen() instanceof InBedChatScreen) bot.mc().gui.setScreen(null);
	}

	/** This bed won't take: ours from before → make a new one instead; the one just placed → give up for a while. */
	private boolean unusableBed(Bot bot, long now, String why) {
		unusable.add(bed);
		if (newBed || !newBedAllowed(now)) return fail(bot, "the bed at " + bed.toShortString() + " won't take (" + why + ")", RETRY_AFTER);
		Chat.info("Respawn bed: our bed at " + bed.toShortString() + " won't take (" + why + "); putting a new one down.");
		bed = null;
		return enter(bot, carryingBed(bot.player()) ? Phase.PLACE : Phase.GET, now);
	}

	// ---- bookkeeping ----------------------------------------------------------------------------

	private boolean enter(Bot bot, Phase next, long now) {
		phase = next;
		phaseStart = now;
		site = null;
		sitesTried = 0;
		rejectedSites.clear();
		waitTicks = 0;
		clicks = 0;
		closer = false;
		bot.nav().stop();
		return true;
	}

	private void finish(Bot bot) {
		bot.setNeed(Bot.NeedOwner.BED, null);
		bot.nav().stop();
		reset();
	}

	private boolean fail(Bot bot, String why, long quiet) {
		Chat.warn("Respawn bed: " + why + "; trying again in " + quiet / 1200 + " minutes.");
		quietUntil = bot.level().getGameTime() + quiet;
		finish(bot);
		return true;
	}

	/** Setting off or out of the Overworld halfway: drop it quietly (it's tried again when it applies again). */
	private void abandon(Bot bot) {
		finish(bot);
	}
}
