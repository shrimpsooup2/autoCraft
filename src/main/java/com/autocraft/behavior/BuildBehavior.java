package com.autocraft.behavior;

import com.autocraft.Bot;
import com.autocraft.Chat;
import com.autocraft.build.BaseWithdraw;
import com.autocraft.build.BuildOrder;
import com.autocraft.build.BuildPlan;
import com.autocraft.build.Builder;
import com.autocraft.build.FluidFill;
import com.autocraft.build.StructureRegistry;
import com.autocraft.control.InvUtil;
import com.autocraft.craft.Planner;
import com.autocraft.craft.Workshop;
import com.autocraft.schematic.Schematic;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

/**
 * Builds the current {@link BuildOrder}: clear and place with {@link Builder}; whenever it runs out of an
 * item, get more — from base chests first, then by crafting/smelting (the recipe planner) or by asking the
 * collector to mine raw materials, buckets are filled at the nearest water/lava. Items nothing can produce
 * (mob drops, loot) are skipped with a message. Finished structures are registered (and protected); a
 * starter base also becomes the storage base.
 */
public final class BuildBehavior implements Behavior {
	/** Materials are fetched for this many stacks' worth of upcoming blocks at a time. */
	private static final int WINDOW_STACKS = 18;

	private @Nullable BuildOrder current;
	private @Nullable BuildPlan plan;
	private @Nullable Builder builder;
	private final Workshop workshop = new Workshop();
	private @Nullable FluidFill fill;
	private @Nullable BaseWithdraw withdraw;
	private boolean triedBase;
	private boolean finishing;
	private @Nullable Item lastMissing;

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
		finishing = false;
		workshop.reset();
		StructureRegistry.setUnderConstruction(null, null);
	}

	@Override
	public boolean tick(Bot bot) {
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
		}
		switch (workshop.tick(bot)) {
			case RUNNING -> {
				return true;
			}
			case FAILED -> Chat.warn("Crafting for the build failed; retrying.");
			default -> {
			}
		}

		Builder.Status status = builder.tick(bot);
		bot.setBuildProgress(builder.placed(), plan.places().size());
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
		String wood = s.builtIn() ? BuildPlan.preferredWood(bot.player()) : null;
		plan = new BuildPlan(s, order.origin(), order.rotation(), wood);
		builder = new Builder(plan);
		StructureRegistry.setUnderConstruction(plan.box(), bot.dimension());
		bot.setBuildMaterials(plan.bill(bot.level(), Integer.MAX_VALUE).keySet());
		Map<Item, Integer> bill = plan.bill(bot.level(), Integer.MAX_VALUE);
		Chat.info("Materials: " + bill.entrySet().stream().limit(12)
				.map(e -> e.getValue() + " " + e.getKey().getDefaultInstance().getHoverName().getString())
				.collect(Collectors.joining(", ")) + (bill.size() > 12 ? ", ..." : ""));
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
		if (!triedBase && bot.base() != null && bot.baseDimension() == bot.dimension()) {
			triedBase = true;
			withdraw = new BaseWithdraw(bot.base(), window);
			return true;
		}
		return step(bot, item, Math.max(1, window.getOrDefault(item, 1)));
	}

	private boolean step(Bot bot, Item item, int total) {
		boolean table = !bot.scanner().findBlocks(s -> s.is(Blocks.CRAFTING_TABLE), 1, 1).isEmpty();
		boolean furnace = !bot.scanner().findBlocks(s -> s.is(Blocks.FURNACE), 1, 1).isEmpty();
		Planner planner = new Planner(bot.player(), bot.dimension(), table, furnace, bot::isJobItem);
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
		BuildOrder order = current;
		Schematic s = order.schematic();
		BoundingBox box = plan.box();
		String provides = s.farm() == null ? null : s.farm().provides();
		StructureRegistry.setUnderConstruction(null, null);
		StructureRegistry.add(new StructureRegistry.Entry(s.name(), order.origin().getX(), order.origin().getY(), order.origin().getZ(),
				order.rotation().ordinal(), bot.dimension(), box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ(), provides));
		Chat.info("Finished building " + s.name() + " (" + builder.placed() + " placements"
				+ (builder.skippedItems().isEmpty() ? "" : ", skipped blocks needing " + builder.skippedItems().stream()
						.map(i -> i.getDefaultInstance().getHoverName().getString()).collect(Collectors.joining(", "))) + ").");
		if (s.baseSpot() != null) {
			BlockPos spot = plan.toWorld(s.baseSpot());
			bot.setBase(spot);
			Chat.info("Base set to " + spot.toShortString() + ".");
		}
		reset();
		bot.finishBuild();
		if (bot.job() == null) bot.stop("build complete");
		return true;
	}
}
