package com.autocraft.behavior;

import com.autocraft.Bot;
import com.autocraft.control.InvUtil;
import com.autocraft.path.Navigator;
import com.autocraft.world.BlockUtil;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Highest priority: get out of lava. Swim up and toward the nearest block we can stand on (or back to
 * the last safe spot); drink fire resistance if the way out is far. Runs before every other behavior.
 */
public final class LavaReflex implements Behavior {
	private static final int SEARCH = 4;
	/** Escape distance beyond which drinking fire resistance first is worth the 1.6 s it takes. */
	private static final double DRINK_IF_FARTHER = 2.5;

	private @Nullable BlockPos lastSafe;

	@Override
	public String name() {
		return "lava-escape";
	}

	@Override
	public void reset() {
		lastSafe = null;
	}

	@Override
	public boolean tick(Bot bot) {
		LocalPlayer p = bot.player();
		if (!p.isInLava()) {
			if (p.onGround() && !p.isOnFire()) lastSafe = Navigator.feet(p);
			return false;
		}
		if (!InvUtil.inventoryMenuActive(p)) p.closeContainer();
		bot.nav().stop();
		BlockPos exit = nearestStandable(bot, Navigator.feet(p));
		if (exit == null) exit = lastSafe;
		Vec3 target = exit == null ? null : Vec3.atBottomCenterOf(exit);
		double distance = target == null ? Double.MAX_VALUE : Math.sqrt(p.distanceToSqr(target.x, p.getY(), target.z));

		if (!p.hasEffect(MobEffects.FIRE_RESISTANCE) && distance > DRINK_IF_FARTHER && drinkFireResistance(bot, p)) {
			bot.setStatus("drinking fire resistance in lava");
			return true;
		}
		bot.controls().jump(); // rise toward the surface
		if (target != null) {
			bot.controls().faceHorizontally(target);
			bot.controls().forward();
		}
		bot.setStatus("escaping lava" + (exit == null ? "" : " toward " + exit.toShortString()));
		return true;
	}

	/** Closest feet position nearby with solid ground, free of lava and fire. */
	private static @Nullable BlockPos nearestStandable(Bot bot, BlockPos feet) {
		ClientLevel level = bot.mc().level;
		BlockPos best = null;
		double bestScore = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-SEARCH, -1, -SEARCH), feet.offset(SEARCH, 2, SEARCH))) {
			if (!BlockUtil.canStandOn(level, pos.below(), level.getBlockState(pos.below()))) continue;
			if (!BlockUtil.isPassable(level, pos, level.getBlockState(pos))) continue;
			if (!BlockUtil.isPassable(level, pos.above(), level.getBlockState(pos.above()))) continue;
			double dx = pos.getX() - feet.getX(), dz = pos.getZ() - feet.getZ();
			double score = Math.sqrt(dx * dx + dz * dz) + Math.abs(pos.getY() - feet.getY()) * 0.5;
			if (score < bestScore) {
				bestScore = score;
				best = pos.immutable();
			}
		}
		return best;
	}

	private static boolean drinkFireResistance(Bot bot, LocalPlayer p) {
		if (p.isUsingItem() && isFireResistance(p.getUseItem())) {
			bot.controls().holdUse();
			return true;
		}
		if (!InvUtil.select(p, LavaReflex::isFireResistance)) return false;
		bot.controls().face(p.getYRot(), -90.0F);
		bot.controls().useItem(InteractionHand.MAIN_HAND);
		bot.controls().holdUse();
		return true;
	}

	private static boolean isFireResistance(ItemStack s) {
		PotionContents potion = s.get(DataComponents.POTION_CONTENTS);
		if (potion == null) return false;
		for (MobEffectInstance e : potion.getAllEffects()) {
			if (e.is(MobEffects.FIRE_RESISTANCE)) return true;
		}
		return false;
	}
}
