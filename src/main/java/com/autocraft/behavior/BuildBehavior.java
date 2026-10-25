package com.autocraft.behavior;

import com.autocraft.Bases;
import com.autocraft.Bot;
import com.autocraft.Chat;
import com.autocraft.build.BaseWithdraw;
import com.autocraft.build.BuildOrder;
import com.autocraft.build.BuildPlan;
import com.autocraft.build.Builder;
import com.autocraft.build.DepotDeposit;
import com.autocraft.build.FluidFill;
import com.autocraft.build.StructureRegistry;
import com.autocraft.control.InvUtil;
import com.autocraft.craft.Planner;
import com.autocraft.craft.Stations;
import com.autocraft.craft.Workshop;
import com.autocraft.goal.Requirement;
import com.autocraft.path.Goal;
import com.autocraft.path.Navigator;
import com.autocraft.schematic.Schematic;
import com.autocraft.team.Team;
import com.autocraft.team.TeamRole;
import com.autocraft.world.ChestMemory;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

/**
 * Builds the current {@link BuildOrder}: clear and place with {@link Builder}; whenever it runs out of an
 * item, get more — from base chests first, then by crafting/smelting (the recipe planner) or by asking the
 * collector to mine raw materials (the whole window is gathered before placing resumes), buckets are filled at
 * the nearest water/lava. Items nothing can produce (mob drops, loot) are skipped with a message. Finished
 * structures are registered (and protected); a starter base also becomes the storage base. Big solo builds keep
 * spare materials in a stash — the base when it's close, else a chest put down by the site.
 *
 * <p>In a team build the {@link TeamRole} decides the part: the placer builds and takes materials from the depot
 * chest, the digger clears the site and then gathers, gatherers fetch what the leader lists and deliver it.
 */
public final class BuildBehavior implements Behavior {
	/** Materials are fetched for this many stacks' worth of upcoming blocks at a time. */
	private static final int WINDOW_STACKS = 18;
	/** Placer: look in the depot chest this often while teammates gather. */
	private static final int DEPOT_RETRY_TICKS = 20 * 10;
	/** Placer: gather for itself when teammates haven't delivered anything for this long. */
	private static final int DEPOT_PATIENCE_TICKS = 20 * 180;
	/** Gatherer: deliver once this many stacks are carried even if the list isn't complete. */
	private static final int TRIP_STACKS = 6;
	/** A bill this big (stacks) doesn't fit in the inventory: spare materials go to a stash. */
	private static final int BIG_BUILD_STACKS = 27;
	/** The base serves as the stash when it's this close to the site. */
	private static final int BASE_STASH_DISTANCE = 64;

	private @Nullable BuildOrder current;
	private @Nullable BuildPlan plan;
	private @Nullable Builder builder;
	private final Workshop workshop = new Workshop();
	private @Nullable FluidFill fill;
	private @Nullable BaseWithdraw withdraw;
	private boolean triedBase;
	private final Set<Item> triedBaseFor = new HashSet<>();
	private boolean finishing;
	private @Nullable Item lastMissing;
	private @Nullable DepotDeposit delivery;
	private @Nullable Map<Item, Integer> beforeDepotWithdraw;
	/** Big solo builds: where spare materials wait, what we put there, and the deposit/withdrawal in progress. */
	private @Nullable BlockPos stash;
	private final Map<Item, Integer> stashed = new HashMap<>();
	private @Nullable DepotDeposit stashing;
	private @Nullable Map<Item, Integer> beforeStashWithdraw;
	private long ticks;
	private long nextDepotTry;
	private long waitingSince = -1;

	@Override
	public String name() {
		return "build";
	}

	@Override
	public void reset() {
		current = null;
		plan = null;
		builder = null;
		fill = null;
		withdraw = null;
		triedBase = false;
		triedBaseFor.clear();
		finishing = false;
		delivery = null;
		beforeDepotWithdraw = null;
		waitingSince = -1;
		nextDepotTry = 0;
		stash = null;
		stashed.clear();
		stashing = null;
		beforeStashWithdraw = null;
		workshop.reset();
		StructureRegistry.setUnderConstruction(null, null);
	}

	@Override
	public boolean tick(Bot bot) {
		ticks++;
		BuildOrder order = bot.buildOrder();
		if (order == null) {
			if (current != null) reset();
			return false;
		}
		if (order != current) begin(bot, order);
		if (finishing) return finish(bot);

		if (fill != null) {
			FluidFill.Status s = fill.tick(bot);
			if (s == FluidFill.Status.RUNNING) return true;
			if (s == FluidFill.Status.FAILED) {
				Chat.warn("No reachable " + (fill.filled() == Items.LAVA_BUCKET ? "lava" : "water") + " source; skipping those blocks.");
				builder.skipItem(fill.filled());
			}
			fill = null;
		}
		if (withdraw != null) {
			if (withdraw.tick(bot) == BaseWithdraw.Status.RUNNING) return true;
			withdraw = null;
			if (beforeDepotWithdraw != null) noteDepotWithdraw(bot);
			if (beforeStashWithdraw != null) noteStashWithdraw(bot);
		}
		if (stashing != null) {
			DepotDeposit.Status s = stashing.tick(bot);
			if (s == DepotDeposit.Status.RUNNING) return true;
			stashing.delivered().forEach((item, n) -> stashed.merge(item, n, Integer::sum));
			stashing = null;
			if (s == DepotDeposit.Status.NEED_CHEST) return step(bot, Items.CHEST, 1);
		}
		switch (workshop.tick(bot)) {
			case RUNNING -> {
				return true;
			}
			case TRAVEL -> {
				return false; // a procedure needs the other dimension: the portal behavior takes us
			}
			case FAILED -> Chat.warn("Making materials for the build failed; retrying.");
			case WAITING -> {
				if (workshop.waitingFor() != null) builder.deferItem(workshop.waitingFor()); // place the rest first
			}
			default -> {
			}
		}

		TeamRole role = bot.teamRole();
		if (role == TeamRole.GATHER) return gather(bot);
		LocalPlayer p = bot.player();
		if (stash != null && InvUtil.freeSlots(p) <= 2 && stashSpare(bot)) return true;
		// gather the whole window before going back to placing (no mine-one, place-one ping-pong)
		Requirement gathering = bot.need(Bot.NeedOwner.BUILD);
		if (gathering != null && InvUtil.count(p, gathering.counts()) < gathering.amount()) return false;
		builder.setMode(role == TeamRole.CLEAR ? Builder.Mode.CLEAR_ONLY
				: role == TeamRole.PLACE && Team.clearerActive() ? Builder.Mode.PLACE_ONLY : Builder.Mode.ALL);
		Builder.Status status = builder.tick(bot);
		bot.setBuildProgress(builder.placed(), plan.places().size());
		if (status == Builder.Status.DONE && role == TeamRole.CLEAR) {
			Chat.info("Site dug out; gathering materials for the placer now.");
			Team.reportCleared();
			bot.setTeamRole(TeamRole.GATHER);
			return true;
		}
		return switch (status) {
			case RUNNING -> {
				bot.setNeed(Bot.NeedOwner.BUILD, null);
				yield true;
			}
			case DONE -> {
				finishing = true;
				yield finish(bot);
			}
			case NEED_ITEMS -> acquire(bot, builder.missing());
		};
	}

	private void begin(Bot bot, BuildOrder order) {
		reset();
		current = order;
		Schematic s = order.schematic();
		plan = new BuildPlan(s, order.origin(), order.rotation(), order.mirror(), order.substitution());
		if (!order.substitution().isIdentity()) Chat.info("Materials: " + order.substitution().reason() + ".");
		builder = new Builder(plan);
		bot.setBuildPlan(plan);
		StructureRegistry.setUnderConstruction(plan.box(), bot.dimension());
		bot.setBuildMaterials(plan.bill(bot.level(), Integer.MAX_VALUE).keySet());
		Map<Item, Integer> bill = plan.bill(bot.level(), Integer.MAX_VALUE);
		Chat.info("Materials: " + bill.entrySet().stream().limit(12)
				.map(e -> e.getValue() + " " + e.getKey().getDefaultInstance().getHoverName().getString())
				.collect(Collectors.joining(", ")) + (bill.size() > 12 ? ", ..." : ""));
		bot.setBuildDig(digWork(bot));
		int stacks = bill.entrySet().stream().mapToInt(e -> (e.getValue() + e.getKey().getDefaultMaxStackSize() - 1) / e.getKey().getDefaultMaxStackSize())
				.sum();
		if (bot.teamRole() == TeamRole.SOLO && stacks >= BIG_BUILD_STACKS) {
			BoundingBox box = plan.box();
			BlockPos base = bot.base();
			boolean baseClose = base != null && bot.baseDimension() == bot.dimension() && base.distSqr(box.getCenter()) <= BASE_STASH_DISTANCE * BASE_STASH_DISTANCE;
			stash = baseClose ? base : new BlockPos(box.minX() - 2, box.minY(), box.minZ() - 2);
			Chat.info("Big build (" + stacks + " stacks): spare materials go " + (baseClose ? "to the base chests" : "into a chest at " + stash.toShortString()) + ".");
		}
	}

	/** Cells to dig out per tool (pickaxe, shovel, axe), so the gear planner can invest in better tools. */
	private int[] digWork(Bot bot) {
		int[] dig = new int[3];
		for (BuildPlan.Task t : plan.clears()) {
			BlockState s = bot.level().getBlockState(t.pos());
			if (s.isAir() || !s.getFluidState().isEmpty()) continue;
			if (s.is(BlockTags.MINEABLE_WITH_PICKAXE)) dig[0]++;
			else if (s.is(BlockTags.MINEABLE_WITH_SHOVEL)) dig[1]++;
			else if (s.is(BlockTags.MINEABLE_WITH_AXE)) dig[2]++;
		}
		return dig;
	}

	/** Inventory nearly full: put materials not needed for the next stretch into the stash. True if that started. */
	private boolean stashSpare(Bot bot) {
		Set<Item> spare = new HashSet<>(plan.bill(bot.level(), Integer.MAX_VALUE).keySet());
		spare.removeAll(plan.bill(bot.level(), WINDOW_STACKS).keySet());
		spare.removeIf(item -> InvUtil.count(bot.player(), s -> s.is(item)) == 0);
		if (spare.isEmpty()) return false;
		Chat.info("Inventory full: storing spare build materials at " + stash.toShortString() + ".");
		stashing = new DepotDeposit(stash, spare);
		return true;
	}

	private void noteStashWithdraw(Bot bot) {
		Map<Item, Integer> after = snapshot(bot, beforeStashWithdraw.keySet());
		beforeStashWithdraw.forEach((item, n) -> {
			int got = after.getOrDefault(item, 0) - n;
			if (!stashed.containsKey(item)) return;
			if (got <= 0 || stashed.get(item) - got <= 0) stashed.remove(item); // emptied (or someone took it)
			else stashed.merge(item, -got, Integer::sum);
		});
		beforeStashWithdraw = null;
	}

	/** Get more of {@code item} for the next stretch of the build. False when the collector should run. */
	private boolean acquire(Bot bot, @Nullable Item item) {
		if (item == null) return true;
		LocalPlayer p = bot.player();
		if (item != lastMissing) {
			lastMissing = item;
			bot.setBuildMaterials(plan.bill(bot.level(), Integer.MAX_VALUE).keySet());
		}
		if (item == Items.WATER_BUCKET || item == Items.LAVA_BUCKET) {
			if (InvUtil.count(p, s -> s.is(Items.BUCKET)) == 0) return step(bot, Items.BUCKET, 1);
			fill = new FluidFill(item == Items.LAVA_BUCKET, plan.box());
			return true;
		}
		Map<Item, Integer> window = plan.bill(bot.level(), WINDOW_STACKS);
		if (bot.teamRole() == TeamRole.PLACE && bot.depot() != null && Team.gathererCount() > 0 && waitForTeam(bot, item, window)) return true;
		if (stash != null && stashed.getOrDefault(item, 0) > 0) {
			beforeStashWithdraw = snapshot(bot, window.keySet());
			withdraw = new BaseWithdraw(stash, window);
			return true;
		}
		// a base known to hold it (nearest to the site): one trip per item; base chests never looked into: one look
		Requirement.Dim dim = bot.dimension();
		BlockPos site = plan.box().getCenter();
		Bases.Base holder = Bases.inDimension(dim).stream().filter(b -> ChestMemory.count(b.pos(), BaseWithdraw.BASE_RADIUS, dim, s -> s.is(item)) > 0)
				.min(Comparator.comparingDouble(b -> b.pos().distSqr(site))).orElse(null);
		if (holder != null && triedBaseFor.add(item)) {
			withdraw = new BaseWithdraw(holder.pos(), window);
			return true;
		}
		BlockPos base = bot.base();
		if (base != null && bot.baseDimension() == dim && !triedBase && !ChestMemory.surveyed(base, BaseWithdraw.BASE_RADIUS, dim)) {
			triedBase = true;
			withdraw = new BaseWithdraw(base, window);
			return true;
		}
		return step(bot, item, Math.max(1, window.getOrDefault(item, 1)));
	}

	/**
	 * Placer with gatherers: take what's in the depot chest every few seconds and wait near it, rather than
	 * walking off to gather; after a long dry spell gather for itself. True while waiting.
	 */
	private boolean waitForTeam(Bot bot, Item item, Map<Item, Integer> window) {
		if (waitingSince < 0) waitingSince = ticks;
		if (ticks - waitingSince > DEPOT_PATIENCE_TICKS) return false;
		if (ticks >= nextDepotTry) {
			nextDepotTry = ticks + DEPOT_RETRY_TICKS;
			beforeDepotWithdraw = snapshot(bot, window.keySet());
			withdraw = new BaseWithdraw(bot.depot(), window);
			return true;
		}
		bot.setNeed(Bot.NeedOwner.BUILD, null);
		bot.setStatus("waiting for teammates to bring " + item.getDefaultInstance().getHoverName().getString());
		bot.nav().setGoal(new Goal.Near(bot.depot(), 3));
		if (bot.nav().tick() != Navigator.Status.MOVING) bot.nav().stop();
		return true;
	}

	private void noteDepotWithdraw(Bot bot) {
		Map<Item, Integer> taken = new HashMap<>();
		Map<Item, Integer> after = snapshot(bot, beforeDepotWithdraw.keySet());
		beforeDepotWithdraw.forEach((item, n) -> {
			int got = after.getOrDefault(item, 0) - n;
			if (got > 0) taken.put(item, got);
		});
		beforeDepotWithdraw = null;
		if (taken.isEmpty()) return;
		Team.noteWithdrawn(taken);
		waitingSince = -1;
	}

	private static Map<Item, Integer> snapshot(Bot bot, Set<Item> items) {
		Map<Item, Integer> counts = new HashMap<>();
		for (Item item : items) counts.put(item, InvUtil.count(bot.player(), s -> s.is(item)));
		return counts;
	}

	/**
	 * Gatherer: get what the leader listed (crafting/smelting/mining as needed), then carry it to the depot chest.
	 * False while the collector mines raw materials.
	 */
	private boolean gather(Bot bot) {
		LocalPlayer p = bot.player();
		Map<Item, Integer> needs = bot.teamNeeds();
		bot.setBuildMaterials(needs.keySet());
		if (delivery != null) {
			DepotDeposit.Status s = delivery.tick(bot);
			if (s == DepotDeposit.Status.RUNNING) return true;
			Team.reportDelivered(delivery.delivered());
			delivery = null;
			if (s == DepotDeposit.Status.NEED_CHEST) return step(bot, Items.CHEST, 1);
			return true;
		}
		int carriedStacks = 0;
		boolean carrying = false;
		Item next = null;
		int want = 0;
		for (Map.Entry<Item, Integer> e : needs.entrySet()) {
			int have = InvUtil.count(p, s -> s.is(e.getKey()));
			if (have > 0) carrying = true;
			carriedStacks += (have + e.getKey().getDefaultMaxStackSize() - 1) / e.getKey().getDefaultMaxStackSize();
			if (next == null && have < e.getValue()) {
				next = e.getKey();
				want = e.getValue();
			}
		}
		boolean tripFull = carriedStacks >= TRIP_STACKS || InvUtil.freeSlots(p) <= 2;
		if (next != null && !tripFull) return step(bot, next, want);
		if (carrying) {
			if (workshop.packUp(bot)) return true;
			bot.setNeed(Bot.NeedOwner.BUILD, null);
			delivery = new DepotDeposit(bot.depot(), needs.keySet());
			return true;
		}
		bot.setNeed(Bot.NeedOwner.BUILD, null);
		if (workshop.packUp(bot)) return true;
		bot.setStatus("waiting for the placer's next list");
		bot.nav().setGoal(new Goal.Near(bot.depot(), 4));
		if (bot.nav().tick() != Navigator.Status.MOVING) bot.nav().stop();
		return true;
	}

	private boolean step(Bot bot, Item item, int total) {
		Planner planner = Stations.planner(bot);
		Planner.Step step = planner.next(item, total);
		switch (step) {
			case Planner.Craft c -> {
				bot.setNeed(Bot.NeedOwner.BUILD, null);
				workshop.start(c);
				return true;
			}
			case Planner.Smelt s -> {
				bot.setNeed(Bot.NeedOwner.BUILD, null);
				workshop.start(s);
				return true;
			}
			case Planner.Cut c -> {
				bot.setNeed(Bot.NeedOwner.BUILD, null);
				workshop.start(c);
				return true;
			}
			case Planner.Special sp -> {
				if (workshop.packUp(bot)) return true;
				bot.setNeed(Bot.NeedOwner.BUILD, null);
				workshop.start(sp);
				return true;
			}
			case Planner.Gather g -> {
				if (workshop.packUp(bot)) return true;
				if (bot.need(Bot.NeedOwner.BUILD) == null || !bot.need(Bot.NeedOwner.BUILD).name().equals(g.requirement().name())) {
					Chat.info("Gathering " + g.requirement() + " for the build.");
				}
				bot.setNeed(Bot.NeedOwner.BUILD, g.requirement());
				return false;
			}
			case Planner.Unavailable u -> {
				Chat.warn("Can't obtain " + item.getDefaultInstance().getHoverName().getString() + " (needs " + u.what()
						+ "); skipping those blocks. Put some in your inventory or a base chest to include them.");
				builder.skipItem(item);
				return true;
			}
			case Planner.Done d -> {
				return true;
			}
		}
	}

	/** Pack up stations, register the structure, set the base for a starter base, end the order. */
	private boolean finish(Bot bot) {
		if (workshop.tick(bot) == Workshop.Status.RUNNING || workshop.packUp(bot)) return true;
		Team.buildFinished();
		BuildOrder order = current;
		Schematic s = order.schematic();
		BoundingBox box = plan.box();
		String provides = s.farm() == null ? null : s.farm().provides();
		StructureRegistry.setUnderConstruction(null, null);
		StructureRegistry.add(new StructureRegistry.Entry(s.name(), order.origin().getX(), order.origin().getY(), order.origin().getZ(),
				order.rotation().ordinal(), order.mirror().ordinal(), order.substitution().encode(), bot.dimension(), box.minX(), box.minY(),
				box.minZ(), box.maxX(), box.maxY(), box.maxZ(), provides));
		Chat.info("Finished building " + s.name() + " (" + builder.placed() + " placements"
				+ (builder.skippedItems().isEmpty() ? "" : ", skipped blocks needing " + builder.skippedItems().stream()
						.map(i -> i.getDefaultInstance().getHoverName().getString()).collect(Collectors.joining(", "))) + ").");
		if (s.baseSpot() != null) {
			BlockPos spot = plan.toWorld(s.baseSpot());
			Chat.info("Base \"" + bot.setBase(spot) + "\" set at " + spot.toShortString() + ".");
		}
		reset();
		bot.finishBuild();
		if (bot.job() == null) bot.stop(Bot.BUILD_COMPLETE);
		return true;
	}
}
