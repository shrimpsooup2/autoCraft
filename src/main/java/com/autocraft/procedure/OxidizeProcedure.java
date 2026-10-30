package com.autocraft.procedure;

import com.autocraft.Bot;
import com.autocraft.Chat;
import com.autocraft.control.InvUtil;
import com.autocraft.goal.Requirement.Dim;
import com.autocraft.path.Navigator;
import com.autocraft.world.BlockUtil;
import com.autocraft.world.WorldKey;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Exposed, weathered and oxidized copper: copper blocks are put out in a "copper garden" (next to the base, or
 * here) and left to oxidize on their own, which takes a long while per stage and only happens while the chunk is
 * loaded (within simulation distance of a player). The job carries on meanwhile (WAITING); once only this is left
 * the bot waits by the garden, mines blocks that reached the stage and scrapes ones that went too far back with an
 * axe first. The garden is remembered across sessions, per world.
 */
final class OxidizeProcedure implements Procedure {
	private static final List<Block> STAGES = List.of(Blocks.COPPER_BLOCK.weathering().unaffected(), Blocks.COPPER_BLOCK.weathering().exposed(),
			Blocks.COPPER_BLOCK.weathering().weathered(), Blocks.COPPER_BLOCK.weathering().oxidized());
	private static final Item COPPER = Items.COPPER_BLOCK.weathering().unaffected();
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	/** Within this distance of the garden its chunks are loaded and oxidizing. */
	private static final int STAY_NEAR = 24;
	private static final long NOTICE_TICKS = 20 * 60 * 5;

	/** One planted block (world position + dimension). */
	private record Plot(int x, int y, int z, Dim dim) {
		BlockPos pos() {
			return new BlockPos(x, y, z);
		}
	}

	private static @Nullable Map<String, List<Plot>> gardens;
	private static long lastNotice = -NOTICE_TICKS;

	@Override
	public boolean makes(Item item) {
		return stage(item) > 0;
	}

	private static int stage(Item item) {
		for (int i = 0; i < STAGES.size(); i++) {
			if (STAGES.get(i).asItem() == item) return i;
		}
		return -1;
	}

	@Override
	public Map<Item, Integer> needs(Item item, int total) {
		int planted = garden().size();
		return Map.of(COPPER, Math.max(0, total - planted), BlockWorkProcedure.carriedAxe(), 1,
				Actions.carriedTool(s -> s.isCorrectToolForDrops(STAGES.getFirst().defaultBlockState()), Items.STONE_PICKAXE), 1);
	}

	@Override
	public double cost(Item item) {
		return 40 + 20 * stage(item); // mostly waiting
	}

	@Override
	public Item consumed(Item item) {
		return COPPER;
	}

	@Override
	public Task start(Item item, int total) {
		return new Run(item, total);
	}

	/** This world's garden. */
	private static List<Plot> garden() {
		if (gardens == null) gardens = load();
		return gardens.computeIfAbsent(WorldKey.current(), k -> new ArrayList<>());
	}

	private final class Run implements Task {
		private final Item item;
		private final int total;
		private final int stage;
		private @Nullable BlockPos working;
		private int wait;

		Run(Item item, int total) {
			this.item = item;
			this.total = total;
			this.stage = OxidizeProcedure.stage(item);
		}

		@Override
		public Status tick(Bot bot) {
			if (wait > 0) {
				wait--;
				return Status.RUNNING;
			}
			int have = InvUtil.count(bot.player(), s -> s.is(item));
			if (have >= total) return Status.DONE;
			if (Actions.pickUp(bot, s -> s.is(item), 8)) return Status.RUNNING;
			List<Plot> plots = garden();
			plots.removeIf(pl -> pl.dim == bot.dimension() && bot.level().isLoaded(pl.pos()) && stageOf(bot.level().getBlockState(pl.pos())) < 0);

			// 1. harvest ripe blocks (scrape over-oxidized ones back first)
			for (Plot pl : plots) {
				if (pl.dim != bot.dimension() || !bot.level().isLoaded(pl.pos())) continue;
				int at = stageOf(bot.level().getBlockState(pl.pos()));
				if (at < stage) continue;
				working = pl.pos();
				bot.setStatus("collecting " + item.getDefaultInstance().getHoverName().getString() + " from the copper garden");
				if (Actions.reach(bot, working) != Actions.Result.DONE) return Status.RUNNING;
				if (at > stage) {
					Actions.useOn(bot, working, s -> s.is(ItemTags.AXES)); // scrape one stage back
					wait = 5;
					return Status.RUNNING;
				}
				if (bot.controls().mine(working)) {
					plots.remove(pl);
					save();
				}
				return Status.RUNNING;
			}

			// 2. plant more if the garden can't cover the rest
			int growing = (int) plots.stream().filter(pl -> pl.dim == bot.dimension()).count();
			if (growing < total - have && InvUtil.count(bot.player(), s -> s.is(COPPER)) > 0) {
				BlockPos spot = plantSpot(bot);
				if (spot == null) return Status.FAILED;
				if (Actions.reach(bot, spot) != Actions.Result.DONE) return Status.RUNNING;
				if (bot.controls().place(spot, s -> s.is(COPPER))) {
					plots.add(new Plot(spot.getX(), spot.getY(), spot.getZ(), bot.dimension()));
					save();
				}
				wait = 4;
				return Status.RUNNING;
			}
			// 3. wait by the garden so it keeps oxidizing
			BlockPos feet = Navigator.feet(bot.player());
			BlockPos nearest = plots.stream().filter(pl -> pl.dim == bot.dimension()).map(Plot::pos)
					.min(java.util.Comparator.comparingDouble(pos -> pos.distSqr(feet))).orElse(null);
			if (nearest != null && nearest.distSqr(feet) > STAY_NEAR * STAY_NEAR) {
				bot.setStatus("going back to the copper garden");
				if (Actions.reach(bot, nearest) == Actions.Result.FAILED) return Status.FAILED;
				return Status.RUNNING;
			}
			long now = bot.level().getGameTime();
			if (now - lastNotice > NOTICE_TICKS) {
				lastNotice = now;
				Chat.info("Copper is oxidizing in the garden (" + growing + " blocks, about an hour per stage while loaded); doing the rest of the job first.");
			}
			bot.setStatus("waiting for copper to oxidize");
			return Status.WAITING;
		}

		private int stageOf(BlockState s) {
			return STAGES.indexOf(s.getBlock());
		}

		/**
		 * A free spot next to the base (or next to us), never touching another plot (checkerboard). Copper weathers
		 * from random ticks, not air: demanding open sky and four free sides found no spot in a tunnel, and the
		 * procedure failed on the spot right after the copper block was crafted.
		 */
		private @Nullable BlockPos plantSpot(Bot bot) {
			BlockPos feet = Navigator.feet(bot.player());
			BlockPos center = bot.base() != null && bot.baseDimension() == bot.dimension()
					&& bot.base().distSqr(bot.player().blockPosition()) < 64 * 64 ? bot.base().offset(6, 0, 6) : feet;
			for (int r = 0; r <= 6; r++) {
				for (BlockPos pos : BlockPos.betweenClosed(center.offset(-r, -2, -r), center.offset(r, 2, r))) {
					if (Math.max(Math.abs(pos.getX() - center.getX()), Math.abs(pos.getZ() - center.getZ())) != r) continue;
					if ((pos.getX() + pos.getZ()) % 2 != 0) continue; // checkerboard: plots never side by side
					if (pos.equals(feet) || pos.equals(feet.above())) continue; // where we stand: placing fails
					BlockState at = bot.level().getBlockState(pos);
					if (!at.canBeReplaced() || !at.getFluidState().isEmpty()) continue;
					if (BlockUtil.canStandOn(bot.level(), pos.below(), bot.level().getBlockState(pos.below()))) return pos.immutable();
				}
			}
			return null;
		}
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("autocraft").resolve("copper_garden.json");
	}

	private static Map<String, List<Plot>> load() {
		Map<String, List<Plot>> out = new HashMap<>();
		try {
			if (Files.exists(file())) {
				Map<String, List<Plot>> loaded = GSON.fromJson(Files.readString(file()), new TypeToken<Map<String, List<Plot>>>() {}.getType());
				if (loaded != null) loaded.forEach((k, v) -> out.put(k, new ArrayList<>(v)));
			}
		} catch (IOException | JsonParseException e) {
			Chat.LOG.warn("Could not read copper_garden.json (a file from before per-world saving starts over)", e);
		}
		return out;
	}

	private static void save() {
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(gardens));
		} catch (IOException e) {
			Chat.LOG.warn("Could not save copper_garden.json", e);
		}
	}
}
