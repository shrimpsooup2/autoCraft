package com.autocraft.behavior;

import com.autocraft.Bot;
import com.autocraft.Chat;
import com.autocraft.build.BuildOrder;
import com.autocraft.build.BuildPlan;
import com.autocraft.build.MaterialAdvisor;
import com.autocraft.build.MaterialSettings;
import com.autocraft.build.SiteFinder;
import com.autocraft.build.StructureRegistry;
import com.autocraft.combat.Melee;
import com.autocraft.control.ContainerOpener;
import com.autocraft.control.InvUtil;
import com.autocraft.goal.Requirement;
import com.autocraft.path.Goal;
import com.autocraft.path.Navigator;
import com.autocraft.schematic.Schematic;
import com.autocraft.schematic.SchematicLibrary;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

/**
 * Uses farms for the job's current resource (or a mob drop being hunted): harvest a registered farm nearby
 * (cobblestone generator, cane, trees, crops, an automatic farm's chests, or a mob farm's landing) whenever it
 * has something ready; otherwise the collector keeps working. When exploring keeps coming up empty for a resource
 * that has a farm design (built-in or a user {@code farm-<resource>-*} schematic; {@code hostile_mobs} for the
 * drops of common hostile mobs), it picks a site and builds one.
 */
public final class FarmBehavior implements Behavior {
	/** Fruitless exploration legs before building a farm instead. */
	private static final int AUTO_BUILD_AFTER = 4;
	/** A farm this close is harvested right away; up to {@link #FAR_FARM_DISTANCE} once searching nearby has failed. */
	private static final double NEAR_FARM_DISTANCE = 48;
	private static final double FAR_FARM_DISTANCE = 128;
	private static final int CONTAINER_CHECK_EVERY = 20 * 120;
	/** Mobs that spawn in a dark-room mob farm (the built-in one is 3 high, so endermen fit). */
	private static final Set<EntityType<?>> FARM_MOBS = Set.of(EntityTypes.ZOMBIE, EntityTypes.SKELETON, EntityTypes.CREEPER,
			EntityTypes.SPIDER, EntityTypes.WITCH, EntityTypes.ENDERMAN);

	private final Set<String> autoTried = new HashSet<>();
	private final Map<StructureRegistry.Entry, BuildPlan> geometry = new HashMap<>();
	private final Map<BlockPos, Long> containersChecked = new HashMap<>();
	private @Nullable Harvest harvest;
	private @Nullable ContainerOpener opener;
	private @Nullable BlockPos openContainer;
	private long tick;

	@Override
	public String name() {
		return "farm";
	}

	@Override
	public void reset() {
		autoTried.clear();
		harvest = null;
		opener = null;
		openContainer = null;
	}

	@Override
	public boolean tick(Bot bot) {
		tick++;
		if (bot.job() == null || bot.buildOrder() != null) return false;
		Requirement need = bot.activeNeed();
		if (need != null && !need.isHunted()) return false; // tools/build materials: the collector's job
		Requirement target = need != null ? need : bot.job().nextUnmet(bot.player());
		if (target == null) return false;
		List<String> keys = farmKeys(target);
		double reach = bot.fruitlessLegs(target.name()) >= 1 ? FAR_FARM_DISTANCE : NEAR_FARM_DISTANCE;
		for (String key : keys) {
			StructureRegistry.Entry farm = nearestFarm(bot, key, reach);
			if (farm != null) return operate(bot, farm, target);
		}
		if (bot.fruitlessLegs(target.name()) >= AUTO_BUILD_AFTER && autoTried.add(target.name())) return autoBuild(bot, target, keys);
		return false;
	}

	/** Farm kinds that yield the target: its own name, plus mob farms for drops of the mobs that spawn in them. */
	private static List<String> farmKeys(Requirement target) {
		if (!target.isHunted() || target.mobs().stream().noneMatch(FARM_MOBS::contains)) return List.of(target.name());
		return List.of(target.name(), Schematic.Farm.HOSTILE_MOBS);
	}

	/** {@code !ac farm} and the automatic case: choose a design and a site, start building. */
	public static boolean buildFarm(Bot bot, String resource, @Nullable BlockPos origin, Rotation rotation) {
		for (Schematic s : SchematicLibrary.farmsFor(resource)) {
			BlockPos site = origin != null ? origin : SiteFinder.find(bot, s);
			if (site == null) continue;
			bot.startBuild(new BuildOrder(s, site, rotation, Mirror.NONE, MaterialAdvisor.resolve(MaterialSettings.defaults(), s),
					BuildOrder.Purpose.FARM));
			return true;
		}
		return false;
	}

	private boolean autoBuild(Bot bot, Requirement target, List<String> keys) {
		for (String key : keys) {
			if (SchematicLibrary.farmsFor(key).isEmpty()) continue;
			Chat.info("Can't find enough " + target.name() + " around; building a farm for it.");
			if (buildFarm(bot, key, null, Rotation.NONE)) return true;
			Chat.warn("No good spot for a " + target.name() + " farm here.");
			return false;
		}
		return false;
	}

	private static StructureRegistry.@Nullable Entry nearestFarm(Bot bot, String resource, double reach) {
		StructureRegistry.Entry best = null;
		double bestDist = reach * reach;
		for (StructureRegistry.Entry e : StructureRegistry.farmsFor(resource, bot.dimension())) {
			double d = e.center().distSqr(bot.player().blockPosition());
			if (d < bestDist) {
				bestDist = d;
				best = e;
			}
		}
		return best;
	}

	// ---- harvesting -----------------------------------------------------------------------------

	private boolean operate(Bot bot, StructureRegistry.Entry farm, Requirement target) {
		Schematic s = SchematicLibrary.get(farm.schematic());
		if (s == null || s.farm() == null) return false;
		BuildPlan plan = geometry.computeIfAbsent(farm, e -> new BuildPlan(s, e.origin(), e.rotationValue(), e.mirrorValue(), e.substitution()));
		if (harvest != null) {
			if (harvest.tick(bot) == Harvest.Status.RUNNING) {
				bot.setStatus("harvesting " + s.name());
				return true;
			}
			harvest = null;
		}
		if (pickUpLoose(bot, plan.box().inflatedBy(3), target)) return true;
		return switch (s.farm().mode()) {
			case COBBLE -> cobble(bot, plan, s.farm());
			case CANE -> cane(bot, plan, target);
			case TREE -> trees(bot, plan);
			case CROP -> crops(bot, plan);
			case CONTAINERS -> containers(bot, plan, target);
			case MOBS -> mobs(bot, plan, s.farm(), target);
		};
	}

	/** Stand beside the output and mine the cobblestone each time the lava turns into it. */
	private boolean cobble(Bot bot, BuildPlan plan, Schematic.Farm farm) {
		BlockPos stand = plan.toWorld(farm.stand()), output = plan.toWorld(farm.output());
		LocalPlayer p = bot.player();
		bot.setStatus("mining the cobblestone generator");
		if (!Navigator.feet(p).equals(stand)) {
			bot.nav().setGoal(new Goal.Block(stand));
			if (bot.nav().tick() == Navigator.Status.FAILED) {
				bot.nav().stop();
				return false;
			}
			return true;
		}
		bot.nav().stop();
		if (bot.level().getBlockState(output).is(Blocks.COBBLESTONE)) bot.controls().mine(output);
		return true;
	}

	/** Mob farm: wait in the doorway by the landing, finish off whatever survived the fall (drops are picked up above). */
	private boolean mobs(Bot bot, BuildPlan plan, Schematic.Farm farm, Requirement target) {
		LocalPlayer p = bot.player();
		BlockPos stand = plan.toWorld(farm.stand()), landing = plan.toWorld(farm.output());
		for (Entity e : bot.scanner().entities(6, e -> e instanceof Enemy && e instanceof LivingEntity le && le.isAlive())) {
			if (e.blockPosition().distSqr(landing) > 4 || !(e instanceof LivingEntity mob)) continue;
			if (Melee.strike(bot, mob)) {
				bot.setStatus("finishing off a " + mob.getType().getDescription().getString() + " at the mob farm");
				return true;
			}
		}
		if (!Navigator.feet(p).equals(stand)) {
			bot.setStatus("going to the mob farm");
			bot.nav().setGoal(new Goal.Block(stand));
			if (bot.nav().tick() == Navigator.Status.FAILED) {
				bot.nav().stop();
				return false;
			}
			return true;
		}
		bot.nav().stop();
		bot.setStatus("waiting at the mob farm for " + target.name());
		return true;
	}

	/** Break cane/bamboo/cactus above the bottom block of each planted column. */
	private boolean cane(Bot bot, BuildPlan plan, Requirement target) {
		ClientLevel level = bot.level();
		for (BuildPlan.Task t : plan.places()) {
			if (!(t.state().getBlock() instanceof SugarCaneBlock || t.state().getBlock() instanceof BambooStalkBlock
					|| t.state().getBlock() instanceof CactusBlock)) continue;
			BlockPos above = t.pos().above();
			if (level.getBlockState(above).is(t.state().getBlock())) {
				harvest = new Harvest(above, target.counts());
				return true;
			}
		}
		return false;
	}

	/** Chop grown trees in the farm, then replant the sapling spots. */
	private boolean trees(Bot bot, BuildPlan plan) {
		ClientLevel level = bot.level();
		BoundingBox area = plan.box().inflatedBy(3, 0, 3);
		for (BlockPos pos : BlockPos.betweenClosed(area.minX(), area.minY(), area.minZ(), area.maxX(), area.maxY() + 12, area.maxZ())) {
			if (level.getBlockState(pos).is(BlockTags.LOGS)) {
				harvest = new Harvest(pos.immutable(), s -> s.is(ItemTags.LOGS) || s.is(ItemTags.SAPLINGS));
				return true;
			}
		}
		for (BuildPlan.Task t : plan.places()) {
			if (!(t.state().getBlock() instanceof SaplingBlock) || !level.getBlockState(t.pos()).isAir()) continue;
			if (InvUtil.find(bot.player(), s -> s.is(ItemTags.SAPLINGS)) < 0) return false;
			if (!bot.player().isWithinBlockInteractionRange(t.pos(), -0.5)) {
				bot.nav().setGoal(new Goal.Reach(t.pos(), 3.5));
				bot.nav().tick();
				return true;
			}
			bot.controls().place(t.pos(), s -> s.is(ItemTags.SAPLINGS));
			return true;
		}
		return false;
	}

	/** Break ripe crops and sow again. */
	private boolean crops(Bot bot, BuildPlan plan) {
		ClientLevel level = bot.level();
		for (BuildPlan.Task t : plan.places()) {
			if (!(t.state().getBlock() instanceof CropBlock crop)) continue;
			BlockState world = level.getBlockState(t.pos());
			if (world.getBlock() instanceof CropBlock c && c.isMaxAge(world)) {
				// seeds, the crop itself and food crops (carrots/potatoes are their own seeds)
				harvest = new Harvest(t.pos(), s -> s.is(crop.asItem()) || s.is(Items.WHEAT) || s.has(DataComponents.FOOD));
				return true;
			}
			if (world.isAir() && level.getBlockState(t.pos().below()).is(Blocks.FARMLAND)) {
				Item seed = crop.asItem();
				if (InvUtil.count(bot.player(), s -> s.is(seed)) == 0) continue;
				if (!bot.player().isWithinBlockInteractionRange(t.pos(), -0.5)) {
					bot.nav().setGoal(new Goal.Reach(t.pos(), 3.5));
					bot.nav().tick();
					return true;
				}
				bot.controls().place(t.pos(), s -> s.is(seed));
				return true;
			}
		}
		return false;
	}

	/** Automatic farm: every couple of minutes, empty its chests/barrels of the product. */
	private boolean containers(Bot bot, BuildPlan plan, Requirement target) {
		LocalPlayer p = bot.player();
		if (openContainer != null) {
			if (p.containerMenu instanceof ChestMenu menu) {
				if (!takeOne(p, menu, target)) {
					p.closeContainer();
					containersChecked.put(openContainer, tick);
					openContainer = null;
				}
				return true;
			}
			if (opener.tick(bot) == ContainerOpener.Status.FAILED) {
				containersChecked.put(openContainer, tick);
				openContainer = null;
			}
			return true;
		}
		List<BlockPos> boxes = bot.scanner().findBlocks(st -> st.getBlock() instanceof ChestBlock || st.getBlock() instanceof BarrelBlock, 2, 32);
		for (BlockPos c : boxes) {
			if (!plan.box().isInside(c) || tick - containersChecked.getOrDefault(c, -CONTAINER_CHECK_EVERY * 2L) < CONTAINER_CHECK_EVERY) continue;
			if (!p.isWithinBlockInteractionRange(c, -0.5)) {
				bot.nav().setGoal(new Goal.Adjacent(c));
				if (bot.nav().tick() == Navigator.Status.FAILED) containersChecked.put(c, tick);
				return true;
			}
			bot.nav().stop();
			openContainer = c;
			opener = new ContainerOpener(c, ChestMenu.class);
			bot.setStatus("collecting from the farm's storage");
			return true;
		}
		return false;
	}

	private static boolean takeOne(LocalPlayer p, ChestMenu menu, Requirement target) {
		int size = menu.getRowCount() * 9;
		for (int slot = 0; slot < size; slot++) {
			ItemStack s = menu.getSlot(slot).getItem();
			if (s.isEmpty() || !target.counts().test(s)) continue;
			int before = s.getCount();
			InvUtil.quickMove(p, slot);
			return menu.getSlot(slot).getItem().getCount() < before;
		}
		return false;
	}

	/** Walk over farm drops (cane knocked off, saplings, seeds) lying around the farm. */
	private boolean pickUpLoose(Bot bot, BoundingBox area, Requirement target) {
		List<ItemEntity> items = bot.scanner().items(24, s -> target.counts().test(s) || s.is(ItemTags.SAPLINGS)
				|| s.is(Items.WHEAT_SEEDS));
		for (ItemEntity item : items) {
			if (!area.isInside(item.blockPosition())) continue;
			bot.nav().setGoal(new Goal.Near(item.blockPosition(), 0));
			if (bot.nav().tick() == Navigator.Status.FAILED) {
				bot.nav().stop();
				return false;
			}
			bot.setStatus("picking up farm drops");
			return true;
		}
		return false;
	}
}
