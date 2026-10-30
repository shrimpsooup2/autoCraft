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
 * while it recovers. Picks the most nutritious safe food; food that may give Hunger (rotten flesh, raw chicken)
 * also when hurt — health only comes back with a nearly full food bar — and poisonous food only when starving;
 * golden apples are kept for emergencies.
 */
public final class EatReflex implements Behavior {
	private static final int START_BELOW = 15;
	private static final int STOP_AT = 19;
	/** Health below which a not quite full food bar is topped up (health only regenerates near full)… */
	private static final float HURT_BELOW = 14;
	/** …and in guarded work ({@link Bot#guardedWork}). */
	private static final float GUARDED_HURT_BELOW = 18;
	private static final double SAFE_RADIUS = 5;
	/** Hurt: mobs that can reach us must be this far (combat's recovery keeps them farther). */
	private static final double SAFE_RADIUS_HURT = 10;
	private static final double ARCHER_RADIUS = 20;
	/** Hunger effect, maybe: still worth eating to get health back (it only regenerates with a full food bar). */
	private static final Set<Item> HUNGER_RISK = Set.of(Items.ROTTEN_FLESH, Items.CHICKEN);
	private static final Set<Item> POISON = Set.of(Items.SPIDER_EYE, Items.POISONOUS_POTATO, Items.PUFFERFISH);
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
		// in guarded work (a trial chamber's waves, a bastion) every lull tops health up: health only comes back with a
		// nearly full bar, and a trial started at 16 was lost a minute later
		float hurtBelow = bot.guardedWork() != null ? GUARDED_HURT_BELOW : HURT_BELOW;
		boolean hungry = food < START_BELOW || p.getHealth() < hurtBelow && food < 20;
		if (!eating && !hungry) return false;
		if (eating && food >= STOP_AT && p.getHealth() >= hurtBelow) eating = false;
		if (!eating && !hungry) return false;
		// in an ancient city eating is a vibration sculk sensors hear: only when it's getting dangerous
		if (bot.quiet() && p.getHealth() > 8 && food > 4) return false;
		double margin = p.getHealth() < 12 ? SAFE_RADIUS_HURT : SAFE_RADIUS;
		// up a pillar, the zombies around it can't stop a meal — that's what the pillar is for
		boolean onPillar = CombatBehavior.onPillar(p);
		for (Mob m : Threats.collect(bot, ARCHER_RADIUS)) {
			if (onPillar && CombatBehavior.pillarBreaker(m) == null) continue;
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
		bot.setStatus("eating " + chosen.getHoverName().getString());
		// still holding up the shield (combat or the defense reflex raised it): let go of "use" for a tick so it
		// comes down — holding on kept the shield up and the bot "eating" for good
		if (p.isUsingItem() && p.getUsedItemHand() != InteractionHand.MAIN_HAND) return true;
		if (!p.isUsingItem()) bot.controls().useItem(InteractionHand.MAIN_HAND);
		bot.controls().holdUse();
		return true;
	}

	/** Something worth eating right now is carried (by the rules of {@link #bestFood}). */
	public static boolean hasFood(LocalPlayer p) {
		return bestFood(p, p.getFoodData().getFoodLevel()) >= 0;
	}

	private static int bestFood(LocalPlayer p, int foodLevel) {
		int best = -1;
		int bestScore = Integer.MIN_VALUE;
		boolean hurt = p.getHealth() < 14;
		for (int i = 0; i < InvUtil.MAIN_SIZE; i++) {
			ItemStack s = p.getInventory().getItem(i);
			FoodProperties food = s.get(DataComponents.FOOD);
			if (food == null || NEVER.contains(s.getItem())) continue;
			if (POISON.contains(s.getItem()) && foodLevel > 4) continue;
			if (HUNGER_RISK.contains(s.getItem()) && foodLevel > 4 && !hurt) continue;
			if (EMERGENCY.contains(s.getItem()) && p.getHealth() > 6) continue;
			boolean risky = POISON.contains(s.getItem()) || HUNGER_RISK.contains(s.getItem());
			int score = food.nutrition() - (risky ? 100 : 0); // any safe food first
			if (score > bestScore) {
				bestScore = score;
				best = i;
			}
		}
		return best;
	}
}
