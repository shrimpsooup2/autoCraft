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
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Water-bucket clutch: when a fall would hurt, look down and pour water on the landing block just
 * before impact, then scoop the water back up after landing. Not in the Nether (water evaporates).
 */
public final class FallReflex implements Behavior {
	/** Fall damage starts above 3 blocks; leave a little margin. */
	private static final double SAFE_FALL = 3.5;
	/** Eye-to-ground distance at which the bucket can reach the landing block. */
	private static final double POUR_DISTANCE = 4.0;
	private static final int SCOOP_TICKS = 30;

	private @Nullable BlockPos poured;
	private int scoopTicks;

	@Override
	public String name() {
		return "fall-clutch";
	}

	@Override
	public void reset() {
		poured = null;
	}

	@Override
	public boolean tick(Bot bot) {
		LocalPlayer p = bot.player();
		if (poured != null && (p.onGround() || p.isInWater())) return scoop(bot, p);
		if (p.onGround() || p.isInWater() || p.isPassenger() || p.getAbilities().flying || p.isFallFlying()) return false;
		if (p.getDeltaMovement().y > -0.2 || bot.dimension() == Requirement.Dim.NETHER) return false;

		ClientLevel level = bot.mc().level;
		double groundTop = Double.NaN;
		BlockPos landing = null;
		for (BlockPos pos = p.blockPosition(); pos.getY() > level.getMinY() && p.getY() - pos.getY() < 64; pos = pos.below()) {
			BlockState s = level.getBlockState(pos);
			if (BlockUtil.isWater(s)) return false; // landing in water is already safe
			VoxelShape shape = s.getCollisionShape(level, pos);
			if (!shape.isEmpty()) {
				groundTop = pos.getY() + shape.max(Direction.Axis.Y);
				landing = pos;
				break;
			}
		}
		if (landing == null) return false;
		double drop = p.getY() - groundTop;
		if (p.fallDistance + drop <= SAFE_FALL) return false;
		if (!InvUtil.select(p, s -> s.is(Items.WATER_BUCKET))) return false;

		bot.controls().face(p.getYRot(), 90.0F);
		if (drop <= POUR_DISTANCE && poured == null) {
			bot.controls().useItem(InteractionHand.MAIN_HAND);
			poured = landing.above();
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
