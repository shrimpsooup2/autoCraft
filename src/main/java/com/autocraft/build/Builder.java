package com.autocraft.build;

import com.autocraft.Bot;
import com.autocraft.Chat;
import com.autocraft.control.Controls;
import com.autocraft.control.InvUtil;
import com.autocraft.path.Goal;
import com.autocraft.path.Navigator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Executes a {@link BuildPlan} one block at a time: clear the space (top down), then place every block in
 * plan order — walking into reach, solving its orientation, turning one tick, clicking the next, and
 * checking the result (wrong result → break it and retry). Asks for items it doesn't carry by returning
 * NEED_ITEMS with {@link #missing()}. Temporary scaffold blocks for unsupported placements are removed at
 * the end.
 */
public final class Builder {
	public enum Status { RUNNING, NEED_ITEMS, DONE }

	/** Team split: a digger only clears (and mines what's in the way); a placer with a digger skips clearing. */
	public enum Mode { ALL, CLEAR_ONLY, PLACE_ONLY }

	private static final double REACH = 4.0;
	private static final int CONFIRM_TICKS = 8;
	private static final int MAX_ATTEMPTS = 3;
	private static final int STUCK_TICKS = 20 * 20;

	private final BuildPlan plan;
	private final Set<BlockPos> skipped = new HashSet<>();
	private final Set<Item> unobtainable = new HashSet<>();
	/** Items still coming (copper oxidizing): their blocks wait until everything else is placed. */
	private final Set<Item> deferred = new HashSet<>();
	private final Map<BlockPos, Integer> attempts = new HashMap<>();
	private final List<BlockPos> scaffolds = new ArrayList<>();
	private PlacementSolver.@Nullable Solution aiming;
	private @Nullable BlockPos aimingAt;
	private @Nullable Item missing;
	private int waitTicks;
	private int taskTicks;
	private @Nullable BlockPos lastTask;
	private int placed;
	private Mode mode = Mode.ALL;

	public Builder(BuildPlan plan) {
		this.plan = plan;
	}

	public void setMode(Mode mode) {
		this.mode = mode;
	}

	public @Nullable Item missing() {
		return missing;
	}

	public int placed() {
		return placed;
	}

	/** Leave out every block needing this item (it can't be obtained). */
	public void skipItem(Item item) {
		unobtainable.add(item);
	}

	/** Place every block needing this item last (it takes time to obtain). */
	public void deferItem(Item item) {
		deferred.add(item);
	}

	public Set<Item> skippedItems() {
		return unobtainable;
	}

	public Status tick(Bot bot) {
		ClientLevel level = bot.mc().level;
		missing = null;
		if (waitTicks > 0) {
			waitTicks--;
			return Status.RUNNING;
		}
		for (BuildPlan.Task t : plan.clears()) {
			if (mode == Mode.PLACE_ONLY) break;
			if (skipped.contains(t.pos())) continue;
			BlockState world = level.getBlockState(t.pos());
			if (world.isAir() || BuildPlan.isCompanionHalf(t.state()) && BuildPlan.matches(world, t.state())) continue;
			if (!track(bot, t.pos())) continue;
			return clear(bot, t.pos(), world);
		}
		boolean waiting = false;
		for (BuildPlan.Task t : plan.places()) {
			if (skipped.contains(t.pos())) continue;
			BlockState world = level.getBlockState(t.pos());
			if (t.satisfiedBy(world)) continue;
			if (mode == Mode.CLEAR_ONLY) {
				// dig out only what's in the way; placing is someone else's job
				if (world.canBeReplaced() && world.getFluidState().isEmpty()) continue;
				if (!track(bot, t.pos())) continue;
				return clear(bot, t.pos(), world);
			}
			Item item = BuildPlan.itemFor(t.state());
			if (item == null || unobtainable.contains(item)) {
				skipped.add(t.pos());
				continue;
			}
			if (deferred.contains(item)) {
				waiting = true;
				continue;
			}
			if (!track(bot, t.pos())) continue;
			return place(bot, t, world, item);
		}
		if (waiting) {
			deferred.clear(); // only those are left: try for them again
			return Status.RUNNING;
		}
		return removeScaffolds(bot) ? Status.RUNNING : Status.DONE;
	}

	/** Per-task stuck guard: false once a cell has eaten too much time (it gets skipped). */
	private boolean track(Bot bot, BlockPos pos) {
		if (!pos.equals(lastTask)) {
			lastTask = pos;
			taskTicks = 0;
			aiming = null;
		}
		if (++taskTicks > STUCK_TICKS) {
			Chat.warn("Skipping " + pos.toShortString() + " (stuck).");
			skipped.add(pos);
			bot.nav().stop();
			return false;
		}
		return true;
	}

	// ---- clearing -------------------------------------------------------------------------------

	private Status clear(Bot bot, BlockPos pos, BlockState world) {
		LocalPlayer p = bot.player();
		bot.setStatus("clearing " + pos.toShortString());
		if (!inReach(p, pos)) return walk(bot, pos);
		bot.nav().stop();
		if (BuildPlan.isFluid(world) || !world.getFluidState().isEmpty() && world.canBeReplaced()) {
			// scoop sources with an empty bucket, otherwise plug the fluid with a block and mine it next
			if (world.getFluidState().isSource() && InvUtil.select(p, s -> s.is(Items.BUCKET))) {
				bot.controls().lookAt(Vec3.atCenterOf(pos));
				bot.controls().useItem(InteractionHand.MAIN_HAND);
			} else if (!bot.controls().place(pos, InvUtil::isThrowaway)) {
				skipped.add(pos);
			}
			waitTicks = 4;
			return Status.RUNNING;
		}
		bot.controls().mine(pos);
		return Status.RUNNING;
	}

	// ---- placing --------------------------------------------------------------------------------

	private Status place(Bot bot, BuildPlan.Task t, BlockState world, Item item) {
		LocalPlayer p = bot.player();
		BlockPos pos = t.pos();
		bot.setStatus("building " + plan.schematic().name() + ": " + t.state().getBlock().getName().getString() + " at " + pos.toShortString());
		boolean groundWork = t.state().is(Blocks.FARMLAND) || t.state().is(Blocks.DIRT_PATH);
		if (groundWork && (world.is(Blocks.DIRT) || world.is(Blocks.GRASS_BLOCK))) return till(bot, t);
		if (!world.canBeReplaced() || !world.getFluidState().isEmpty() && !BuildPlan.isFluid(t.state())) {
			if (!inReach(p, pos)) return walk(bot, pos);
			bot.nav().stop();
			bot.controls().mine(pos); // wrong block in the way
			return Status.RUNNING;
		}
		if (InvUtil.count(p, s -> s.is(item)) == 0) {
			missing = item;
			return Status.NEED_ITEMS;
		}
		if (!inReach(p, pos) || occupies(p, pos)) return walk(bot, pos);
		bot.nav().stop();
		if (BuildPlan.isFluid(t.state())) return pour(bot, pos, item);

		Controls c = bot.controls();
		if (aiming == null || !pos.equals(aimingAt)) {
			ItemStack stack = p.getInventory().getItem(InvUtil.find(p, s -> s.is(item)));
			BlockState wanted = groundWork ? Blocks.DIRT.defaultBlockState() : t.state();
			PlacementSolver.Solution sol = PlacementSolver.solve(p, pos, wanted, stack, REACH);
			if (sol == null) sol = PlacementSolver.anyFace(p, pos, REACH);
			if (sol == null) return scaffoldBelow(bot, pos);
			if (!InvUtil.select(p, s -> s.is(item))) return Status.RUNNING;
			c.face(sol.yaw(), sol.pitch()); // turn this tick so the server knows our facing before the click
			aiming = sol;
			aimingAt = pos;
			return Status.RUNNING;
		}
		if (!InvUtil.select(p, s -> s.is(item))) return Status.RUNNING;
		c.face(aiming.yaw(), aiming.pitch());
		if (!c.aimed()) return Status.RUNNING; // the block's orientation comes from the facing the server knows
		bot.mc().gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(aiming.hit(), aiming.face(), aiming.against(), false));
		p.swing(InteractionHand.MAIN_HAND, p.getMainHandItem().getAttackAnimation(), false);
		aiming = null;
		waitTicks = CONFIRM_TICKS;
		placed++;
		if (attempts.merge(pos, 1, Integer::sum) > MAX_ATTEMPTS) {
			Chat.warn("Couldn't place " + t.state().getBlock().getName().getString() + " correctly at " + pos.toShortString() + "; leaving it.");
			skipped.add(pos);
		}
		return Status.RUNNING;
	}

	/** Water/lava: aim the bucket at a solid face next to the cell so the fluid lands in it. */
	private Status pour(Bot bot, BlockPos pos, Item bucket) {
		ClientLevel level = bot.mc().level;
		for (Direction face : new Direction[] {Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN}) {
			BlockPos against = pos.relative(face.getOpposite());
			if (level.getBlockState(against).canBeReplaced()) continue;
			if (!InvUtil.select(bot.player(), s -> s.is(bucket))) return Status.RUNNING;
			bot.controls().lookAt(Vec3.atCenterOf(against).add(Vec3.atLowerCornerOf(face.getUnitVec3i()).scale(0.5)));
			bot.controls().useItem(InteractionHand.MAIN_HAND);
			waitTicks = CONFIRM_TICKS;
			attempts.merge(pos, 1, Integer::sum);
			if (attempts.get(pos) > MAX_ATTEMPTS) skipped.add(pos);
			return Status.RUNNING;
		}
		skipped.add(pos);
		return Status.RUNNING;
	}

	/** Farmland / path: the dirt is down, now use a hoe / shovel on its top. */
	private Status till(Bot bot, BuildPlan.Task t) {
		LocalPlayer p = bot.player();
		boolean farmland = t.state().is(Blocks.FARMLAND);
		Predicate<ItemStack> tool = farmland ? s -> s.is(ItemTags.HOES) : s -> s.is(ItemTags.SHOVELS);
		if (InvUtil.find(p, tool) < 0) {
			missing = farmland ? Items.WOODEN_HOE : Items.WOODEN_SHOVEL;
			return Status.NEED_ITEMS;
		}
		if (!inReach(p, t.pos())) return walk(bot, t.pos());
		if (!bot.level().getBlockState(t.pos().above()).isAir()) {
			bot.controls().mine(t.pos().above());
			return Status.RUNNING;
		}
		bot.nav().stop();
		InvUtil.select(p, tool);
		Vec3 hit = Vec3.atCenterOf(t.pos()).add(0, 0.5, 0);
		bot.controls().lookAt(hit);
		bot.mc().gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, t.pos(), false));
		waitTicks = CONFIRM_TICKS;
		if (attempts.merge(t.pos(), 1, Integer::sum) > MAX_ATTEMPTS) skipped.add(t.pos());
		return Status.RUNNING;
	}

	/** Nothing solid to click: put a temporary block underneath first. */
	private Status scaffoldBelow(Bot bot, BlockPos pos) {
		BlockPos below = pos.below();
		if (bot.level().getBlockState(below).canBeReplaced() && bot.controls().place(below, InvUtil::isThrowaway)) {
			scaffolds.add(below);
			waitTicks = CONFIRM_TICKS;
			return Status.RUNNING;
		}
		skipped.add(pos);
		Chat.warn("Nothing to attach " + pos.toShortString() + " to; skipped.");
		return Status.RUNNING;
	}

	/** Mine temporary scaffolds (unless the plan put a real block there). True while working. */
	private boolean removeScaffolds(Bot bot) {
		while (!scaffolds.isEmpty()) {
			BlockPos s = scaffolds.getFirst();
			BlockState world = bot.level().getBlockState(s);
			boolean planned = plan.places().stream().anyMatch(t -> t.pos().equals(s));
			if (world.isAir() || planned) {
				scaffolds.removeFirst();
				continue;
			}
			if (!inReach(bot.player(), s)) {
				walk(bot, s);
				return true;
			}
			bot.controls().mine(s);
			return true;
		}
		return false;
	}

	private Status walk(Bot bot, BlockPos pos) {
		bot.nav().setGoal(new Goal.Reach(pos, REACH - 0.5));
		if (bot.nav().tick() == Navigator.Status.FAILED) {
			bot.nav().stop();
			skipped.add(pos);
			Chat.warn("Can't reach " + pos.toShortString() + "; skipped.");
		}
		return Status.RUNNING;
	}

	private static boolean inReach(LocalPlayer p, BlockPos pos) {
		return Navigator.settled(p) && p.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) <= REACH;
	}

	/** The player's body overlaps the cell (placing there would be refused). */
	private static boolean occupies(LocalPlayer p, BlockPos pos) {
		return p.getBoundingBox().intersects(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
	}
}
