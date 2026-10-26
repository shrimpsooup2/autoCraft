package com.autocraft.behavior;

import com.autocraft.Bot;
import com.autocraft.Chat;
import com.autocraft.ai.Features;
import com.autocraft.control.InvUtil;
import com.autocraft.craft.Planner;
import com.autocraft.craft.Planner.Step;
import com.autocraft.craft.Stations;
import com.autocraft.craft.Workshop;
import com.autocraft.goal.Job;
import com.autocraft.goal.Requirement;
import com.autocraft.path.Goal;
import com.autocraft.path.Navigator;
import com.autocraft.loot.LootBook;
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
import net.minecraft.tags.BlockTags;
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
import net.minecraft.world.level.block.Blocks;
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
	private static final int TOOL_ANSWER_TICKS = 10;
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
	/** Ticks until the next tool request is answered (each answer plans recursively; once per half second is plenty). */
	private int toolAnswerCooldown;
	/** Required gear already reported as impossible (warned once each). */
	private final java.util.Set<String> warnedUnavailable = new java.util.HashSet<>();
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
			case TRAVEL -> {
				return false; // a procedure needs the other dimension: the portal behavior takes us
			}
			case FAILED, WAITING -> {
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
		// the collector is stuck on something no carried tool mines: answer it now, for exactly that target, instead of
		// waiting for the next planning round to (maybe) plan for a different one
		Requirement stuck = bot.takeToolRequest();
		if (toolAnswerCooldown > 0) toolAnswerCooldown--;
		Step step = stuck == null || toolAnswerCooldown > 0 ? null : toolStepFor(bot, stuck);
		if (step == null) {
			if (++sincePlan < PLAN_EVERY) return false;
			sincePlan = 0;
			// no "mobs nearby" gate here: combat runs first and takes over when a mob matters; a mob behind a wall used
			// to block all gear planning, and with the only pickaxe worn out the bot then stood still
			step = plan(bot, p);
			bot.noteGear("plan: " + describe(step));
		}
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
			case Planner.Cut c -> {
				bot.setNeed(Bot.NeedOwner.GEAR, null);
				workshop.start(c);
				return true;
			}
			case Planner.Special sp -> {
				if (workshop.packUp(bot)) return true;
				bot.setNeed(Bot.NeedOwner.GEAR, null);
				workshop.start(sp);
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
				bot.setNeed(Bot.NeedOwner.GEAR, null); // a stale need would hold the collector on something it can't mine
				return false;
			}
			case Planner.Done d -> {
				bot.setNeed(Bot.NeedOwner.GEAR, null);
				return false; // stations are picked up when the bot leaves the area or finishes
			}
		}
	}

	/**
	 * The first step toward a pickaxe for {@code r}, which the collector can't mine with anything carried; null when
	 * the planner disagrees (it sees a usable tool), then the normal plan runs.
	 */
	private @Nullable Step toolStepFor(Bot bot, Requirement r) {
		Step s = Stations.planner(bot).borrowingJobLoot().toolFirst(new Planner.Gather(r));
		toolAnswerCooldown = TOOL_ANSWER_TICKS;
		bot.noteGear("tool for " + r.name() + ": " + describe(s));
		if (s instanceof Planner.Gather g && g.requirement().sources().equals(r.sources())) return null; // "can mine it already"
		if (s instanceof Planner.Unavailable u) {
			if (warnedUnavailable.add(u.what())) Chat.warn("Can't get " + u.what() + "; working on the rest.");
			Job job = bot.job();
			if (job != null && job.requirements().contains(r)) job.defer(r); // a job item: the rest of the job first
		}
		return s;
	}

	private static String describe(Step s) {
		return switch (s) {
			case Planner.Craft c -> "craft " + c.recipe().output().getDefaultInstance().getHoverName().getString();
			case Planner.Smelt sm -> "smelt " + sm.count() + " " + sm.input().getDefaultInstance().getHoverName().getString();
			case Planner.Cut c -> "cut " + c.output().getDefaultInstance().getHoverName().getString();
			case Planner.Special sp -> "procedure for " + sp.item().getDefaultInstance().getHoverName().getString();
			case Planner.Gather g -> "gather " + g.requirement();
			case Planner.Unavailable u -> "can't get " + u.what();
			case Planner.Done d -> "nothing needed";
		};
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
		Planner strict = Stations.planner(bot);
		// tools the job can't go on without may use job loot (3 cobblestone for a stone pickaxe); extras may not
		Planner tools = Stations.planner(bot).borrowingJobLoot();
		for (Want w : wants(bot, p, tools, bot.targetForGear())) {
			Step s = w.plan.apply(w.required ? tools : strict);
			if (s instanceof Planner.Gather g) s = tools.toolFirst(g); // a pickaxe first when nothing carried mines it
			if (s instanceof Planner.Unavailable u) {
				if (w.required && warnedUnavailable.add(u.what())) Chat.warn("Can't get " + u.what() + "; carrying on with the rest.");
				continue;
			}
			if (s instanceof Planner.Done) continue;
			if (!w.required && !(s instanceof Planner.Craft c && w.finalItem.test(c.recipe().output()))) continue;
			return s;
		}
		return new Planner.Done();
	}

	/**
	 * What to own, in order. Tools scale with the work left: a stone pickaxe for any real stone work (never mine
	 * a lot with wood), iron/diamond when hundreds of blocks remain and the materials are cheap, axes and shovels
	 * for big wood/dirt jobs. Nothing is made that something carried already beats.
	 */
	private static List<Want> wants(Bot bot, LocalPlayer p, Planner planner, @Nullable Requirement target) {
		List<Want> wants = new ArrayList<>();
		int pick = 0, axe = 0, shovel = 0;
		if (target != null) {
			// the work needs a pickaxe nothing carried can stand in for: its first step (cycle-safe, see toolFirst)
			if (!planner.pickaxeTiers(target).isEmpty() && !planner.canHarvest(target)) {
				wants.add(new Want(pl -> pl.toolFirst(new Planner.Gather(target)), true, i -> i.getDefaultInstance().is(ItemTags.PICKAXES)));
			}
			boolean deep = target.strategy() == Requirement.Strategy.UNDERGROUND || target.dimension() == Requirement.Dim.NETHER;
			int remaining = Math.max(0, target.amount() - InvUtil.count(p, target.counts()));
			// a build's gathering comes in windows: the work ahead is the rest of the build
			if (target == bot.need(Bot.NeedOwner.BUILD)) remaining = Math.max(remaining, bot.buildTotal() - bot.buildPlaced());
			if (deep || target.sources().stream().anyMatch(b -> b.defaultBlockState().is(BlockTags.MINEABLE_WITH_PICKAXE))) pick = Math.max(1, remaining);
			if (target.sources().stream().anyMatch(b -> b.defaultBlockState().is(BlockTags.MINEABLE_WITH_AXE))) axe = remaining;
			if (target.sources().stream().anyMatch(b -> b.defaultBlockState().is(BlockTags.MINEABLE_WITH_SHOVEL))) shovel = remaining;
			if (target.tool() == LootBook.ToolUse.SHEARS && InvUtil.find(p, s -> s.is(Items.SHEARS) && !InvUtil.nearlyBroken(s)) < 0) {
				wants.add(Want.required(Items.SHEARS));
			}
			// hunting means fighting on purpose: a real weapon first
			if (target.isHunted() && Features.bestWeaponDamage(p) < 5) wants.add(Want.required(Items.STONE_SWORD));
		}
		// water or ice to cross (an ocean, a frozen sea): a boat beats swimming; made before any tool upgrade
		if (bot.boatWanted()) wants.add(new Want(Planner::boat, true, item -> item.getDefaultInstance().is(ItemTags.BOATS)));
		int[] dig = bot.buildDig(); // digging out a build site counts as work for the tools too
		if (dig != null) {
			pick += dig[0];
			shovel += dig[1];
			axe += dig[2];
		}
		addToolTiers(wants, p, pick, axe, shovel);
		if (netherTripAhead(bot, p)) addNetherKit(wants, p);
		// combat only takes quick kills, so a stone sword is plenty; never "upgrade" past something carried
		if (Features.bestWeaponDamage(p) < 5) wants.add(Want.optional(Items.STONE_SWORD));
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

	/** Better tools when the work ahead (blocks per tool kind) pays for them. */
	private static void addToolTiers(List<Want> wants, LocalPlayer p, int pick, int axe, int shovel) {
		if (pick > 0) {
			boolean stoneTier = InvUtil.canHarvest(p, Blocks.IRON_ORE.defaultBlockState());
			boolean ironTier = InvUtil.canHarvest(p, Blocks.DIAMOND_ORE.defaultBlockState());
			boolean diamondTier = InvUtil.canHarvest(p, Blocks.OBSIDIAN.defaultBlockState());
			if (!stoneTier) {
				wants.add(Want.required(Items.STONE_PICKAXE)); // 3 cobblestone, mined anyway: 50% faster than wood
			} else if (!ironTier && (pick >= 1024 || pick >= 384 && carriedSpare(p, Items.IRON_INGOT, Items.RAW_IRON) >= 3)) {
				wants.add(Want.required(Items.IRON_PICKAXE));
			} else if (ironTier && !diamondTier && pick >= 256 && carriedSpare(p, Items.DIAMOND) >= 3) {
				wants.add(Want.optional(Items.DIAMOND_PICKAXE));
			}
		}
		if (axe >= 24 && InvUtil.find(p, s -> s.is(ItemTags.AXES) && !InvUtil.nearlyBroken(s)) < 0) {
			wants.add(axe >= 64 ? Want.required(Items.STONE_AXE) : Want.optional(Items.STONE_AXE));
		}
		if (shovel >= 48 && InvUtil.find(p, s -> s.is(ItemTags.SHOVELS) && !InvUtil.nearlyBroken(s)) < 0) {
			wants.add(shovel >= 256 ? Want.required(Items.STONE_SHOVEL) : Want.optional(Items.STONE_SHOVEL));
		}
	}

	private static int carriedSpare(LocalPlayer p, Item... items) {
		int n = 0;
		for (Item item : items) n += InvUtil.count(p, s -> s.is(item) && !Bot.get().isJobTarget(s));
		return n;
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

	/** The next work (job or a build/make need) is in the Nether and we're still in the Overworld. */
	private static boolean netherTripAhead(Bot bot, LocalPlayer p) {
		return bot.workDimension() == Requirement.Dim.NETHER && bot.dimension() == Requirement.Dim.OVERWORLD;
	}

	/** A bucket counts whether it's empty or already holds water. */
	private static Step bucket(Planner planner) {
		return planner.hasAny(s -> s.is(Items.BUCKET) || s.is(Items.WATER_BUCKET)) ? new Planner.Done() : planner.next(Items.BUCKET, 1);
	}

	/**
	 * Overworld: carry water, not an empty bucket — scoop the nearest water source, but only if it's close (or a
	 * Nether trip is coming up, where water is needed for obsidian and clutches).
	 */
	private boolean fillBucket(Bot bot, LocalPlayer p) {
		if (bot.dimension() != Requirement.Dim.OVERWORLD || InvUtil.count(p, s -> s.is(Items.WATER_BUCKET)) > 0
				|| InvUtil.count(p, s -> s.is(Items.BUCKET)) == 0) {
			water = null;
			return false;
		}
		if (water == null) {
			if (++sinceWaterSearch < WATER_SEARCH_EVERY) return false;
			sinceWaterSearch = 0;
			double maxDist = netherTripAhead(bot, p) ? 48 : 16;
			List<BlockPos> found = bot.scanner().findBlocks(s -> BlockUtil.isWater(s) && s.getFluidState().isSource(), 2, 4);
			found.removeIf(w -> w.distSqr(p.blockPosition()) > maxDist * maxDist);
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
