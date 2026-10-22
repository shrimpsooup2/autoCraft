package com.autocraft.behavior;

import com.autocraft.Bot;
import com.autocraft.path.Goal;
import com.autocraft.path.Navigator;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
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
					if (items.isEmpty()) return phaseTicks > 10 ? Status.DONE : Status.RUNNING; // drops spawn a tick late
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

	/** In reach and either standing next to it or its face toward us is open (logs above head, exposed ore). */
	private boolean canMineFromHere(Bot bot) {
		LocalPlayer p = bot.player();
		if (!Navigator.settled(p) || !p.isWithinBlockInteractionRange(target, -0.5)) return false;
		BlockPos feet = Navigator.feet(p);
		if (new Goal.Adjacent(target).isEnd(feet.getX(), feet.getY(), feet.getZ())) return true;
		ClientLevel level = bot.mc().level;
		BlockPos front = target.relative(bot.controls().faceToward(target));
		return level.getBlockState(front).getCollisionShape(level, front).isEmpty();
	}
}
