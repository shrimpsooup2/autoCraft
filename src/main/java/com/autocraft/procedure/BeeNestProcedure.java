package com.autocraft.procedure;

import com.autocraft.Bot;
import com.autocraft.control.InvUtil;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Honey bottles (glass bottle on a full nest) and honeycomb (shears on a full nest, 3 each). Full nests/hives
 * ({@code honey_level=5}) are looked for around, else in flower-rich biomes where bees live. The bees get angry;
 * combat and the effect reflex (milk/honey for poison) deal with that.
 */
final class BeeNestProcedure implements Procedure {
	private static final int SCAN_CHUNKS = 4;
	private static final int MAX_LEGS = 16;

	@Override
	public boolean makes(Item item) {
		return item == Items.HONEY_BOTTLE || item == Items.HONEYCOMB;
	}

	@Override
	public Map<Item, Integer> needs(Item item, int total) {
		return item == Items.HONEY_BOTTLE ? Map.of(Items.GLASS_BOTTLE, Math.max(1, total)) : Map.of(Items.SHEARS, 1);
	}

	@Override
	public double cost(Item item) {
		return item == Items.HONEY_BOTTLE ? 14 : 6;
	}

	@Override
	public Item consumed(Item item) {
		return item == Items.HONEY_BOTTLE ? Items.GLASS_BOTTLE : null;
	}

	@Override
	public Task start(Item item, int total) {
		return new Run(item, total);
	}

	private static boolean full(BlockState s) {
		return s.getBlock() instanceof BeehiveBlock && s.getValue(BeehiveBlock.HONEY_LEVEL) >= BeehiveBlock.MAX_HONEY_LEVELS;
	}

	private static final class Run implements Task {
		private final Item item;
		private final int total;
		private final Explorer explorer = new Explorer(Set.of(Biomes.FLOWER_FOREST, Biomes.SUNFLOWER_PLAINS, Biomes.MEADOW, Biomes.CHERRY_GROVE,
				Biomes.BIRCH_FOREST, Biomes.PLAINS));
		private @Nullable BlockPos nest;
		private int wait;

		Run(Item item, int total) {
			this.item = item;
			this.total = total;
		}

		@Override
		public Status tick(Bot bot) {
			if (InvUtil.count(bot.player(), s -> s.is(item)) >= total) return Status.DONE;
			if (wait > 0) {
				wait--;
				return Status.RUNNING;
			}
			if (item == Items.HONEYCOMB && Actions.pickUp(bot, s -> s.is(Items.HONEYCOMB), 8)) return Status.RUNNING;
			if (nest == null || !full(bot.level().getBlockState(nest))) nest = Actions.nearestBlock(bot, BeeNestProcedure::full, SCAN_CHUNKS);
			if (nest == null) {
				if (explorer.exhausted(MAX_LEGS)) return Status.FAILED;
				explorer.tick(bot, "a bee nest full of honey");
				return Status.RUNNING;
			}
			bot.setStatus((item == Items.HONEY_BOTTLE ? "bottling honey at " : "shearing honeycomb at ") + nest.toShortString());
			switch (Actions.reach(bot, nest)) {
				case WORKING -> {
					return Status.RUNNING;
				}
				case FAILED -> {
					nest = null;
					return Status.RUNNING;
				}
				case DONE -> {
				}
			}
			boolean used = item == Items.HONEY_BOTTLE ? Actions.useOn(bot, nest, s -> s.is(Items.GLASS_BOTTLE))
					: Actions.useOn(bot, nest, s -> s.is(Items.SHEARS));
			if (!used && InvUtil.find(bot.player(), s -> s.is(item == Items.HONEY_BOTTLE ? Items.GLASS_BOTTLE : Items.SHEARS)) < 0) return Status.FAILED;
			wait = 8;
			return Status.RUNNING;
		}
	}
}
