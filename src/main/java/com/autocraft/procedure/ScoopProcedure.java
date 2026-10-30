package com.autocraft.procedure;

import com.autocraft.Bot;
import com.autocraft.control.InvUtil;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;

/**
 * An empty bucket on powder snow fills it (mined, powder snow gives nothing). It lies in patches on snowy slopes and
 * in groves; the bot stays on solid ground beside it — the pathfinder never steps in, it swallows whoever does.
 */
final class ScoopProcedure implements Procedure {
	private static final Set<ResourceKey<Biome>> POWDER_SNOW_BIOMES = Set.of(Biomes.SNOWY_SLOPES, Biomes.GROVE);
	private static final int SCAN_CHUNKS = 4;
	private static final int MAX_LEGS = 24;

	@Override
	public boolean makes(Item item) {
		return item == Items.POWDER_SNOW_BUCKET;
	}

	@Override
	public Map<Item, Integer> needs(Item item, int total) {
		return Map.of(Items.BUCKET, Math.max(1, total));
	}

	@Override
	public double cost(Item item) {
		return 30; // a snowy mountainside to find
	}

	@Override
	public Item consumed(Item item) {
		return Items.BUCKET;
	}

	@Override
	public Task start(Item item, int total) {
		return new Run(total);
	}

	@Override
	public String label() {
		return "Bucket on powder snow (snowy slopes, groves)";
	}

	private static final class Run implements Task {
		private final int total;
		private final Explorer explorer = new Explorer(POWDER_SNOW_BIOMES);
		private final Set<BlockPos> unreachable = new HashSet<>();
		private @Nullable BlockPos target;
		private int wait;

		Run(int total) {
			this.total = total;
		}

		@Override
		public Status tick(Bot bot) {
			if (InvUtil.count(bot.player(), s -> s.is(Items.POWDER_SNOW_BUCKET)) >= total) return Status.DONE;
			if (wait > 0) {
				wait--;
				return Status.RUNNING;
			}
			if (InvUtil.find(bot.player(), s -> s.is(Items.BUCKET)) < 0) return Status.FAILED; // the planner gets them first
			if (target == null || !bot.level().getBlockState(target).is(Blocks.POWDER_SNOW)) {
				target = bot.scanner().findBlocks(s -> s.is(Blocks.POWDER_SNOW), SCAN_CHUNKS, 32).stream()
						.filter(pos -> !unreachable.contains(pos))
						.min(java.util.Comparator.comparingDouble(pos -> pos.distSqr(bot.player().blockPosition()))).orElse(null);
			}
			if (target == null) {
				if (explorer.exhausted(MAX_LEGS)) return Status.FAILED;
				explorer.tick(bot, "powder snow (snowy slopes, groves)");
				return Status.RUNNING;
			}
			bot.setStatus("filling a bucket with powder snow at " + target.toShortString());
			switch (Actions.reach(bot, target)) {
				case WORKING -> {
					return Status.RUNNING;
				}
				case FAILED -> {
					unreachable.add(target);
					target = null;
					return Status.RUNNING;
				}
				case DONE -> {
				}
			}
			if (!Actions.useOn(bot, target, s -> s.is(Items.BUCKET))) return Status.FAILED;
			target = null;
			wait = 10;
			return Status.RUNNING;
		}
	}
}
