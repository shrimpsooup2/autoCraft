package com.autocraft.behavior;

import com.autocraft.Bot;
import com.autocraft.combat.Threats;
import com.autocraft.control.InvUtil;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Eat when hungry (or hurt and not full) and it's safe: no mob within reach of us soon, no archer with a line
 * of sight. At low health the margin is bigger, but only for mobs that can actually get to us (aggressive or in
 * sight) — a zombie behind a wall must not keep a hurt bot from eating; combat keeps the bot away from the rest
 * while it recovers. Picks the most nutritious safe food; risky foods only when starving; golden apples are kept
 * for emergencies.
 */
public final class EatReflex implements Behavior {
	private static final int START_BELOW = 15;
	private static final int STOP_AT = 19;
	private static final double SAFE_RADIUS = 5;
	/** Hurt: mobs that can reach us must be this far (combat's recovery keeps them farther). */
	private static final double SAFE_RADIUS_HURT = 10;
	private static final double ARCHER_RADIUS = 20;
	private static final Set<Item> RISKY = Set.of(Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO, Items.PUFFERFISH, Items.CHICKEN);
	private static final Set<Item> NEVER = Set.of(Items.SUSPICIOUS_STEW, Items.CHORUS_FRUIT);
	private static final Set<Item> EMERGENCY = Set.of(Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE);

	private boolean eating;

	@Override
	public String name() {
		return "eat";
	}

	@Override
	public void reset() {
		eating = false;
	}

	@Override
	public boolean tick(Bot bot) {
		LocalPlayer p = bot.player();
		int food = p.getFoodData().getFoodLevel();
		boolean hungry = food < START_BELOW || p.getHealth() < 14 && food < 20;
		if (!eating && !hungry) return false;
		if (eating && food >= STOP_AT && p.getHealth() >= 14) eating = false;
		if (!eating && !hungry) return false;
		// in an ancient city eating is a vibration sculk sensors hear: only when it's getting dangerous
		if (bot.quiet() && p.getHealth() > 8 && food > 4) return false;
		double margin = p.getHealth() < 12 ? SAFE_RADIUS_HURT : SAFE_RADIUS;
		for (Mob m : Threats.collect(bot, ARCHER_RADIUS)) {
			double d = m.distanceTo(p);
			boolean canReach = m.isAggressive() || p.hasLineOfSight(m);
			if (d < SAFE_RADIUS || d < margin && canReach || Threats.isRanged(m) && m.isAggressive() && p.hasLineOfSight(m)) {
				eating = false; // fight or flee first; letting go of "use" stops the bite
				return false;
			}
		}
		int best = bestFood(p, food);
		if (best < 0) {
			eating = false;
			return false;
		}
		if (!InvUtil.inventoryMenuActive(p)) p.closeContainer();
		ItemStack chosen = p.getInventory().getItem(best);
		if (!InvUtil.select(p, s -> s == chosen) && !p.isUsingItem()) return false;
		eating = true;
		bot.controls().face(p.getYRot(), -90.0F); // look at the sky so "use" can't hit a block
		if (!p.isUsingItem()) bot.controls().useItem(InteractionHand.MAIN_HAND);
		bot.controls().holdUse();
		bot.setStatus("eating " + chosen.getHoverName().getString());
		return true;
	}

	/** Something safe to eat is carried. */
	static boolean hasFood(LocalPlayer p) {
		return bestFood(p, p.getFoodData().getFoodLevel()) >= 0;
	}

	private static int bestFood(LocalPlayer p, int foodLevel) {
		int best = -1;
		int bestNutrition = 0;
		for (int i = 0; i < InvUtil.MAIN_SIZE; i++) {
			ItemStack s = p.getInventory().getItem(i);
			FoodProperties food = s.get(DataComponents.FOOD);
			if (food == null || NEVER.contains(s.getItem())) continue;
			if (RISKY.contains(s.getItem()) && foodLevel > 4) continue;
			if (EMERGENCY.contains(s.getItem()) && p.getHealth() > 6) continue;
			if (food.nutrition() > bestNutrition) {
				bestNutrition = food.nutrition();
				best = i;
			}
		}
		return best;
	}
}
