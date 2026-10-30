package com.autocraft.behavior;

import com.autocraft.Bot;
import com.autocraft.path.Goal;
import com.autocraft.path.Navigator;
import com.autocraft.world.BlockUtil;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Running out of air: swim straight up when open water leads to the surface; under a ceiling (a flooded cave or
 * tunnel) act early: swim into an air pocket right beside the head, else head for the nearest one for a few seconds,
 * else break the block over the head — water doesn't flow up into it — and come up into that gap. Leaves the
 * moment air is back to a safe level.
 */
public final class DrowningReflex implements Behavior {
	/** Act once air drops below this (full air is 300). */
	private static final int ACT_BELOW = 150;
	/** Under a ceiling there's no swimming up: act this early. */
	private static final int ACT_BELOW_CEILING = 240;
	/** Keep surfacing until air is back to this. */
	private static final int SAFE_AIR = 280;
	private static final int MAX_SWIM_UP = 14;
	private static final int POCKET_SEARCH = 5;
	/** How long walking/swimming to a farther air pocket may take before digging up instead. */
	private static final int POCKET_TICKS = 60;

	private boolean active;
	private @Nullable BlockPos pocket;
	private int activeTicks;

	@Override
	public String name() {
		return "drowning";
	}

	@Override
	public void reset() {
		active = false;
		pocket = null;
		activeTicks = 0;
	}

	@Override
	public boolean tick(Bot bot) {
		LocalPlayer p = bot.player();
		if (p.hasEffect(MobEffects.WATER_BREATHING) || p.hasEffect(MobEffects.CONDUIT_POWER)) return false;
		ClientLevel level = bot.mc().level;
		BlockPos head = BlockPos.containing(p.getEyePosition());
		if (!active && (!p.isUnderWater() || p.getAirSupply() >= (openWaterUp(level, head) ? ACT_BELOW : ACT_BELOW_CEILING))) return false;
		if (!p.isUnderWater() && p.getAirSupply() >= SAFE_AIR || !p.isInWater() && !p.isUnderWater()) {
			reset();
			bot.nav().stop();
			return false;
		}
		if (!active) bot.noteAirScare(); // the collector gives up on a source whose way keeps doing this
		active = true;
		activeTicks++;
		bot.setStatus("out of air, surfacing");

		if (openWaterUp(level, head)) {
			bot.nav().stop();
			bot.controls().face(p.getYRot(), -60.0F);
			bot.controls().jump();
			bot.controls().forward();
			return true;
		}
		if (pocket == null || !level.getBlockState(pocket).isAir()) pocket = nearestAir(level, head);
		if (pocket != null && activeTicks <= POCKET_TICKS && head.distManhattan(pocket) <= 2) {
			// right beside the head: swim into it (the pathfinder can't plan a half-block bob into a pocket)
			bot.nav().stop();
			bot.controls().lookAt(Vec3.atCenterOf(pocket));
			bot.controls().forward();
			bot.controls().jump();
			return true;
		}
		if (pocket != null && activeTicks <= POCKET_TICKS) {
			bot.nav().setGoal(new Goal.Near(pocket.below(), 0));
			if (bot.nav().tick() != Navigator.Status.FAILED) {
				bot.controls().jump();
				return true;
			}
			activeTicks = POCKET_TICKS + 1; // unreachable: dig up from here
		}
		bot.nav().stop();
		BlockPos above = head.above();
		BlockState s = level.getBlockState(above);
		if (BlockUtil.isBreakable(level, above, s)) {
			// stay on the floor while digging (mining afloat is five times slower again), come up once it's open
			bot.controls().mine(above);
			return true;
		}
		bot.controls().jump();
		return true;
	}

	/** Only water (or nothing) between the head and air within {@link #MAX_SWIM_UP} blocks straight up. */
	private static boolean openWaterUp(ClientLevel level, BlockPos head) {
		for (int dy = 1; dy <= MAX_SWIM_UP; dy++) {
			BlockState s = level.getBlockState(head.above(dy));
			if (s.isAir()) return true;
			if (!BlockUtil.isWater(s) || !s.getCollisionShape(level, head.above(dy)).isEmpty()) return false;
		}
		return false;
	}

	/** Nearest air cell with water or air under it (somewhere the head can go), preferring higher ones. */
	private static @Nullable BlockPos nearestAir(ClientLevel level, BlockPos head) {
		BlockPos best = null;
		double bestScore = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(head.offset(-POCKET_SEARCH, -1, -POCKET_SEARCH), head.offset(POCKET_SEARCH, POCKET_SEARCH, POCKET_SEARCH))) {
			if (!level.getBlockState(pos).isAir()) continue;
			BlockState below = level.getBlockState(pos.below());
			if (!below.isAir() && !BlockUtil.isWater(below)) continue;
			double score = pos.distSqr(head) - (pos.getY() - head.getY()) * 2;
			if (score < bestScore) {
				bestScore = score;
				best = pos.immutable();
			}
		}
		return best;
	}
}
