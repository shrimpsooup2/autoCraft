package com.autocraft.travel.portal;

import com.autocraft.Bot;
import com.autocraft.control.InvUtil;
import com.autocraft.path.Goal;
import com.autocraft.path.Navigator;
import com.autocraft.world.BlockUtil;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * "Bucket portal": casts the 10 frame blocks in place instead of mining obsidian, so no diamond pickaxe is
 * needed. For each frame cell: pour water into it (it spreads to the neighbours), scoop the source back, and
 * at once pour lava into the cell — lava next to water becomes obsidian. Every cell sits on a solid block whose
 * top face is clicked, so the bot must see that face from above: the lower four cells are cast from the
 * ground, the upper six from a 3-block pillar in front; two blocks in the opening hold the top row's water and
 * are mined away before lighting. Lava buckets are refilled at the nearest lava source when they run out.
 */
public final class CastPortalBuilder {
	public enum Status { RUNNING, DONE, FAILED }

	private enum Phase { WALK, CLEAR, CORNERS, GROUND, CLIMB, HIGH, DESCEND, CLEANUP, LIGHT }

	/** Throwaway blocks used: 2 corners, 2 in the opening, 3 for the pillar. */
	public static final int BLOCKS_NEEDED = 7;
	/** Lava buckets worth carrying (more means fewer refill trips). */
	public static final int LAVA_BUCKETS = 6;
	private static final int[][] GROUND = {{1, 0}, {2, 0}, {0, 1}, {3, 1}};
	private static final int[][] SIDES = {{0, 2}, {3, 2}, {0, 3}, {3, 3}};
	private static final int[][] TOP = {{1, 4}, {2, 4}};
	/** Blocks in the opening under the top row (they're its support and keep its water from falling). */
	private static final int[][] TEMPS = {{1, 3}, {2, 3}};
	/** Water ticks every 5 game ticks: give the source time to spread to its neighbours. */
	private static final int SPREAD_TICKS = 8;
	private static final int SETTLE_TICKS = 6;
	private static final int TIMEOUT = 20 * 300;
	private static final int MAX_CAST_FAILURES = 6;

	private final PortalFrame frame;
	private final Direction front;
	private final BlockPos highStand;
	private Phase phase = Phase.WALK;
	private int ticks;
	private int wait;
	private @Nullable BlockPos casting;
	private int castStep;
	private int castFailures;
	private int placeFailures;
	private int lightTries;
	private @Nullable BlockPos refillAt;
	private boolean refilling;

	public CastPortalBuilder(PortalFrame frame) {
		this.frame = frame;
		this.front = frame.along().getCounterClockWise();
		this.highStand = frame.at(1, 0).relative(front, 2).above(3);
	}

	public Status tick(Bot bot) {
		if (++ticks > TIMEOUT) return Status.FAILED;
		if (wait > 0) {
			wait--;
			return Status.RUNNING;
		}
		if (refilling) return refill(bot);
		LocalPlayer p = bot.player();
		ClientLevel level = bot.level();
		bot.setStatus("bucket portal: " + phase.name().toLowerCase());
		switch (phase) {
			case WALK -> {
				if (walkTo(bot, frame.standSpot())) phase = Phase.CLEAR;
			}
			case CLEAR -> {
				for (BlockPos c : frame.cellsToClear()) {
					if (c.equals(Navigator.feet(p)) || c.equals(Navigator.feet(p).above())) continue;
					BlockState s = level.getBlockState(c);
					if (s.isAir() || !s.getFluidState().isEmpty()) continue; // our own water drains away
					if (!p.isWithinBlockInteractionRange(c, 0.0)) return Status.FAILED;
					if (bot.controls().mine(c) && !level.getBlockState(c).canBeReplaced()) return Status.FAILED;
					return Status.RUNNING;
				}
				phase = Phase.CORNERS;
			}
			case CORNERS -> {
				if (!place(bot, frame.at(0, 0)) || !place(bot, frame.at(3, 0))) return placeFailures > 20 ? Status.FAILED : Status.RUNNING;
				phase = Phase.GROUND;
			}
			case GROUND -> {
				Status s = castAll(bot, GROUND, frame.standSpot());
				if (s != null) return s;
				phase = Phase.CLIMB;
			}
			case CLIMB -> {
				// six casts up there: fill every empty bucket first so we needn't climb twice
				if (InvUtil.count(p, s -> s.is(Items.LAVA_BUCKET)) < SIDES.length + TOP.length && InvUtil.count(p, s -> s.is(Items.BUCKET)) > 0) {
					return refill(bot);
				}
				if (walkTo(bot, highStand)) phase = Phase.HIGH;
			}
			case HIGH -> {
				Status s = castAll(bot, SIDES, highStand);
				if (s != null) return s;
				for (int[] t : TEMPS) {
					if (!place(bot, frame.at(t[0], t[1]))) return placeFailures > 20 ? Status.FAILED : Status.RUNNING;
				}
				s = castAll(bot, TOP, highStand);
				if (s != null) return s;
				phase = Phase.DESCEND;
			}
			case DESCEND -> {
				if (walkTo(bot, frame.standSpot())) phase = Phase.CLEANUP;
			}
			case CLEANUP -> {
				// the opening must be air again, the pillar goes too; then wait for the water to drain
				for (int[] t : TEMPS) {
					if (mineAway(bot, frame.at(t[0], t[1]))) return Status.RUNNING;
				}
				for (int dy = 2; dy >= 0; dy--) {
					if (mineAway(bot, highStand.below(3 - dy))) return Status.RUNNING;
				}
				for (int u = 1; u <= 2; u++) {
					for (int v = 1; v <= 3; v++) {
						if (!level.getFluidState(frame.at(u, v)).isEmpty()) return Status.RUNNING;
					}
				}
				phase = Phase.LIGHT;
			}
			case LIGHT -> {
				if (level.getBlockState(frame.at(1, 1)).is(Blocks.NETHER_PORTAL)) return Status.DONE;
				if (lightTries++ >= 3 || !InvUtil.select(p, s -> s.is(Items.FLINT_AND_STEEL))) return Status.FAILED;
				BlockPos base = frame.ignitionBase();
				Vec3 hit = Vec3.atCenterOf(base).add(0, 0.5, 0);
				bot.controls().lookAt(hit);
				bot.mc().gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, base, false));
				wait = 20;
			}
		}
		return Status.RUNNING;
	}

	/** Cast every cell of {@code cells} from {@code stand}; null when all are obsidian. */
	private @Nullable Status castAll(Bot bot, int[][] cells, BlockPos stand) {
		ClientLevel level = bot.level();
		for (int[] c : cells) {
			BlockPos cell = frame.at(c[0], c[1]);
			if (level.getBlockState(cell).is(Blocks.OBSIDIAN)) {
				if (cell.equals(casting)) casting = null;
				continue;
			}
			if (!Navigator.feet(bot.player()).equals(stand)) {
				if (casting == null && needsLava(bot)) return refill(bot);
				walkTo(bot, stand);
				return Status.RUNNING;
			}
			return cast(bot, cell);
		}
		return null;
	}

	/** Water in, scoop it back, lava in while the spread water still touches the cell. */
	private Status cast(Bot bot, BlockPos cell) {
		LocalPlayer p = bot.player();
		ClientLevel level = bot.level();
		if (!cell.equals(casting)) {
			casting = cell;
			castStep = 0;
		}
		Vec3 supportTop = Vec3.atCenterOf(cell.below()).add(0, 0.5, 0);
		switch (castStep) {
			case 0 -> {
				if (needsLava(bot)) {
					casting = null;
					return refill(bot);
				}
				if (!InvUtil.select(p, s -> s.is(Items.WATER_BUCKET))) return Status.FAILED;
				bot.controls().lookAt(supportTop);
				bot.controls().useItem(InteractionHand.MAIN_HAND);
				castStep = 1;
				wait = SPREAD_TICKS;
			}
			case 1 -> {
				if (!level.getFluidState(cell).isSource()) return failedCast(); // the water didn't land in the cell
				if (!InvUtil.select(p, s -> s.is(Items.BUCKET))) return Status.FAILED;
				bot.controls().lookAt(Vec3.atCenterOf(cell));
				bot.controls().useItem(InteractionHand.MAIN_HAND); // water back into the bucket
				if (!InvUtil.select(p, s -> s.is(Items.LAVA_BUCKET))) return Status.FAILED;
				bot.controls().lookAt(supportTop);
				bot.controls().useItem(InteractionHand.MAIN_HAND); // lava beside flowing water: obsidian
				castStep = 2;
				wait = SETTLE_TICKS;
			}
			default -> {
				if (level.getBlockState(cell).is(Blocks.OBSIDIAN)) {
					casting = null;
					return Status.RUNNING;
				}
				return failedCast();
			}
		}
		return Status.RUNNING;
	}

	private Status failedCast() {
		casting = null;
		return ++castFailures > MAX_CAST_FAILURES ? Status.FAILED : Status.RUNNING;
	}

	private static boolean needsLava(Bot bot) {
		return InvUtil.count(bot.player(), s -> s.is(Items.LAVA_BUCKET)) == 0;
	}

	/** Fill every empty bucket at the nearest lava sources (keeps going on later ticks until none is empty). */
	private Status refill(Bot bot) {
		LocalPlayer p = bot.player();
		if (InvUtil.count(p, s -> s.is(Items.BUCKET)) == 0) {
			refilling = false;
			refillAt = null;
			return Status.RUNNING;
		}
		refilling = true;
		ClientLevel level = bot.level();
		if (refillAt == null || !BlockUtil.isLava(level.getBlockState(refillAt)) || !level.getFluidState(refillAt).isSource()) {
			BlockPos feet = Navigator.feet(p);
			List<BlockPos> found = bot.scanner().findBlocks(s -> BlockUtil.isLava(s) && s.getFluidState().isSource(), 3, 64);
			refillAt = found.stream().filter(pos -> level.getBlockState(pos.above()).isAir())
					.min(Comparator.comparingDouble(pos -> pos.distSqr(feet))).orElse(null);
			if (refillAt == null) return Status.FAILED;
		}
		bot.setStatus("filling lava buckets");
		if (!p.isWithinBlockInteractionRange(refillAt, -0.5)) {
			bot.nav().setGoal(new Goal.Reach(refillAt, 3.5));
			if (bot.nav().tick() == Navigator.Status.FAILED) {
				bot.nav().stop();
				return Status.FAILED;
			}
			return Status.RUNNING;
		}
		bot.nav().stop();
		if (!InvUtil.select(p, s -> s.is(Items.BUCKET))) return Status.RUNNING;
		bot.controls().lookAt(Vec3.atCenterOf(refillAt));
		bot.controls().useItem(InteractionHand.MAIN_HAND);
		refillAt = null; // that source is gone now
		wait = 4;
		return Status.RUNNING;
	}

	private boolean walkTo(Bot bot, BlockPos stand) {
		if (Navigator.feet(bot.player()).equals(stand) && Navigator.settled(bot.player())) {
			bot.nav().stop();
			return true;
		}
		bot.nav().setGoal(new Goal.Block(stand));
		if (bot.nav().tick() == Navigator.Status.FAILED) {
			bot.nav().stop();
			placeFailures++;
		}
		return false;
	}

	/** True once {@code pos} holds a block (placing a throwaway if needed). */
	private boolean place(Bot bot, BlockPos pos) {
		if (!bot.level().getBlockState(pos).canBeReplaced()) return true;
		if (!bot.controls().place(pos, InvUtil.scaffold(bot.player(), bot::isJobItem))) placeFailures++;
		wait = 4;
		return false;
	}

	/** True while still mining {@code pos} away. */
	private static boolean mineAway(Bot bot, BlockPos pos) {
		BlockState s = bot.level().getBlockState(pos);
		if (s.isAir() || s.is(Blocks.OBSIDIAN) || !s.getFluidState().isEmpty()) return false;
		return !bot.controls().mine(pos);
	}
}
