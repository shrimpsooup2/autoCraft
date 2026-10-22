package com.autocraft.behavior;

import com.autocraft.Bot;
import com.autocraft.control.InvUtil;
import com.autocraft.goal.Requirement;
import com.autocraft.world.BlockUtil;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Mid-air reactions.
 * <ul>
 * <li>Falling into lava (any height, any dimension): steer toward solid ground, pour water onto the lava
 * (Overworld) or place a block into the lava cell to land on, drink fire resistance as a last resort.</li>
 * <li>A fall that would hurt (Overworld): water-bucket clutch, then scoop the water back up.</li>
 * </ul>
 */
public final class FallReflex implements Behavior {
	/** Fall damage starts above 3 blocks; leave a little margin. */
	private static final double SAFE_FALL = 3.5;
	/** Feet-to-surface distance at which the landing block is in reach of the bucket / a placement. */
	private static final double REACH_DROP = 4.0;
	private static final int SCAN_DEPTH = 64;
	private static final int STEER_RADIUS = 3;
	private static final int SCOOP_TICKS = 30;

	/** What is under us: where we land and whether it is lava. */
	private record Landing(BlockPos pos, double surfaceY, boolean lava) {}

	private @Nullable BlockPos poured;
	private int scoopTicks;
	/** Water poured / block placed for the current lava fall; don't stack a second action on it. */
	private boolean lavaHandled;

	@Override
	public String name() {
		return "fall";
	}

	@Override
	public void reset() {
		poured = null;
		lavaHandled = false;
	}

	@Override
	public boolean tick(Bot bot) {
		LocalPlayer p = bot.player();
		if (poured != null && (p.onGround() || p.isInWater())) return scoop(bot, p);
		if (p.onGround() || p.isInWater() || p.isInLava() || p.isPassenger() || p.getAbilities().flying || p.isFallFlying()) {
			lavaHandled = false;
			return false;
		}
		if (p.getDeltaMovement().y > -0.08) return false;

		Landing landing = landing(bot.mc().level, p);
		if (landing == null) return false;
		double drop = p.getY() - landing.surfaceY;
		if (landing.lava) return avoidLava(bot, p, landing, drop);
		if (p.fallDistance + drop <= SAFE_FALL || bot.dimension() == Requirement.Dim.NETHER) return false;
		return waterClutch(bot, p, landing, drop);
	}

	/** First block below the feet that would stop or swallow us (water landings are safe: null). */
	private static @Nullable Landing landing(ClientLevel level, LocalPlayer p) {
		BlockPos start = p.blockPosition();
		for (int i = 0; i < SCAN_DEPTH; i++) {
			BlockPos pos = start.below(i);
			if (pos.getY() < level.getMinY()) return null;
			BlockState s = level.getBlockState(pos);
			if (BlockUtil.isWater(s)) return null;
			if (BlockUtil.isLava(s)) return new Landing(pos, pos.getY() + 0.9, true);
			VoxelShape shape = s.getCollisionShape(level, pos);
			if (!shape.isEmpty()) return new Landing(pos, pos.getY() + shape.max(Direction.Axis.Y), false);
		}
		return null;
	}

	private boolean avoidLava(Bot bot, LocalPlayer p, Landing landing, double drop) {
		bot.setStatus("falling toward lava!");
		if (!InvUtil.inventoryMenuActive(p)) p.closeContainer();
		steerToSafety(bot, p, landing);
		if (lavaHandled || drop > REACH_DROP) return true; // keep steering until the lava is within reach

		// Overworld: water poured into a lava source replaces it, so we land in water
		if (bot.dimension() == Requirement.Dim.OVERWORLD && InvUtil.select(p, s -> s.is(Items.WATER_BUCKET))) {
			bot.controls().lookAt(Vec3.atCenterOf(landing.pos).add(0, 0.4, 0));
			bot.controls().useItem(InteractionHand.MAIN_HAND);
			lavaHandled = true;
			return true;
		}
		// anywhere: fill the lava cell with a block to land on (needs a solid neighbour to click)
		if (InvUtil.count(p, InvUtil::isThrowaway) > 0 && bot.controls().place(landing.pos, InvUtil::isThrowaway)) {
			lavaHandled = true;
			return true;
		}
		if (!p.hasEffect(MobEffects.FIRE_RESISTANCE)) drinkFireResistance(bot, p);
		return true;
	}

	/** Air control: push toward the closest column within reach whose top is solid ground, not lava. */
	private static void steerToSafety(Bot bot, LocalPlayer p, Landing landing) {
		ClientLevel level = bot.mc().level;
		BlockPos feet = p.blockPosition();
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (int dx = -STEER_RADIUS; dx <= STEER_RADIUS; dx++) {
			for (int dz = -STEER_RADIUS; dz <= STEER_RADIUS; dz++) {
				for (int y = feet.getY(); y >= landing.pos.getY() - 1; y--) {
					BlockPos pos = new BlockPos(feet.getX() + dx, y, feet.getZ() + dz);
					BlockState s = level.getBlockState(pos);
					if (BlockUtil.isPassable(level, pos, s)) continue;
					if (BlockUtil.canStandOn(level, pos, s)) {
						double d = dx * dx + dz * dz;
						if (d < bestDist) {
							bestDist = d;
							best = pos;
						}
					}
					break; // first non-passable block in this column decides it
				}
			}
		}
		if (best == null) return;
		bot.controls().faceHorizontally(Vec3.atCenterOf(best));
		bot.controls().forward();
		bot.controls().sprint();
	}

	private static void drinkFireResistance(Bot bot, LocalPlayer p) {
		if (p.isUsingItem()) {
			bot.controls().holdUse();
			return;
		}
		if (!InvUtil.select(p, LavaReflex::isFireResistance)) return;
		bot.controls().useItem(InteractionHand.MAIN_HAND);
		bot.controls().holdUse();
	}

	private boolean waterClutch(Bot bot, LocalPlayer p, Landing landing, double drop) {
		if (!InvUtil.select(p, s -> s.is(Items.WATER_BUCKET))) return false;
		bot.controls().face(p.getYRot(), 90.0F);
		if (drop <= REACH_DROP && poured == null) {
			bot.controls().useItem(InteractionHand.MAIN_HAND);
			poured = landing.pos.above();
			scoopTicks = 0;
		}
		bot.setStatus("water bucket clutch");
		return true;
	}

	/** After landing, pick the water back up so we keep the bucket and don't flood the area. */
	private boolean scoop(Bot bot, LocalPlayer p) {
		ClientLevel level = bot.mc().level;
		if (++scoopTicks > SCOOP_TICKS || !BlockUtil.isWater(level.getBlockState(poured)) || !level.getFluidState(poured).isSource()) {
			poured = null;
			return false;
		}
		if (scoopTicks < 3) return true; // let the landing settle
		if (!InvUtil.select(p, s -> s.is(Items.BUCKET))) {
			poured = null;
			return false;
		}
		bot.controls().lookAt(poured);
		bot.controls().useItem(InteractionHand.MAIN_HAND);
		return true;
	}
}
