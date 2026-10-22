package com.autocraft.behavior;

import com.autocraft.Bot;
import com.autocraft.Chat;
import com.autocraft.ai.Features;
import com.autocraft.combat.Threats;
import com.autocraft.control.InvUtil;
import com.autocraft.craft.Planner;
import com.autocraft.craft.Planner.Step;
import com.autocraft.craft.Workshop;
import com.autocraft.goal.Requirement;
import com.autocraft.path.Goal;
import com.autocraft.path.Navigator;
import com.autocraft.travel.ElytraTravel;
import com.autocraft.world.BlockUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Keeps the bot equipped: the pickaxe tier the current target needs (wood → stone → iron → diamond),
 * a stone pickaxe for any underground/nether work, the Nether kit (flint and steel, gold boots, iron
 * pickaxe, building blocks), plus a sword/shield/boat when the materials are already on hand.
 * Raw materials are requested from the collector as a GEAR need; crafting and smelting run here.
 */
public final class GearBehavior implements Behavior {
	private static final int PLAN_EVERY = 20;
	private static final double HOSTILE_RADIUS = 8;
	private static final List<Item> PICKAXES = List.of(Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE);
	private static final int NETHER_BLOCKS = 32;
	private static final int WATER_SEARCH_EVERY = 100;
	/** Plain rockets to keep on hand when carrying an elytra. */
	private static final int ROCKET_STOCK = 32;

	/**
	 * Something to have. Required wants may gather/smelt; optional ones only run when the very next step
	 * crafts the final item (so they never eat job materials or send the bot off gathering).
	 */
	private record Want(Function<Planner, Step> plan, boolean required, Predicate<Item> finalItem) {
		static Want required(Item item) {
			return new Want(pl -> pl.next(item, 1), true, i -> i == item);
		}

		static Want optional(Item item) {
			return new Want(pl -> pl.next(item, 1), false, i -> i == item);
		}
	}

	private final Workshop workshop = new Workshop();
	private int sincePlan = PLAN_EVERY;
	private int failures;
	private @Nullable BlockPos water;
	private int waterTicks;
	private int sinceWaterSearch = WATER_SEARCH_EVERY;

	@Override
	public String name() {
		return "gear";
	}

	@Override
	public void reset() {
		workshop.reset();
		sincePlan = PLAN_EVERY;
		failures = 0;
	}

	@Override
	public boolean tick(Bot bot) {
		if (!bot.hasWork()) return false;
		LocalPlayer p = bot.player();
		switch (workshop.tick(bot)) {
			case RUNNING -> {
				return true;
			}
			case FAILED -> {
				onFailure();
				sincePlan = PLAN_EVERY;
				return true;
			}
			case DONE -> sincePlan = PLAN_EVERY; // plan the next step right away
			case IDLE -> {
			}
		}
		if (equip(bot, p)) return true;
		if (fillBucket(bot, p)) return true;
		if (++sincePlan < PLAN_EVERY) return false;
		sincePlan = 0;
		if (!Threats.collect(bot, HOSTILE_RADIUS).isEmpty()) return false;

		Step step = plan(bot, p);
		switch (step) {
			case Planner.Craft c -> {
				bot.setNeed(Bot.NeedOwner.GEAR, null);
				workshop.start(c);
				return true;
			}
			case Planner.Smelt s -> {
				bot.setNeed(Bot.NeedOwner.GEAR, null);
				workshop.start(s);
				return true;
			}
			case Planner.Gather g -> {
				if (workshop.packUp(bot)) return true;
				Requirement before = bot.need(Bot.NeedOwner.GEAR);
				if (before == null || !before.name().equals(g.requirement().name())) Chat.info("Gathering " + g.requirement() + " for gear.");
				bot.setNeed(Bot.NeedOwner.GEAR, g.requirement());
				return false;
			}
			case Planner.Unavailable u -> {
				return false; // plan() skips these
			}
			case Planner.Done d -> {
				bot.setNeed(Bot.NeedOwner.GEAR, null);
				return workshop.packUp(bot);
			}
		}
	}

	private void onFailure() {
		failures++;
		Chat.warn("Crafting/smelting failed (" + failures + ")");
		if (failures >= 5) {
			sincePlan = -20 * 30; // back off for a while; the world may change
			failures = 0;
		}
	}

	// ---- planning -------------------------------------------------------------------------------

	private Step plan(Bot bot, LocalPlayer p) {
		Planner planner = new Planner(p, bot.dimension(), stationNearby(bot, Blocks.CRAFTING_TABLE), stationNearby(bot, Blocks.FURNACE),
				bot::isJobItem);
		for (Want w : wants(bot, p, bot.targetForGear())) {
			Step s = w.plan.apply(planner);
			if (s instanceof Planner.Done || s instanceof Planner.Unavailable) continue;
			if (!w.required && !(s instanceof Planner.Craft c && w.finalItem.test(c.recipe().output()))) continue;
			if (s instanceof Planner.Gather g) s = toolFirst(planner, p, g);
			return s;
		}
		return new Planner.Done();
	}

	private static boolean stationNearby(Bot bot, Block block) {
		return !bot.scanner().findBlocks(s -> s.is(block), 1, 1).isEmpty();
	}

	private static List<Want> wants(Bot bot, LocalPlayer p, @Nullable Requirement target) {
		List<Want> wants = new ArrayList<>();
		if (target != null) {
			Item tool = requiredPickaxe(target);
			if (tool != null && !canHarvestAny(p, target)) wants.add(Want.required(tool));
			boolean deep = target.strategy() == Requirement.Strategy.UNDERGROUND || target.dimension() == Requirement.Dim.NETHER;
			if (deep && !InvUtil.canHarvest(p, Blocks.IRON_ORE.defaultBlockState())) wants.add(Want.required(Items.STONE_PICKAXE));
		}
		if (netherTripAhead(bot, p)) addNetherKit(wants, p);
		double weapon = Features.bestWeaponDamage(p);
		if (weapon < 5) wants.add(Want.optional(Items.STONE_SWORD));
		if (weapon < 6) wants.add(Want.optional(Items.IRON_SWORD));
		if (InvUtil.count(p, s -> s.is(Items.SHIELD)) == 0) wants.add(Want.optional(Items.SHIELD));
		wants.add(new Want(GearBehavior::bucket, false, i -> i == Items.BUCKET));
		wants.add(new Want(Planner::boat, false, item -> item.getDefaultInstance().is(ItemTags.BOATS)));
		if (InvUtil.find(p, ElytraTravel::isElytra) >= 0 && InvUtil.count(p, ElytraTravel::isPlainRocket) < ROCKET_STOCK) {
			int rockets = InvUtil.count(p, s -> s.is(Items.FIREWORK_ROCKET));
			// one batch (3 rockets) per plan from carried paper/sugar cane + gunpowder
			wants.add(new Want(pl -> pl.next(Items.FIREWORK_ROCKET, rockets + 3), false, i -> i == Items.FIREWORK_ROCKET || i == Items.PAPER));
		}
		return wants;
	}

	private static void addNetherKit(List<Want> wants, LocalPlayer p) {
		if (!InvUtil.canHarvest(p, Blocks.GOLD_ORE.defaultBlockState())) wants.add(Want.required(Items.IRON_PICKAXE));
		wants.add(Want.required(Items.FLINT_AND_STEEL));
		wants.add(new Want(GearBehavior::bucket, true, i -> i == Items.BUCKET)); // obsidian casting, fall clutches
		if (!PiglinAi.isWearingSafeArmor(p)) wants.add(Want.required(Items.GOLDEN_BOOTS));
		if (InvUtil.count(p, InvUtil::isThrowaway) < NETHER_BLOCKS) {
			Requirement blocks = new Requirement("building blocks", InvUtil::isThrowaway,
					Set.of(Blocks.STONE, Blocks.COBBLESTONE, Blocks.DEEPSLATE, Blocks.COBBLED_DEEPSLATE, Blocks.DIRT, Blocks.GRASS_BLOCK),
					NETHER_BLOCKS, Requirement.Strategy.ANYWHERE, 0, Requirement.Dim.OVERWORLD);
			wants.add(new Want(pl -> new Planner.Gather(blocks), true, i -> false));
		}
	}

	/** Before gathering something we can't harvest yet, plan the pickaxe for it (recursively). */
	private static Step toolFirst(Planner planner, LocalPlayer p, Planner.Gather gather) {
		Requirement req = gather.requirement();
		for (int depth = 0; depth <= PICKAXES.size(); depth++) {
			Item tool = requiredPickaxe(req);
			if (tool == null || canHarvestAny(p, req)) return new Planner.Gather(req);
			Step s = planner.next(tool, 1);
			if (!(s instanceof Planner.Gather g)) return s;
			req = g.requirement();
		}
		return new Planner.Gather(req);
	}

	/** Cheapest pickaxe that gets drops from at least one source, or null if no tool is needed. */
	private static @Nullable Item requiredPickaxe(Requirement r) {
		for (Block b : r.sources()) {
			if (!b.defaultBlockState().requiresCorrectToolForDrops()) return null;
		}
		for (Item pick : PICKAXES) {
			ItemStack stack = new ItemStack(pick);
			for (Block b : r.sources()) {
				if (stack.isCorrectToolForDrops(b.defaultBlockState())) return pick;
			}
		}
		return null;
	}

	private static boolean canHarvestAny(LocalPlayer p, Requirement r) {
		for (Block b : r.sources()) {
			if (InvUtil.canHarvest(p, b.defaultBlockState())) return true;
		}
		return false;
	}

	/** The job's next requirement is in the Nether and we're still in the Overworld. */
	private static boolean netherTripAhead(Bot bot, LocalPlayer p) {
		Requirement next = bot.job() == null ? null : bot.job().nextUnmet(p);
		return next != null && next.dimension() == Requirement.Dim.NETHER && bot.dimension() == Requirement.Dim.OVERWORLD;
	}

	/** A bucket counts whether it's empty or already holds water. */
	private static Step bucket(Planner planner) {
		return planner.hasAny(s -> s.is(Items.BUCKET) || s.is(Items.WATER_BUCKET)) ? new Planner.Done() : planner.next(Items.BUCKET, 1);
	}

	/** Overworld: carry water, not an empty bucket — walk to the nearest water source and scoop it. */
	private boolean fillBucket(Bot bot, LocalPlayer p) {
		if (bot.dimension() != Requirement.Dim.OVERWORLD || InvUtil.count(p, s -> s.is(Items.WATER_BUCKET)) > 0
				|| InvUtil.count(p, s -> s.is(Items.BUCKET)) == 0) {
			water = null;
			return false;
		}
		if (water == null) {
			if (++sinceWaterSearch < WATER_SEARCH_EVERY) return false;
			sinceWaterSearch = 0;
			List<BlockPos> found = bot.scanner().findBlocks(s -> BlockUtil.isWater(s) && s.getFluidState().isSource(), 2, 1);
			if (found.isEmpty()) return false;
			water = found.getFirst();
			waterTicks = 0;
		}
		if (++waterTicks > 20 * 30 || !bot.mc().level.getFluidState(water).isSource()) {
			water = null;
			return false;
		}
		bot.setStatus("filling the bucket with water");
		if (Navigator.settled(p) && p.isWithinBlockInteractionRange(water, -0.5)) {
			bot.nav().stop();
			if (!InvUtil.select(p, s -> s.is(Items.BUCKET))) return false;
			bot.controls().lookAt(Vec3.atCenterOf(water).add(0, 0.4, 0));
			bot.controls().useItem(InteractionHand.MAIN_HAND);
			water = null;
			return true;
		}
		bot.nav().setGoal(new Goal.Near(water, 2));
		if (bot.nav().tick() == Navigator.Status.FAILED) {
			bot.nav().stop();
			water = null;
			return false;
		}
		return true;
	}

	// ---- equipping ------------------------------------------------------------------------------

	/** Shield to the offhand, better armor on (gold stays on for the Nether). One action per tick. */
	private static boolean equip(Bot bot, LocalPlayer p) {
		if (!p.onGround() || !InvUtil.inventoryMenuActive(p) || p.isUsingItem()) return false; // never swap armor mid-air
		if (p.getOffhandItem().isEmpty()) {
			int shield = InvUtil.find(p, s -> s.is(Items.SHIELD));
			if (shield >= 0) {
				InvUtil.moveToOffhand(p, shield);
				return true;
			}
		}
		boolean nether = bot.dimension() == Requirement.Dim.NETHER || netherTripAhead(bot, p);
		for (int i = 0; i < InvUtil.MAIN_SIZE; i++) {
			ItemStack s = p.getInventory().getItem(i);
			Equippable eq = s.get(DataComponents.EQUIPPABLE);
			if (eq == null || eq.slot().getType() != EquipmentSlot.Type.HUMANOID_ARMOR) continue;
			EquipmentSlot slot = eq.slot();
			ItemStack worn = p.getItemBySlot(slot);
			if (nether && worn.is(ItemTags.PIGLIN_SAFE_ARMOR)) continue; // never take gold off near piglins
			boolean neededGold = nether && s.is(ItemTags.PIGLIN_SAFE_ARMOR) && !PiglinAi.isWearingSafeArmor(p);
			if (!neededGold && armorValue(s, slot) <= armorValue(worn, slot)) continue;
			int from = InvUtil.menuSlot(p.inventoryMenu, p, i);
			if (worn.isEmpty()) {
				InvUtil.quickMove(p, from);
			} else {
				int armorSlot = armorMenuSlot(slot);
				InvUtil.click(p, from, 0, ContainerInput.PICKUP);
				InvUtil.click(p, armorSlot, 0, ContainerInput.PICKUP);
				InvUtil.click(p, from, 0, ContainerInput.PICKUP);
			}
			bot.setStatus("equipping " + s.getHoverName().getString());
			return true;
		}
		return false;
	}

	/** InventoryMenu armor slots: 5 head, 6 chest, 7 legs, 8 feet. */
	private static int armorMenuSlot(EquipmentSlot slot) {
		return switch (slot) {
			case HEAD -> 5;
			case CHEST -> 6;
			case LEGS -> 7;
			default -> 8;
		};
	}

	private static double armorValue(ItemStack s, EquipmentSlot slot) {
		if (s.isEmpty()) return 0;
		ItemAttributeModifiers mods = s.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
		return mods.compute(Attributes.ARMOR, 0, slot) + mods.compute(Attributes.ARMOR_TOUGHNESS, 0, slot) * 0.5;
	}
}
