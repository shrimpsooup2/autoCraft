package com.autocraft.behavior;

import com.autocraft.Bot;
import com.autocraft.control.InvUtil;
import com.autocraft.goal.Requirement;
import com.autocraft.path.Goal;
import com.autocraft.path.Navigator;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.cow.AbstractCow;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.rabbit.Rabbit;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Keeps a food supply: when the carried food is low (Overworld), hunt the nearest adult cow, pig, sheep,
 * chicken or rabbit, then pick up the meat. Named animals (pets, farm favourites) are left alone.
 */
public final class FoodBehavior implements Behavior {
	/** Hunger points of food carried below which we go hunting (≈ 4 steaks). */
	private static final int LOW_STOCK = 24;
	private static final double HUNT_RADIUS = 32;
	private static final int LOOK_EVERY = 100;
	private static final int HUNT_TIMEOUT = 20 * 40;
	private static final int LOOT_TIMEOUT = 20 * 5;

	private @Nullable Animal prey;
	private @Nullable BlockPos chaseAt;
	private int huntTicks;
	private int lootTicks = -1;
	private int sinceLook = LOOK_EVERY;

	@Override
	public String name() {
		return "food";
	}

	@Override
	public void reset() {
		prey = null;
		lootTicks = -1;
	}

	@Override
	public boolean tick(Bot bot) {
		if (!bot.hasWork() || bot.dimension() != Requirement.Dim.OVERWORLD) return false;
		LocalPlayer p = bot.player();
		if (lootTicks >= 0) return loot(bot);
		if (prey == null) {
			if (foodPoints(p) >= wantedStock(bot) || ++sinceLook < LOOK_EVERY) return false;
			sinceLook = 0;
			prey = nearestPrey(bot);
			if (prey == null) return false;
			huntTicks = 0;
			chaseAt = null;
		}
		if (!prey.isAlive()) {
			prey = null;
			lootTicks = 0;
			return true;
		}
		if (++huntTicks > HUNT_TIMEOUT) {
			prey = null;
			bot.nav().stop();
			return false;
		}
		bot.setStatus("hunting " + prey.getType().getDescription().getString());
		CombatBehavior.selectWeapon(p);
		if (p.isWithinEntityInteractionRange(prey, 0) && p.hasLineOfSight(prey)) {
			bot.nav().stop();
			bot.controls().lookAt(prey.getBoundingBox().getCenter());
			if (p.getAttackStrengthScale(0.5F) >= 0.9F) bot.controls().attack(prey);
			return true;
		}
		BlockPos at = prey.blockPosition();
		if (chaseAt == null || chaseAt.distSqr(at) > 4) chaseAt = at;
		bot.nav().setGoal(new Goal.Near(chaseAt, 1));
		if (bot.nav().tick() == Navigator.Status.FAILED) {
			prey = null;
			bot.nav().stop();
		}
		return true;
	}

	/** No animals to hunt in the Nether: take twice the usual supply when the next work is there. */
	private static int wantedStock(Bot bot) {
		return bot.workDimension() == Requirement.Dim.NETHER ? LOW_STOCK * 2 : LOW_STOCK;
	}

	/** Walk over the meat the kill dropped. */
	private boolean loot(Bot bot) {
		lootTicks++;
		List<ItemEntity> meat = bot.scanner().items(10, s -> s.has(DataComponents.FOOD));
		if (meat.isEmpty() && lootTicks < 5) return true; // drops appear a tick or two after the kill
		if (meat.isEmpty() || lootTicks > LOOT_TIMEOUT) {
			lootTicks = -1;
			bot.nav().stop();
			return false;
		}
		bot.setStatus("picking up food");
		bot.nav().setGoal(new Goal.Near(meat.getFirst().blockPosition(), 0));
		if (bot.nav().tick() == Navigator.Status.FAILED) {
			lootTicks = -1;
			bot.nav().stop();
		}
		return true;
	}

	private static @Nullable Animal nearestPrey(Bot bot) {
		List<Entity> animals = bot.scanner().entities(HUNT_RADIUS, e -> e instanceof Animal a && !a.isBaby() && !a.hasCustomName()
				&& (a instanceof AbstractCow || a instanceof Pig || a instanceof Sheep || a instanceof Chicken || a instanceof Rabbit));
		return (Animal) animals.stream().min(Comparator.comparingDouble(e -> e.distanceToSqr(bot.player()))).orElse(null);
	}

	/** Hunger points the carried food would restore. */
	static int foodPoints(LocalPlayer p) {
		int total = 0;
		for (int i = 0; i < InvUtil.MAIN_SIZE; i++) {
			ItemStack s = p.getInventory().getItem(i);
			FoodProperties food = s.get(DataComponents.FOOD);
			if (food != null) total += food.nutrition() * s.getCount();
		}
		return total;
	}
}
