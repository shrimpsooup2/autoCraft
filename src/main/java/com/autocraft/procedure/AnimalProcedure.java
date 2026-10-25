package com.autocraft.procedure;

import com.autocraft.Bot;
import com.autocraft.control.InvUtil;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.chicken.ChickenVariants;
import net.minecraft.world.entity.animal.cow.AbstractCow;
import net.minecraft.world.entity.animal.goat.Goat;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.animal.armadillo.Armadillo;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import org.jspecify.annotations.Nullable;

/**
 * Using a tool on animals: milk (bucket on a cow, mooshroom or goat), wool (shears on a sheep of that colour, 1-3
 * each), armadillo scutes (brush), and eggs (stay with chickens and pick up what they lay).
 */
final class AnimalProcedure implements Procedure {
	private static final double SEARCH = 64;
	private static final int MAX_LEGS = 16;
	/** Eggs: give up on a flock after this long without one. */
	private static final int EGG_PATIENCE = 20 * 60 * 6;

	private record Kind(Predicate<Entity> animal, @Nullable Item tool, Set<ResourceKey<Biome>> biomes, double cost, String what) {}

	private final Map<Item, Kind> kinds = new HashMap<>();

	AnimalProcedure() {
		Set<ResourceKey<Biome>> grass = Set.of(Biomes.PLAINS, Biomes.MEADOW, Biomes.SUNFLOWER_PLAINS, Biomes.SAVANNA);
		kinds.put(Items.MILK_BUCKET, new Kind(e -> e instanceof AbstractCow || e instanceof Goat, Items.BUCKET, grass, 6, "a cow to milk"));
		kinds.put(Items.ARMADILLO_SCUTE, new Kind(e -> e instanceof Armadillo, Items.BRUSH,
				Set.of(Biomes.SAVANNA, Biomes.SAVANNA_PLATEAU, Biomes.BADLANDS), 25, "an armadillo"));
		for (DyeColor c : DyeColor.values()) {
			Item wool = BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.withDefaultNamespace(c.getName() + "_wool"));
			kinds.put(wool, new Kind(e -> e instanceof Sheep s && !s.isSheared() && s.getColor() == c, Items.SHEARS, grass,
					c == DyeColor.WHITE ? 5 : c == DyeColor.BLACK || c == DyeColor.GRAY || c == DyeColor.LIGHT_GRAY || c == DyeColor.BROWN ? 15 : 60,
					"a " + c.getName().replace('_', ' ') + " sheep"));
		}
		kinds.put(Items.EGG, new Kind(e -> e instanceof Chicken c && c.getVariant().is(ChickenVariants.TEMPERATE), null, grass, 30, "chickens"));
		kinds.put(Items.BROWN_EGG, new Kind(e -> e instanceof Chicken c && c.getVariant().is(ChickenVariants.WARM), null,
				Set.of(Biomes.SAVANNA, Biomes.DESERT, Biomes.JUNGLE, Biomes.BADLANDS), 40, "warm chickens"));
		kinds.put(Items.BLUE_EGG, new Kind(e -> e instanceof Chicken c && c.getVariant().is(ChickenVariants.COLD), null,
				Set.of(Biomes.SNOWY_PLAINS, Biomes.TAIGA, Biomes.SNOWY_TAIGA, Biomes.OLD_GROWTH_PINE_TAIGA), 40, "cold chickens"));
	}

	@Override
	public boolean makes(Item item) {
		return kinds.containsKey(item);
	}

	@Override
	public Map<Item, Integer> needs(Item item, int total) {
		Kind k = kinds.get(item);
		if (k.tool == null) return Map.of();
		return Map.of(k.tool, k.tool == Items.BUCKET ? Math.max(1, total) : 1);
	}

	@Override
	public double cost(Item item) {
		return kinds.get(item).cost;
	}

	@Override
	public Task start(Item item, int total) {
		return new Run(item, total, kinds.get(item));
	}

	private final class Run implements Task {
		private final Item item;
		private final int total;
		private final Kind kind;
		private final Explorer explorer;
		private final Set<Integer> done = new HashSet<>();
		private final net.minecraft.core.BlockPos[] chase = new net.minecraft.core.BlockPos[1];
		private @Nullable Entity target;
		private int wait;
		private int sinceEgg;

		Run(Item item, int total, Kind kind) {
			this.item = item;
			this.total = total;
			this.kind = kind;
			this.explorer = new Explorer(kind.biomes);
		}

		@Override
		public Status tick(Bot bot) {
			if (InvUtil.count(bot.player(), s -> s.is(item)) >= total) return Status.DONE;
			if (wait > 0) {
				wait--;
				return Status.RUNNING;
			}
			Predicate<ItemStack> drop = s -> s.is(item);
			if (Actions.pickUp(bot, drop, 10)) {
				sinceEgg = 0;
				return Status.RUNNING;
			}
			if (target == null || !target.isAlive() || !kind.animal.test(target)) {
				List<Entity> found = bot.scanner().entities(SEARCH, e -> kind.animal.test(e) && !done.contains(e.getId())
						&& !(e instanceof AgeableMob a && a.isBaby()));
				target = found.stream().min(Comparator.comparingDouble(e -> e.distanceToSqr(bot.player()))).orElse(null);
				chase[0] = null;
			}
			if (target == null) {
				if (explorer.legs() >= MAX_LEGS) return Status.FAILED;
				explorer.tick(bot, kind.what);
				return Status.RUNNING;
			}
			if (kind.tool == null) return waitForEggs(bot);
			bot.setStatus("getting " + item.getDefaultInstance().getHoverName().getString() + " from " + kind.what);
			switch (Actions.reach(bot, target, chase)) {
				case WORKING -> {
					return Status.RUNNING;
				}
				case FAILED -> {
					done.add(target.getId());
					target = null;
					return Status.RUNNING;
				}
				case DONE -> {
				}
			}
			if (!Actions.useOn(bot, target, s -> s.is(kind.tool)) && InvUtil.find(bot.player(), s -> s.is(kind.tool)) < 0) return Status.FAILED;
			if (kind.tool != Items.BUCKET) done.add(target.getId()); // a sheared sheep/brushed armadillo has nothing more for now
			target = null;
			wait = 10;
			return Status.RUNNING;
		}

		/** Chickens lay an egg every 5-10 minutes: hang around the flock and collect them. */
		private Status waitForEggs(Bot bot) {
			if (++sinceEgg > EGG_PATIENCE) {
				done.add(target.getId());
				target = null;
				sinceEgg = 0;
				return Status.RUNNING;
			}
			bot.setStatus("waiting for chickens to lay eggs");
			if (Actions.reach(bot, target, chase) == Actions.Result.FAILED) target = null;
			return Status.RUNNING;
		}
	}
}
