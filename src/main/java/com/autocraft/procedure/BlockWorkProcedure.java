package com.autocraft.procedure;

import com.autocraft.Bot;
import com.autocraft.control.InvUtil;
import com.autocraft.control.Placement;
import com.autocraft.path.Goal;
import com.autocraft.path.Navigator;
import com.autocraft.world.BlockUtil;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Put a block down, change it in place, mine it back: logs stripped with an axe, pumpkins carved with shears, dirt
 * turned to mud with a water bottle, concrete powder hardened by touching water (poured from a bucket if there's
 * no water close by, and scooped up again afterwards).
 */
final class BlockWorkProcedure implements Procedure {
	private enum Kind { AXE, SHEARS, WATER_BOTTLE, WATER }

	private record Work(Item input, Kind kind, Block result, double cost) {}

	private final Map<Item, Work> works = new HashMap<>();

	BlockWorkProcedure() {
		works.put(Items.CARVED_PUMPKIN, new Work(Items.PUMPKIN, Kind.SHEARS, Blocks.CARVED_PUMPKIN, 3));
		works.put(Items.MUD, new Work(Items.DIRT, Kind.WATER_BOTTLE, Blocks.MUD, 3));
		for (Item item : BuiltInRegistries.ITEM) {
			String path = BuiltInRegistries.ITEM.getKey(item).getPath();
			if (path.startsWith("stripped_")) {
				Item log = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(path.substring("stripped_".length())));
				Block result = Block.byItem(item);
				if (log != Items.AIR && result != Blocks.AIR) works.put(item, new Work(log, Kind.AXE, result, 2));
			}
		}
		for (DyeColor c : DyeColor.values()) {
			Item concrete = item(c.getName() + "_concrete");
			Item powder = item(c.getName() + "_concrete_powder");
			if (concrete != Items.AIR && powder != Items.AIR) works.put(concrete, new Work(powder, Kind.WATER, Block.byItem(concrete), 2));
		}
	}

	private static Item item(String path) {
		return BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(path));
	}

	@Override
	public boolean makes(Item item) {
		return works.containsKey(item);
	}

	@Override
	public Map<Item, Integer> needs(Item item, int total) {
		Work w = works.get(item);
		Map<Item, Integer> needs = new HashMap<>();
		needs.put(w.input, Math.max(1, total));
		switch (w.kind) {
			case AXE -> needs.put(carriedAxe(), 1);
			case SHEARS -> needs.put(Items.SHEARS, 1);
			case WATER_BOTTLE -> needs.put(Items.GLASS_BOTTLE, 1);
			case WATER -> needs.put(Items.BUCKET, 1);
		}
		return needs;
	}

	@Override
	public double cost(Item item) {
		return works.get(item).cost;
	}

	@Override
	public Item consumed(Item item) {
		return works.get(item).input;
	}

	@Override
	public Task start(Item item, int total) {
		return new Run(item, total, works.get(item));
	}

	/** An axe already carried (any tier), else the cheapest to make. */
	static Item carriedAxe() {
		return Actions.carriedTool(s -> s.is(net.minecraft.tags.ItemTags.AXES), Items.WOODEN_AXE);
	}

	static boolean isWaterBottle(ItemStack s) {
		PotionContents contents = s.get(DataComponents.POTION_CONTENTS);
		return s.is(Items.POTION) && contents != null && contents.is(Potions.WATER);
	}

	private static final class Run implements Task {
		private final Item item;
		private final int total;
		private final Work work;
		private @Nullable BlockPos placed;
		private @Nullable BlockPos water;
		private boolean pouredWater;
		/** Walking to stand beside one water source this long: another one is tried. */
		private static final int APPROACH_TICKS = 20 * 60;
		private int wait;
		private int fails;
		/** Water sources that couldn't be got at (behind rock, unreachable): another one is used. */
		private final Set<BlockPos> unusable = new HashSet<>();
		private int approachTicks;

		Run(Item item, int total, Work work) {
			this.item = item;
			this.total = total;
			this.work = work;
		}

		@Override
		public Status tick(Bot bot) {
			LocalPlayer p = bot.player();
			if (wait > 0) {
				wait--;
				return Status.RUNNING;
			}
			if (Actions.pickUp(bot, s -> s.is(item), 6)) return Status.RUNNING;
			if (InvUtil.count(p, s -> s.is(item)) >= total) return cleanUp(bot);
			if (fails > 6) return Status.FAILED;
			if (work.kind == Kind.WATER) return concrete(bot, p);
			if (work.kind == Kind.WATER_BOTTLE && InvUtil.find(p, BlockWorkProcedure::isWaterBottle) < 0) return fillBottle(bot, p);

			bot.setStatus("making " + item.getDefaultInstance().getHoverName().getString());
			if (placed != null) {
				BlockState s = bot.level().getBlockState(placed);
				if (s.is(work.result)) {
					if (bot.controls().mine(placed)) placed = null; // gone: drops are picked up next tick
					return Status.RUNNING;
				}
				if (s.is(Block.byItem(work.input))) {
					Predicate<ItemStack> tool = switch (work.kind) {
						case AXE -> s2 -> s2.is(net.minecraft.tags.ItemTags.AXES);
						case SHEARS -> s2 -> s2.is(Items.SHEARS);
						default -> BlockWorkProcedure::isWaterBottle;
					};
					if (!Actions.useOn(bot, placed, tool)) fails++;
					wait = 4;
					return Status.RUNNING;
				}
				placed = null; // something else happened to it
			}
			if (InvUtil.count(p, s -> s.is(work.input)) == 0) return Status.FAILED;
			placed = Placement.besideFeet(bot, s -> s.is(work.input), false);
			if (placed == null) fails++;
			wait = 4;
			return Status.RUNNING;
		}

		private Status fillBottle(Bot bot, LocalPlayer p) {
			if (water == null || !BlockUtil.isWater(bot.level().getBlockState(water))) {
				water = bot.scanner().findBlocks(s -> BlockUtil.isWater(s) && s.getFluidState().isSource(), 3, 16).stream()
						.filter(b -> !unusable.contains(b)).min(java.util.Comparator.comparingDouble(b -> b.distSqr(Navigator.feet(p)))).orElse(null);
				if (water == null) return Status.FAILED;
			}
			bot.setStatus("filling a bottle with water");
			if (!seesWater(bot, p)) {
				// stand right beside it: "in reach" through rock (a pocket behind the shaft it dug) the bottle never filled,
				// and the bot stood there for a quarter of an hour
				bot.nav().setGoal(new Goal.Adjacent(water));
				if (bot.nav().tick() == Navigator.Status.FAILED || ++approachTicks > APPROACH_TICKS) {
					bot.nav().stop();
					unusable.add(water);
					water = null;
					approachTicks = 0;
					fails++;
				}
				return Status.RUNNING;
			}
			bot.nav().stop();
			approachTicks = 0;
			bot.controls().lookAt(Vec3.atCenterOf(water));
			if (InvUtil.select(p, s -> s.is(Items.GLASS_BOTTLE))) bot.controls().useItem(net.minecraft.world.InteractionHand.MAIN_HAND);
			wait = 6;
			return Status.RUNNING;
		}

		/** In reach, and the eye's ray to it meets that water itself — not rock in between. */
		private boolean seesWater(Bot bot, LocalPlayer p) {
			if (!p.isWithinBlockInteractionRange(water, -0.5)) return false;
			BlockHitResult hit = bot.level().clip(new ClipContext(p.getEyePosition(), Vec3.atCenterOf(water), ClipContext.Block.COLLIDER,
					ClipContext.Fluid.SOURCE_ONLY, p));
			return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(water);
		}

		/** Concrete: put powder right next to a water source; it hardens on the spot; mine it. */
		private Status concrete(Bot bot, LocalPlayer p) {
			if (water == null || !BlockUtil.isWater(bot.level().getBlockState(water))) {
				water = Actions.nearestBlock(bot, s -> BlockUtil.isWater(s) && s.getFluidState().isSource(), 2);
				if (water == null) return pourWater(bot, p);
			}
			bot.setStatus("hardening concrete");
			if (placed != null) {
				BlockState s = bot.level().getBlockState(placed);
				if (s.is(work.result)) {
					if (bot.controls().mine(placed)) placed = null;
					return Status.RUNNING;
				}
				if (!s.isAir()) return Status.RUNNING; // powder still falling into place
				placed = null;
			}
			switch (Actions.reach(bot, water)) {
				case WORKING -> {
					return Status.RUNNING;
				}
				case FAILED -> {
					water = null;
					fails++;
					return Status.RUNNING;
				}
				case DONE -> {
				}
			}
			if (InvUtil.count(p, s -> s.is(work.input)) == 0) return Status.FAILED;
			for (Direction d : Direction.Plane.HORIZONTAL) {
				BlockPos cell = water.relative(d);
				BlockState at = bot.level().getBlockState(cell);
				if (!at.canBeReplaced() || !at.getFluidState().isEmpty() || !p.isWithinBlockInteractionRange(cell, -0.5)) continue;
				if (new net.minecraft.world.phys.AABB(cell).intersects(p.getBoundingBox())) continue;
				if (bot.controls().place(cell, s -> s.is(work.input))) {
					placed = cell;
					wait = 5;
					return Status.RUNNING;
				}
			}
			fails++;
			water = null;
			return Status.RUNNING;
		}

		private Status pourWater(Bot bot, LocalPlayer p) {
			if (InvUtil.find(p, s -> s.is(Items.WATER_BUCKET)) < 0) return Status.FAILED; // gear fills the bucket near water
			BlockPos feet = com.autocraft.path.Navigator.feet(p);
			for (Direction d : Direction.Plane.HORIZONTAL) {
				BlockPos cell = feet.relative(d, 2);
				if (!bot.level().getBlockState(cell).canBeReplaced() || !BlockUtil.canStandOn(bot.level(), cell.below(), bot.level().getBlockState(cell.below()))) {
					continue;
				}
				if (Actions.useOn(bot, cell.below(), s -> s.is(Items.WATER_BUCKET))) {
					water = cell;
					pouredWater = true;
					wait = 5;
					return Status.RUNNING;
				}
			}
			fails++;
			return Status.RUNNING;
		}

		/** Scoop up water we poured. */
		private Status cleanUp(Bot bot) {
			if (!pouredWater || water == null || !bot.level().getFluidState(water).isSource()) return Status.DONE;
			if (Actions.reach(bot, water) != Actions.Result.DONE) return Status.RUNNING;
			bot.controls().lookAt(net.minecraft.world.phys.Vec3.atCenterOf(water));
			if (InvUtil.select(bot.player(), s -> s.is(Items.BUCKET))) bot.controls().useItem(net.minecraft.world.InteractionHand.MAIN_HAND);
			pouredWater = false;
			return Status.DONE;
		}
	}
}
