package com.autocraft.behavior;

import com.autocraft.Bot;
import com.autocraft.Chat;
import com.autocraft.combat.Melee;
import com.autocraft.control.InvUtil;
import com.autocraft.craft.Planner;
import com.autocraft.craft.Stations;
import com.autocraft.craft.Workshop;
import com.autocraft.goal.Requirement;
import com.autocraft.loot.LootBook;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.cow.AbstractCow;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.rabbit.Rabbit;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * Keeps a food supply without wandering off for it: hunts the nearest adult cow, pig, sheep, chicken or rabbit
 * only when actually getting hungry with little food carried (farther the hungrier), or when an animal is right
 * next to us and the supply is low. Named animals (pets, farm favourites) are left alone. The meat is picked up like
 * any drop ({@link PickupBehavior}).
 * <p>
 * No animals live in the Nether: short of food before a trip there, or running low over there, food becomes a
 * need ({@link Bot.NeedOwner#FOOD}) the collector hunts for in the Overworld — the portal behavior takes us home
 * for it — until a trip's worth is carried. (Out of food in the Nether, health stopped coming back and the bot went
 * looking for a bastion with half its hearts.)
 */
public final class FoodBehavior implements Behavior {
	/** Hunger points of food carried below which hunting is considered (≈ 4 steaks). */
	private static final int LOW_STOCK = 24;
	/** Food (hunger points) a Nether trip sets out with. */
	private static final int TRIP_STOCK = LOW_STOCK * 2;
	/** In the Nether with less than this: home for food, once what's gatherable over there is done. */
	private static final int NETHER_MIN = 12;
	/** Hunger bar level (out of 20) at which a low supply is worth a short detour. */
	private static final int HUNGRY = 14;
	/** Hunger bar level at which food matters more than the job. */
	private static final int STARVING = 8;
	private static final double OPPORTUNITY_RADIUS = 6;
	private static final double HUNGRY_RADIUS = 20;
	private static final double STARVING_RADIUS = 48;
	private static final int LOOK_EVERY = 100;
	private static final int HUNT_TIMEOUT = 20 * 25;
	private static final Set<EntityType<?>> LIVESTOCK = Set.of(EntityTypes.COW, EntityTypes.MOOSHROOM, EntityTypes.PIG, EntityTypes.SHEEP,
			EntityTypes.CHICKEN, EntityTypes.RABBIT);
	/** Raw food a furnace makes two to three times as filling. */
	private static final Map<Item, Item> COOKED = Map.of(Items.BEEF, Items.COOKED_BEEF, Items.PORKCHOP, Items.COOKED_PORKCHOP,
			Items.MUTTON, Items.COOKED_MUTTON, Items.CHICKEN, Items.COOKED_CHICKEN, Items.RABBIT, Items.COOKED_RABBIT, Items.COD, Items.COOKED_COD,
			Items.SALMON, Items.COOKED_SALMON, Items.POTATO, Items.BAKED_POTATO);
	/** Raw pieces worth a furnace session. */
	private static final int COOK_BATCH = 4;

	private final Workshop workshop = new Workshop();
	private @Nullable Animal prey;
	private final BlockPos[] chaseAt = new BlockPos[1];
	private int huntTicks;
	private int sinceLook = LOOK_EVERY;
	/** A trip's worth of food was gathered and the supply hasn't run low since. */
	private boolean stocked;

	@Override
	public String name() {
		return "food";
	}

	@Override
	public void reset() {
		prey = null;
		workshop.reset();
	}

	@Override
	public boolean tick(Bot bot) {
		if (!bot.hasWork()) return false;
		LocalPlayer p = bot.player();
		updateNeed(bot, p);
		if (bot.dimension() != Requirement.Dim.OVERWORLD) return false;
		if (workshop.tick(bot) == Workshop.Status.RUNNING || prey == null && cook(bot, p)) return true;
		if (prey == null) {
			if (++sinceLook < LOOK_EVERY) return false;
			sinceLook = 0;
			double radius = huntRadius(p);
			if (radius <= 0) return false;
			prey = nearestPrey(bot, radius);
			if (prey == null) return false;
			huntTicks = 0;
			chaseAt[0] = null;
		}
		if (!prey.isAlive()) {
			prey = null;
			return false;
		}
		if (++huntTicks > HUNT_TIMEOUT) {
			prey = null;
			bot.nav().stop();
			return false;
		}
		bot.setStatus("hunting " + prey.getType().getDescription().getString());
		if (Melee.strike(bot, prey)) return true;
		if (!Melee.approach(bot, prey, chaseAt)) {
			prey = null;
			bot.nav().stop();
		}
		return true;
	}

	/**
	 * Stocking up for the Nether, or running low: a batch of raw meat carried and a furnace with fuel at hand (or one
	 * craftable from what's carried) gets cooked — steak is worth almost three raw beef. Never sends anyone off for
	 * fuel, and with food enough it leaves the fuel alone (it cooked ten porkchops on the coal mined for a job).
	 */
	private boolean cook(Bot bot, LocalPlayer p) {
		if (bot.need(Bot.NeedOwner.FOOD) == null && foodPoints(p) >= LOW_STOCK) return false;
		for (Map.Entry<Item, Item> e : COOKED.entrySet()) {
			int raw = InvUtil.count(p, s -> s.is(e.getKey()));
			if (raw < COOK_BATCH) continue;
			Planner.Step s = Stations.planner(bot).next(e.getValue(), InvUtil.count(p, x -> x.is(e.getValue())) + raw);
			if (s instanceof Planner.Smelt || s instanceof Planner.Craft) { // a furnace to craft first counts too
				workshop.start(s);
				bot.setStatus("cooking " + e.getKey().getDefaultInstance().getHoverName().getString());
				return true;
			}
		}
		return false;
	}

	/**
	 * Set or clear the food need (see the class comment); it lasts until a trip's worth is carried. Once stocked, what
	 * gets eaten on the way doesn't send us hunting again until the supply is actually low (a fight on the way to the
	 * portal ate 10 points and the bot turned back from 60 blocks underground to hunt).
	 */
	private void updateNeed(Bot bot, LocalPlayer p) {
		int stock = foodPoints(p);
		Requirement current = bot.need(Bot.NeedOwner.FOOD);
		if (current != null) {
			if (stock >= TRIP_STOCK) {
				bot.setNeed(Bot.NeedOwner.FOOD, null);
				stocked = true;
			}
			return;
		}
		if (stock < LOW_STOCK) stocked = false;
		boolean inNether = bot.dimension() == Requirement.Dim.NETHER;
		boolean tripAhead = !inNether && (bot.workDimension() == Requirement.Dim.NETHER
				|| bot.recovery() != null && bot.recovery().dim() == Requirement.Dim.NETHER);
		if (inNether ? stock >= NETHER_MIN : !tripAhead || stocked || stock >= TRIP_STOCK) return;
		Chat.info("Food for the Nether: " + stock + " hunger points carried, hunting in the Overworld for " + TRIP_STOCK + ".");
		int items = InvUtil.count(p, FoodBehavior::isFood) + (TRIP_STOCK - stock + 2) / 3; // raw meat: about 3 points each
		bot.setNeed(Bot.NeedOwner.FOOD, new Requirement("food", FoodBehavior::isFood, Set.of(), items, Requirement.Strategy.SURFACE, 0,
				Requirement.Dim.OVERWORLD, Set.of(), null, LootBook.ToolUse.ANY, LIVESTOCK));
	}

	/** How far an animal may be for a hunt to be worth it right now (0 = don't hunt). */
	private static double huntRadius(LocalPlayer p) {
		int stock = foodPoints(p);
		int hunger = p.getFoodData().getFoodLevel();
		if (hunger <= STARVING && stock < 8) return STARVING_RADIUS;
		if (hunger <= HUNGRY && stock < LOW_STOCK) return HUNGRY_RADIUS;
		// hurt, and nothing to eat to get the bar up to 18 where health comes back: food is the way to heal
		if (p.getHealth() < 14 && hunger < 18 && stock < 8) return HUNGRY_RADIUS;
		return stock < LOW_STOCK ? OPPORTUNITY_RADIUS : 0;
	}

	private static @Nullable Animal nearestPrey(Bot bot, double radius) {
		List<Entity> animals = bot.scanner().entities(radius, e -> e instanceof Animal a && !a.isBaby() && !a.hasCustomName()
				&& (a instanceof AbstractCow || a instanceof Pig || a instanceof Sheep || a instanceof Chicken || a instanceof Rabbit));
		return (Animal) animals.stream().min(Comparator.comparingDouble(e -> e.distanceToSqr(bot.player()))).orElse(null);
	}

	/** Food worth carrying: anything edible but the poisonous kinds. */
	static boolean isFood(ItemStack s) {
		return s.has(DataComponents.FOOD) && !s.is(Items.SPIDER_EYE) && !s.is(Items.POISONOUS_POTATO) && !s.is(Items.PUFFERFISH)
				&& !s.is(Items.SUSPICIOUS_STEW) && !s.is(Items.CHORUS_FRUIT);
	}

	/** Hunger points the carried food would restore. */
	static int foodPoints(LocalPlayer p) {
		int total = 0;
		for (int i = 0; i < InvUtil.MAIN_SIZE; i++) {
			ItemStack s = p.getInventory().getItem(i);
			FoodProperties food = s.get(DataComponents.FOOD);
			if (food != null && isFood(s)) total += food.nutrition() * s.getCount();
		}
		return total;
	}
}
