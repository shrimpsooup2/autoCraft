package com.autocraft.behavior;

import com.autocraft.Bot;
import com.autocraft.path.Goal;
import com.autocraft.path.Navigator;
import com.autocraft.world.BlockUtil;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Walk to a block, mine it, then pick up the matching drops. Shared by collecting, tool crafting
 * (picking the crafting table back up) and storage (picking a shulker back up). Tick until not RUNNING.
 */
public final class Harvest {
	public enum Status { RUNNING, DONE, FAILED }

	private enum Phase { WALK, MINE, PICKUP }

	private static final int MINE_TIMEOUT = 20 * 30;
	private static final int PICKUP_TIMEOUT = 20 * 6;

	private final BlockPos target;
	private final Predicate<ItemStack> drops;
	private Phase phase = Phase.WALK;
	private int phaseTicks;
	private @Nullable ItemEntity chasing;

	public Harvest(BlockPos target, Predicate<ItemStack> drops) {
		this.target = target.immutable();
		this.drops = drops;
	}

	public BlockPos target() {
		return target;
	}

	/** Already breaking the block (not walking or picking up). */
	public boolean isMining() {
		return phase == Phase.MINE;
	}

	public Status tick(Bot bot) {
		phaseTicks++;
		ClientLevel level = bot.mc().level;
		switch (phase) {
			case WALK -> {
				if (level.getBlockState(target).isAir()) return enter(Phase.PICKUP);
				if (canMineFromHere(bot)) {
					bot.nav().stop();
					return enter(Phase.MINE);
				}
				bot.nav().setGoal(new Goal.Adjacent(target));
				Navigator.Status s = bot.nav().tick();
				if (s == Navigator.Status.FAILED) return Status.FAILED;
				if (s == Navigator.Status.ARRIVED && !canMineFromHere(bot)) return Status.FAILED;
			}
			case MINE -> {
				if (!canMineFromHere(bot)) return enter(Phase.WALK);
				if (bot.controls().mine(target)) {
					return level.getBlockState(target).isAir() ? enter(Phase.PICKUP) : Status.FAILED;
				}
				if (phaseTicks > MINE_TIMEOUT) return Status.FAILED;
			}
			case PICKUP -> {
				if (phaseTicks > PICKUP_TIMEOUT) return Status.DONE;
				if (chasing == null || !chasing.isAlive()) {
					List<ItemEntity> items = bot.scanner().items(8, drops);
					items.removeIf(i -> i.blockPosition().distSqr(target) > 36);
					if (items.isEmpty()) return phaseTicks > 4 ? Status.DONE : Status.RUNNING; // drops spawn a tick or two late
					chasing = items.getFirst();
				}
				bot.nav().setGoal(new Goal.Near(chasing.blockPosition(), 0));
				if (bot.nav().tick() == Navigator.Status.FAILED) {
					chasing = null;
					return Status.DONE;
				}
			}
		}
		return Status.RUNNING;
	}

	private Status enter(Phase next) {
		phase = next;
		phaseTicks = 0;
		chasing = null;
		return Status.RUNNING;
	}

	/**
	 * In reach and either standing next to it or its face toward us is open (logs above head, exposed ore).
	 * Never the block we stand on unless what's under it is safe to land on (lava pockets under netherrack).
	 */
	private boolean canMineFromHere(Bot bot) {
		LocalPlayer p = bot.player();
		if (!Navigator.settled(p) || !p.isWithinBlockInteractionRange(target, -0.5)) return false;
		BlockPos feet = Navigator.feet(p);
		ClientLevel level = bot.mc().level;
		if (target.getX() == feet.getX() && target.getZ() == feet.getZ() && target.getY() < feet.getY()) {
			BlockPos under = target.below();
			BlockState floor = level.getBlockState(under);
			if (!BlockUtil.canStandOn(level, under, floor) && !BlockUtil.isWater(floor)) return false;
		}
		if (new Goal.Adjacent(target).isEnd(feet.getX(), feet.getY(), feet.getZ())) return true;
		BlockPos front = target.relative(bot.controls().faceToward(target));
		return level.getBlockState(front).getCollisionShape(level, front).isEmpty();
	}
}
