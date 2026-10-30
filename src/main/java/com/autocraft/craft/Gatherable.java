package com.autocraft.craft;

import com.autocraft.goal.Requirement;
import com.autocraft.goal.Requirement.Dim;
import com.autocraft.goal.Resources;
import com.autocraft.hunt.MobHints;
import com.autocraft.loot.LootBook;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.VegetationBlock;
import org.jspecify.annotations.Nullable;

/**
 * Which items the bot can get by mining, and as which requirement. Named resources (ores, logs, sand...)
 * come first; otherwise a block that drops itself, isn't made by any recipe (so it occurs naturally) and
 * isn't on the "needs silk touch / drops something else" list.
 */
public final class Gatherable {
	/** Mining these gives something else (or nothing) without silk touch, or they can't be reached (End, loot-only). */
	private static final Set<Block> NOT_SELF_DROPPING = Set.of(
			Blocks.GLASS, Blocks.GLASS_PANE, Blocks.ICE, Blocks.PACKED_ICE, Blocks.BLUE_ICE, Blocks.STONE, Blocks.DEEPSLATE,
			Blocks.GRASS_BLOCK, Blocks.MYCELIUM, Blocks.PODZOL, Blocks.GLOWSTONE, Blocks.SEA_LANTERN, Blocks.BOOKSHELF,
			Blocks.CLAY, Blocks.SNOW_BLOCK, Blocks.SNOW, Blocks.MELON, Blocks.TURTLE_EGG, Blocks.BEE_NEST, Blocks.BEEHIVE,
			Blocks.SPAWNER, Blocks.BUDDING_AMETHYST, Blocks.AMETHYST_CLUSTER, Blocks.SHORT_GRASS, Blocks.TALL_GRASS, Blocks.FERN,
			Blocks.LARGE_FERN, Blocks.DEAD_BUSH, Blocks.SEAGRASS, Blocks.COBWEB, Blocks.GILDED_BLACKSTONE, Blocks.SCULK_SENSOR,
			Blocks.REINFORCED_DEEPSLATE, Blocks.FARMLAND, Blocks.DIRT_PATH, Blocks.INFESTED_STONE, Blocks.INFESTED_DEEPSLATE,
			Blocks.STRUCTURE_VOID, Blocks.PETRIFIED_OAK_SLAB, Blocks.SUSPICIOUS_SAND, Blocks.SUSPICIOUS_GRAVEL, Blocks.FROGSPAWN,
			Blocks.END_STONE, Blocks.CHORUS_PLANT, Blocks.CHORUS_FLOWER, Blocks.DRAGON_EGG, Blocks.POWDER_SNOW);
	/** Gather a little extra of cheap staples so one trip covers several recipes. */
	private static final Map<Item, Integer> MIN_BATCH = Map.of(
			Items.COBBLESTONE, 16, Items.RAW_IRON, 5, Items.RAW_GOLD, 5, Items.RAW_COPPER, 5, Items.COAL, 4, Items.SAND, 8);
	private static final int MIN_LOGS = 6;
	/** Natural-looking blocks that are only ever built by players or found in structures. */
	private static final Set<Block> PLAYER_MADE = Set.of(Blocks.BOOKSHELF, Blocks.CRAFTER, Blocks.TNT, Blocks.CAKE, Blocks.HAY_BLOCK,
			Blocks.TARGET, Blocks.REDSTONE_LAMP, Blocks.SEA_LANTERN, Blocks.JACK_O_LANTERN, Blocks.CARVED_PUMPKIN, Blocks.INFESTED_STONE,
			Blocks.INFESTED_DEEPSLATE, Blocks.SPAWNER, Blocks.TRIAL_SPAWNER, Blocks.TRIPWIRE, Blocks.REDSTONE_WIRE);
	/**
	 * Blocks that drop themselves but never generate in the world: they only ever come as loot — heavy cores out of
	 * ominous vaults, sniffer eggs out of suspicious sand. Taken for world blocks, the mace job explored for a heavy
	 * core to mine instead of taking on an ominous trial.
	 */
	private static final Set<Block> LOOT_ONLY = Set.of(Blocks.HEAVY_CORE, Blocks.SNIFFER_EGG);
	/** Rough relative effort, used by the planner to compare gathering with crafting/smelting. */
	static final double NAMED_COST = 8;
	static final double GENERIC_COST = 20;
	static final double OTHER_DIMENSION_PENALTY = 40;
	static final double HUNT_COST = 30;
	/** Blocks that are seldom near: their loot is worth hunting for instead when a mob drops it too. */
	private static final Set<Block> RARE = Set.of(Blocks.COBWEB);
	/** Found only in biomes none of which is around: a trip to find one comes first (rough). */
	static final double FAR_BIOME_FACTOR = 4;

	private Gatherable() {}

	/**
	 * Requirement for having {@code total} of {@code item} in the inventory by mining or hunting, or null.
	 * Between mining loot and hunting the cheaper one wins (string: spiders rather than searching for cobwebs).
	 *
	 * @param flexible any item of the same family would do (e.g. any log for "some planks")
	 */
	public static @Nullable Requirement requirementFor(Item item, int total, Dim here, boolean flexible) {
		if (flexible && item.getDefaultInstance().is(ItemTags.LOGS)) {
			return here == Dim.NETHER
					? new Requirement("stem", s -> s.is(ItemTags.LOGS), Set.of(Blocks.CRIMSON_STEM, Blocks.WARPED_STEM),
							Math.max(total, MIN_LOGS), Requirement.Strategy.ANYWHERE, 0, Dim.NETHER)
					: Resources.get("log", Math.max(total, MIN_LOGS));
		}
		String path = BuiltInRegistries.ITEM.getKey(item).getPath();
		if (Resources.isNamed(path)) {
			Requirement named = Resources.get(path, total);
			return named.withAmount(Math.max(total, MIN_BATCH.getOrDefault(item, 0)));
		}
		Block block = Block.byItem(item);
		if (block != Blocks.AIR && isNatural(block, item)) return Resources.get(path, Math.max(total, MIN_BATCH.getOrDefault(item, 0)));
		Requirement mined = fromLoot(item, total, here);
		Requirement hunted = fromMobs(item, total, here);
		if (mined == null || hunted == null) return mined != null ? mined : hunted;
		return cost(hunted, here) < cost(mined, here) ? hunted : mined;
	}

	/** A block found in the world that drops itself when mined (tripwire is string, but string comes from spiders). */
	public static boolean isNatural(Block block, Item item) {
		if (NOT_SELF_DROPPING.contains(block) || structureOnly(block) || block instanceof AbstractSkullBlock
				|| block.defaultBlockState().is(BlockTags.LEAVES)) {
			return false;
		}
		if (BuiltInRegistries.BLOCK.getKey(block).getPath().contains("_ore")) return false; // ores drop raw items: named resources
		return !RecipeBook.get().isMade(item);
	}

	/** Only ever built by players, found in structures or given as loot: not worth searching the world for. */
	private static boolean structureOnly(Block b) {
		if (PLAYER_MADE.contains(b) || LOOT_ONLY.contains(b)) return true;
		// copper's weathered stages occur in the world only in trial chambers: made in the copper garden instead (it
		// tunnelled twenty minutes for exposed copper, never once near a chamber)
		String path = BuiltInRegistries.BLOCK.getKey(b).getPath();
		return path.startsWith("exposed_") || path.startsWith("weathered_") || path.startsWith("oxidized_");
	}

	/**
	 * Mining loot: world blocks whose loot table includes the item — flint from gravel, string from cobwebs (not
	 * with shears), seeds from grass, sticks from dead bushes, leaves with shears. Null if no block gives it.
	 */
	public static @Nullable Requirement fromLoot(Item item, int total, Dim here) {
		Set<Block> plain = new HashSet<>();
		Set<Block> withShears = new HashSet<>();
		boolean avoidShears = false;
		boolean anyHere = false;
		for (LootBook.BlockDrop d : LootBook.get().blockSources(item)) {
			Block b = d.block();
			if (!isWorldBlock(b) || placedOnly(b) || dimensionOf(b) == null) continue;
			anyHere |= dimensionOf(b) == here;
			switch (d.tool()) {
				case ANY -> plain.add(b);
				case NO_SHEARS -> {
					plain.add(b);
					avoidShears = true;
				}
				case SHEARS -> withShears.add(b);
				case SILK -> {
				}
			}
		}
		Set<Block> sources = plain.isEmpty() ? withShears : plain;
		if (sources.isEmpty()) return null;
		Dim dim = anyHere ? here : dimensionOf(sources.iterator().next());
		sources.removeIf(b -> dimensionOf(b) != dim);
		LootBook.ToolUse tool = plain.isEmpty() ? LootBook.ToolUse.SHEARS : avoidShears ? LootBook.ToolUse.NO_SHEARS : LootBook.ToolUse.ANY;
		boolean surface = sources.stream().allMatch(b -> b.defaultBlockState().is(BlockTags.LEAVES) || b.defaultBlockState().canBeReplaced()
				|| b instanceof VegetationBlock);
		String name = BuiltInRegistries.ITEM.getKey(item).getPath();
		return new Requirement(name, s -> s.is(item), Set.copyOf(sources), total, surface ? Requirement.Strategy.SURFACE : Requirement.Strategy.ANYWHERE,
				0, dim, Set.of(), null, tool, Set.of());
	}

	/** Hunting: mobs whose loot includes the item, in this dimension if any live here. */
	public static @Nullable Requirement fromMobs(Item item, int total, Dim here) {
		Set<EntityType<?>> local = new HashSet<>();
		Set<EntityType<?>> elsewhere = new HashSet<>();
		for (LootBook.MobDrop d : LootBook.get().mobSources(item)) {
			MobHints.Hint h = MobHints.of(d.type());
			if (h.unreachable()) continue;
			(h.dimension() == here ? local : elsewhere).add(d.type());
		}
		Set<EntityType<?>> mobs = local.isEmpty() ? elsewhere : local;
		if (mobs.isEmpty()) return null;
		Dim dim = MobHints.of(mobs.iterator().next()).dimension();
		mobs.removeIf(t -> MobHints.of(t).dimension() != dim);
		Set<ResourceKey<Biome>> biomes = new HashSet<>();
		for (EntityType<?> t : mobs) {
			Set<ResourceKey<Biome>> b = MobHints.of(t).biomes();
			if (b.isEmpty()) {
				biomes.clear(); // one of them lives anywhere: no need to travel
				break;
			}
			biomes.addAll(b);
		}
		return Requirement.hunted(BuiltInRegistries.ITEM.getKey(item).getPath(), item, total, Set.copyOf(mobs), dim, Set.copyOf(biomes));
	}

	/** Blocks that occur in the world: not furniture/containers, placed string/redstone, potted plants, or someone's crops. */
	private static boolean isWorldBlock(Block b) {
		return !(b instanceof BaseEntityBlock) && !(b instanceof CropBlock) && !(b instanceof StemBlock) && !(b instanceof FlowerPotBlock)
				&& !structureOnly(b);
	}

	/**
	 * A crafted or smelted block (iron block, comparator, iron bars, terracotta): mining one gives it back, but none
	 * generate where the bot can count on them, so searching for them never ends — it's made instead. Natural blocks
	 * that drop something else ({@link #NOT_SELF_DROPPING}: glowstone dust, clay balls, snowballs) still count.
	 */
	private static boolean placedOnly(Block b) {
		Item self = b.asItem();
		return self != Items.AIR && !NOT_SELF_DROPPING.contains(b) && RecipeBook.get().isMade(self);
	}

	/** Where a block is found (null: the End or nowhere the bot goes). */
	private static @Nullable Dim dimensionOf(Block b) {
		String path = BuiltInRegistries.BLOCK.getKey(b).getPath();
		if (path.contains("end_stone") || path.contains("chorus") || path.contains("purpur")) return null;
		if (path.contains("nether") || path.contains("crimson") || path.contains("warped") || path.contains("soul_") || path.contains("basalt")
				|| path.contains("blackstone") || b == Blocks.GLOWSTONE || b == Blocks.MAGMA_BLOCK || b == Blocks.SHROOMLIGHT
				|| b == Blocks.ANCIENT_DEBRIS) {
			return Dim.NETHER;
		}
		return Dim.OVERWORLD;
	}

	public static double cost(Requirement r, Dim here) {
		return cost(r, here, null);
	}

	/** @param biomesAround biomes in the loaded area, or null when unknown (no biome judgment then) */
	public static double cost(Requirement r, Dim here, @Nullable Set<ResourceKey<Biome>> biomesAround) {
		double base = Resources.isNamed(r.name()) || r.name().equals("stem") ? NAMED_COST : GENERIC_COST;
		Item item = BuiltInRegistries.ITEM.getOptional(Identifier.withDefaultNamespace(r.name())).orElse(null);
		if (item != null && r.isHunted()) {
			double chance = LootBook.get().mobSources(item).stream().filter(d -> r.mobs().contains(d.type()))
					.mapToDouble(LootBook.MobDrop::chance).max().orElse(1);
			base = HUNT_COST / Math.max(0.05, Math.min(1, chance));
		} else if (item != null && !r.sources().isEmpty() && !Resources.isNamed(r.name())) {
			// loot that only sometimes drops (flint from gravel) costs more per item; rare blocks cost more to find
			double chance = LootBook.get().blockSources(item).stream().filter(d -> r.sources().contains(d.block()))
					.mapToDouble(LootBook.BlockDrop::chance).max().orElse(1);
			base /= Math.max(0.05, Math.min(1, chance));
			if (r.sources().stream().allMatch(RARE::contains)) base *= 3;
		}
		// found only in biomes none of which is around: gathering it starts with a trip to find one — mud lives in
		// mangrove swamps, and without one around a water bottle on dirt is the quicker way (it went exploring)
		if (biomesAround != null && !r.biomes().isEmpty() && Collections.disjoint(r.biomes(), biomesAround)) base *= FAR_BIOME_FACTOR;
		return r.dimension() == here ? base : base + OTHER_DIMENSION_PENALTY;
	}

	static boolean isFlexibleGroup(ItemStack s) {
		return s.is(ItemTags.LOGS) || s.is(ItemTags.PLANKS);
	}
}
