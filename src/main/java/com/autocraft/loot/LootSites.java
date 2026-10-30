package com.autocraft.loot;

import com.autocraft.goal.Requirement.Dim;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.maps.MapDecorationType;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import org.jspecify.annotations.Nullable;

/**
 * Where structure loot is and how to recognise the place: dimension, depth (tunnel at a Y level, or walk the
 * surface), biomes to head for, tell-tale blocks or mobs, what holds the loot (chests and barrels, chest
 * minecarts, suspicious sand/gravel to brush, a chest buried at a treasure map's X), how many per structure, a
 * rough effort to find one, whether it's an ancient city (no noise near sculk), and which explorer-map markers
 * point at it.
 *
 * <p>Every structure with loot found nowhere else is here. Left out, because everything in them comes from
 * other places too: igloos, underwater ruin chests (their treasure maps are also in shipwrecks; their
 * suspicious sand/gravel is here), the spawn bonus chest. End cities (elytra) need End travel, which the bot
 * doesn't do. Trial chamber vaults are handled by their own procedure.
 */
public final class LootSites {
	public enum Holds { CHESTS, MINECARTS, SUSPICIOUS_BLOCKS, BURIED_AT_MAP_X }

	/**
	 * @param y          tunnel at this level; null = walk the surface
	 * @param containers chests (or minecarts, suspicious blocks) in a typical one
	 * @param effort     rough cost of finding one, in planner units
	 * @param quiet      an ancient city: no noise near sculk sensors and shriekers
	 * @param maps       explorer-map markers that point at this structure
	 */
	public record Site(String name, Dim dim, @Nullable Integer y, Set<ResourceKey<Biome>> biomes, Predicate<BlockState> tell,
			Set<EntityType<?>> tellMobs, Holds holds, int containers, double effort, boolean quiet, Set<Holder<MapDecorationType>> maps) {}

	private static final Set<ResourceKey<Biome>> VILLAGE_BIOMES = Set.of(Biomes.PLAINS, Biomes.SAVANNA, Biomes.DESERT, Biomes.TAIGA,
			Biomes.SNOWY_PLAINS, Biomes.MEADOW, Biomes.SUNFLOWER_PLAINS);
	private static final Set<ResourceKey<Biome>> FORESTS = Set.of(Biomes.FOREST, Biomes.BIRCH_FOREST, Biomes.FLOWER_FOREST, Biomes.TAIGA,
			Biomes.OLD_GROWTH_PINE_TAIGA, Biomes.OLD_GROWTH_SPRUCE_TAIGA, Biomes.MEADOW, Biomes.CHERRY_GROVE, Biomes.SAVANNA, Biomes.SNOWY_TAIGA,
			Biomes.SPARSE_JUNGLE, Biomes.BAMBOO_JUNGLE, Biomes.SWAMP, Biomes.WINDSWEPT_FOREST, Biomes.WOODED_BADLANDS, Biomes.PALE_GARDEN,
			Biomes.OLD_GROWTH_BIRCH_FOREST);
	private static final Set<ResourceKey<Biome>> OCEANS = Set.of(Biomes.OCEAN, Biomes.DEEP_OCEAN, Biomes.LUKEWARM_OCEAN, Biomes.DEEP_LUKEWARM_OCEAN,
			Biomes.COLD_OCEAN, Biomes.DEEP_COLD_OCEAN, Biomes.WARM_OCEAN, Biomes.BEACH, Biomes.SNOWY_BEACH);

	private static final Site DUNGEON = site("dungeon", Dim.OVERWORLD, 10, Set.of(), s -> s.is(Blocks.SPAWNER) || s.is(Blocks.MOSSY_COBBLESTONE),
			Holds.CHESTS, 2, 200);
	private static final Site MINESHAFT = new Site("mineshaft", Dim.OVERWORLD, 10, Set.of(), s -> s.is(Blocks.RAIL) || s.is(Blocks.COBWEB), Set.of(),
			Holds.MINECARTS, 6, 220, false, Set.of(MapDecorationTypes.MINESHAFT));
	private static final Site RUINED_PORTAL = site("ruined portal", Dim.OVERWORLD, null, Set.of(), s -> s.is(Blocks.CRYING_OBSIDIAN), Holds.CHESTS, 1,
			120);
	private static final Site VILLAGE = new Site("village", Dim.OVERWORLD, null, VILLAGE_BIOMES, s -> s.is(Blocks.BELL) || s.is(Blocks.COMPOSTER)
			|| s.is(Blocks.SMOKER) || s.is(Blocks.FLETCHING_TABLE), Set.of(), Holds.CHESTS, 3, 100, false, Set.of(MapDecorationTypes.DESERT_VILLAGE,
			MapDecorationTypes.PLAINS_VILLAGE, MapDecorationTypes.SAVANNA_VILLAGE, MapDecorationTypes.SNOWY_VILLAGE, MapDecorationTypes.TAIGA_VILLAGE));
	private static final Site FORTRESS = site("nether fortress", Dim.NETHER, 70, Set.of(), s -> s.is(Blocks.NETHER_BRICKS) || s.is(Blocks.NETHER_BRICK_FENCE),
			Holds.CHESTS, 3, 200);
	private static final Site BASTION = site("bastion", Dim.NETHER, 60, Set.of(), s -> s.is(Blocks.POLISHED_BLACKSTONE_BRICKS) || s.is(Blocks.GILDED_BLACKSTONE),
			Holds.CHESTS, 6, 450);
	private static final Site STRONGHOLD = site("stronghold", Dim.OVERWORLD, 25, Set.of(), s -> s.is(Blocks.END_PORTAL_FRAME)
			|| s.is(Blocks.INFESTED_STONE_BRICKS), Holds.CHESTS, 4, 600);
	private static final Site ANCIENT_CITY = new Site("ancient city", Dim.OVERWORLD, -51, Set.of(Biomes.DEEP_DARK), s -> s.is(Blocks.REINFORCED_DEEPSLATE)
			|| s.is(Blocks.SCULK_SHRIEKER) || s.is(Blocks.DEEPSLATE_TILES), Set.of(), Holds.CHESTS, 15, 380, true, Set.of(MapDecorationTypes.ANCIENT_CITY));
	private static final Site TRIAL_CHAMBER = new Site("trial chamber", Dim.OVERWORLD, -30, Set.of(), s -> s.is(Blocks.TUFF_BRICKS)
			|| s.is(Blocks.CHISELED_TUFF) || s.is(Blocks.TRIAL_SPAWNER), Set.of(), Holds.CHESTS, 8, 220, false, Set.of(MapDecorationTypes.TRIAL_CHAMBERS));
	/** Pressure plate + TNT under the floor: the pathfinder never steps on pressure plates. */
	private static final Site DESERT_PYRAMID = new Site("desert pyramid", Dim.OVERWORLD, null, Set.of(Biomes.DESERT),
			s -> s.is(Blocks.DYED_TERRACOTTA.pick(DyeColor.BLUE)),
			Set.of(), Holds.CHESTS, 4, 200, false, Set.of(MapDecorationTypes.DESERT_PYRAMID));
	/** Tripwires to arrow dispensers: the pathfinder never walks into tripwire; it digs around. */
	private static final Site JUNGLE_TEMPLE = new Site("jungle temple", Dim.OVERWORLD, null, Set.of(Biomes.JUNGLE, Biomes.BAMBOO_JUNGLE,
			Biomes.SPARSE_JUNGLE), s -> s.is(Blocks.TRIPWIRE_HOOK), Set.of(), Holds.CHESTS, 2, 220, false, Set.of(MapDecorationTypes.JUNGLE_TEMPLE));
	private static final Site SHIPWRECK = site("shipwreck", Dim.OVERWORLD, null, OCEANS,
			s -> s.getBlock() instanceof ChestBlock && s.hasProperty(ChestBlock.WATERLOGGED) && s.getValue(ChestBlock.WATERLOGGED), Holds.CHESTS, 2, 180);
	private static final Site BURIED_TREASURE = new Site("buried treasure", Dim.OVERWORLD, null, Set.of(), s -> false, Set.of(), Holds.BURIED_AT_MAP_X, 1,
			120, false, Set.of(MapDecorationTypes.RED_X));
	private static final Site OUTPOST = new Site("pillager outpost", Dim.OVERWORLD, null, VILLAGE_BIOMES, s -> false, Set.of(EntityTypes.PILLAGER),
			Holds.CHESTS, 1, 220, false, Set.of());
	private static final Site MANSION = new Site("woodland mansion", Dim.OVERWORLD, null, Set.of(Biomes.DARK_FOREST, Biomes.PALE_GARDEN),
			s -> s.is(Blocks.DARK_OAK_PLANKS) || s.is(Blocks.BIRCH_PLANKS), Set.of(EntityTypes.VINDICATOR, EntityTypes.EVOKER), Holds.CHESTS, 8, 700, false,
			Set.of(MapDecorationTypes.WOODLAND_MANSION));
	private static final Site CAMP = new Site("abandoned camp", Dim.OVERWORLD, null, FORESTS, s -> s.is(Blocks.CAMPFIRE) || s.is(Blocks.STRAW_BED),
			Set.of(), Holds.CHESTS, 2, 120, false, Set.of(MapDecorationTypes.ABANDONED_CAMP));
	private static final Site PYRAMID_SAND = site("desert pyramid (suspicious sand)", Dim.OVERWORLD, null, Set.of(Biomes.DESERT),
			s -> s.is(Blocks.SUSPICIOUS_SAND), Holds.SUSPICIOUS_BLOCKS, 8, 220);
	private static final Site DESERT_WELL = site("desert well", Dim.OVERWORLD, null, Set.of(Biomes.DESERT), s -> s.is(Blocks.SUSPICIOUS_SAND),
			Holds.SUSPICIOUS_BLOCKS, 1, 200);
	private static final Site COLD_RUINS = site("cold ocean ruins", Dim.OVERWORLD, null, Set.of(Biomes.COLD_OCEAN, Biomes.DEEP_COLD_OCEAN, Biomes.OCEAN,
			Biomes.DEEP_OCEAN, Biomes.FROZEN_OCEAN), s -> s.is(Blocks.SUSPICIOUS_GRAVEL), Holds.SUSPICIOUS_BLOCKS, 6, 220);
	private static final Site WARM_RUINS = new Site("warm ocean ruins", Dim.OVERWORLD, null, Set.of(Biomes.WARM_OCEAN, Biomes.LUKEWARM_OCEAN,
			Biomes.DEEP_LUKEWARM_OCEAN), s -> s.is(Blocks.SUSPICIOUS_SAND), Set.of(), Holds.SUSPICIOUS_BLOCKS, 6, 220, false,
			Set.of(MapDecorationTypes.OCEAN_RUIN_WARM));
	private static final Site TRAIL_RUINS = site("trail ruins", Dim.OVERWORLD, null, Set.of(Biomes.TAIGA, Biomes.SNOWY_TAIGA, Biomes.OLD_GROWTH_BIRCH_FOREST,
			Biomes.OLD_GROWTH_PINE_TAIGA, Biomes.OLD_GROWTH_SPRUCE_TAIGA, Biomes.JUNGLE), s -> s.is(Blocks.SUSPICIOUS_GRAVEL), Holds.SUSPICIOUS_BLOCKS, 8, 260);
	/** Structures whose guards (spawners, illagers, the warden, piglins) make dying there likely. */
	private static final Set<Site> GUARDED = Set.of(DUNGEON, STRONGHOLD, ANCIENT_CITY, TRIAL_CHAMBER, OUTPOST, MANSION, FORTRESS, BASTION);

	private LootSites() {}

	private static Site site(String name, Dim dim, @Nullable Integer y, Set<ResourceKey<Biome>> biomes, Predicate<BlockState> tell, Holds holds,
			int containers, double effort) {
		return new Site(name, dim, y, biomes, tell, Set.of(), holds, containers, effort, false, Set.of());
	}

	/**
	 * The structures a go-to task can head for: every site above but the buried treasure (nothing shows where it is
	 * without a map) and the pyramid's suspicious sand (the desert pyramid itself is in the list).
	 */
	public static List<Site> structures() {
		return List.of(VILLAGE, DESERT_PYRAMID, JUNGLE_TEMPLE, OUTPOST, MANSION, CAMP, SHIPWRECK, RUINED_PORTAL, DESERT_WELL, TRAIL_RUINS, COLD_RUINS,
				WARM_RUINS, DUNGEON, MINESHAFT, STRONGHOLD, TRIAL_CHAMBER, ANCIENT_CITY, FORTRESS, BASTION);
	}

	/**
	 * The structure called {@code name} ({@code village}, {@code nether_fortress}): the exact name, else the only one
	 * whose name contains it ({@code fortress}, {@code pyramid}); null when none or several do.
	 */
	public static @Nullable Site structure(String name) {
		String n = name.trim().toLowerCase().replace('_', ' ');
		List<Site> all = structures();
		for (Site s : all) {
			if (s.name().equals(n)) return s;
		}
		List<Site> partial = all.stream().filter(s -> s.name().contains(n)).toList();
		return partial.size() == 1 ? partial.getFirst() : null;
	}

	/** Guarded by mobs: dying there is likely (see {@link #GUARDED}). */
	public static boolean guarded(Site site) {
		return GUARDED.contains(site);
	}

	/** Trial chambers, for the vault procedure (its loot is a procedure of its own, not a chest table). */
	public static Site trialChamber() {
		return TRIAL_CHAMBER;
	}

	/** The site for a loot table ({@code ancient_city}, {@code village/village_armorer}, {@code archaeology/desert_well}...), or null. */
	public static @Nullable Site forTable(String table) {
		if (table.startsWith("village/")) return VILLAGE;
		if (table.startsWith("bastion_")) return BASTION;
		if (table.startsWith("stronghold_")) return STRONGHOLD;
		if (table.startsWith("ancient_city")) return ANCIENT_CITY;
		if (table.startsWith("shipwreck_")) return SHIPWRECK;
		if (table.startsWith("abandoned_camp")) return CAMP;
		if (table.startsWith("archaeology/trail_ruins")) return TRAIL_RUINS;
		if (table.startsWith("trial_chambers/")) return table.contains("reward") ? null : TRIAL_CHAMBER; // vaults: their own procedure
		return switch (table) {
			case "simple_dungeon" -> DUNGEON;
			case "abandoned_mineshaft" -> MINESHAFT;
			case "ruined_portal" -> RUINED_PORTAL;
			case "nether_bridge" -> FORTRESS;
			case "desert_pyramid" -> DESERT_PYRAMID;
			case "jungle_temple" -> JUNGLE_TEMPLE;
			case "buried_treasure" -> BURIED_TREASURE;
			case "pillager_outpost" -> OUTPOST;
			case "woodland_mansion" -> MANSION;
			case "archaeology/desert_pyramid" -> PYRAMID_SAND;
			case "archaeology/desert_well" -> DESERT_WELL;
			case "archaeology/ocean_ruin_cold" -> COLD_RUINS;
			case "archaeology/ocean_ruin_warm" -> WARM_RUINS;
			default -> null; // igloo, underwater ruin chests, spawn bonus chest (nothing unique), end city (no End travel)
		};
	}
}
