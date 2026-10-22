package com.autocraft.behavior;

import com.autocraft.Bot;
import com.autocraft.Chat;
import com.autocraft.ai.Features;
import com.autocraft.combat.Threats;
import com.autocraft.control.InvUtil;
import com.autocraft.craft.Crafter;
import com.autocraft.craft.Planner;
import com.autocraft.craft.Planner.Step;
import com.autocraft.craft.Smelter;
import com.autocraft.goal.Requirement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
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

	private @Nullable Crafter crafter;
	private @Nullable Smelter smelter;
	private @Nullable Harvest pickup;
	private final List<BlockPos> placed = new ArrayList<>();
	private int sincePlan = PLAN_EVERY;
	private int failures;

	@Override
	public String name() {
		return "gear";
	}

	@Override
	public void reset() {
		crafter = null;
		smelter = null;
		pickup = null;
		placed.clear();
		sincePlan = PLAN_EVERY;
		failures = 0;
	}

	@Override
	public boolean tick(Bot bot) {
		if (bot.job() == null) return false;
		LocalPlayer p = bot.player();
		if (crafter != null) return tickCrafter(bot);
		if (smelter != null) return tickSmelter(bot);
		if (pickup != null) {
			if (pickup.tick(bot) == Harvest.Status.RUNNING) {
				bot.setStatus("picking up crafting station");
				return true;
			}
			pickup = null;
		}
		if (equip(bot, p)) return true;
		if (++sincePlan < PLAN_EVERY) return false;
		sincePlan = 0;
		if (!Threats.collect(bot, HOSTILE_RADIUS).isEmpty()) return false;

		return switch (plan(bot, p)) {
			case Planner.Craft c -> {
				bot.setNeed(Bot.NeedOwner.GEAR, null);
				crafter = new Crafter(c.recipe());
				yield tickCrafter(bot);
			}
			case Planner.Smelt s -> {
				bot.setNeed(Bot.NeedOwner.GEAR, null);
				smelter = new Smelter(s.input(), s.count());
				yield tickSmelter(bot);
			}
			case Planner.Gather g -> {
				if (finishStations(bot, p)) yield true;
				Requirement before = bot.need(Bot.NeedOwner.GEAR);
				if (before == null || !before.name().equals(g.requirement().name())) Chat.info("Gathering " + g.requirement() + " for gear.");
				bot.setNeed(Bot.NeedOwner.GEAR, g.requirement());
				yield false;
			}
			case Planner.Done d -> {
				bot.setNeed(Bot.NeedOwner.GEAR, null);
				yield finishStations(bot, p);
			}
		};
	}

	private boolean tickCrafter(Bot bot) {
		Crafter.Status s = crafter.tick(bot);
		String what = crafter.recipe().output().getDefaultInstance().getHoverName().getString();
		bot.setStatus("crafting " + what);
		if (s == Crafter.Status.RUNNING) return true;
		remember(crafter.placedTable());
		if (s == Crafter.Status.FAILED) onFailure("crafting " + what);
		crafter = null;
		sincePlan = PLAN_EVERY; // plan the next step right away
		return true;
	}

	private boolean tickSmelter(Bot bot) {
		Smelter.Status s = smelter.tick(bot);
		if (s == Smelter.Status.RUNNING) return true;
		remember(smelter.placedFurnace());
		if (s == Smelter.Status.FAILED) onFailure("smelting");
		smelter = null;
		sincePlan = PLAN_EVERY;
		return true;
	}

	private void remember(@Nullable BlockPos pos) {
		if (pos != null && !placed.contains(pos)) placed.add(pos);
	}

	private void onFailure(String what) {
		failures++;
		Chat.warn(what + " failed (" + failures + ")");
		if (failures >= 5) {
			sincePlan = -20 * 30; // back off for a while; the world may change
			failures = 0;
		}
	}

	/** Close any station screen and pick our placed tables/furnaces back up. True while doing so. */
	private boolean finishStations(Bot bot, LocalPlayer p) {
		if (!InvUtil.inventoryMenuActive(p)) p.closeContainer();
		while (!placed.isEmpty()) {
			BlockPos pos = placed.removeFirst();
			BlockState s = bot.mc().level.getBlockState(pos);
			if (s.is(Blocks.CRAFTING_TABLE) || s.is(Blocks.FURNACE)) {
				pickup = new Harvest(pos, st -> st.is(Items.CRAFTING_TABLE) || st.is(Items.FURNACE));
				return true;
			}
		}
		return false;
	}

	// ---- planning -------------------------------------------------------------------------------

	private Step plan(Bot bot, LocalPlayer p) {
		Planner planner = new Planner(p, bot.dimension(), stationNearby(bot, Blocks.CRAFTING_TABLE), stationNearby(bot, Blocks.FURNACE));
		for (Want w : wants(bot, p, bot.targetForGear())) {
			Step s = w.plan.apply(planner);
			if (s instanceof Planner.Done) continue;
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
		wants.add(new Want(Planner::boat, false, item -> item.getDefaultInstance().is(ItemTags.BOATS)));
		return wants;
	}

	private static void addNetherKit(List<Want> wants, LocalPlayer p) {
		if (!InvUtil.canHarvest(p, Blocks.GOLD_ORE.defaultBlockState())) wants.add(Want.required(Items.IRON_PICKAXE));
		wants.add(Want.required(Items.FLINT_AND_STEEL));
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

	// ---- equipping ------------------------------------------------------------------------------

	/** Shield to the offhand, better armor on (gold stays on for the Nether). One action per tick. */
	private static boolean equip(Bot bot, LocalPlayer p) {
		if (!InvUtil.inventoryMenuActive(p) || p.isUsingItem()) return false;
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
