package com.autocraft.hunt;

import com.autocraft.goal.Requirement.Dim;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Where to look for a mob: its dimension, biomes it lives in, and for structure-bound mobs (blazes in fortresses,
 * guardians at monuments, breezes in trial chambers) blocks that give the structure away from a distance.
 */
public final class MobHints {
	/**
	 * @param dimension       where it spawns
	 * @param biomes          biomes to head for (empty = anywhere)
	 * @param structureBlocks seeing one of these means the mob's structure is near; walk there
	 * @param dark            spawns in the dark (night on the surface, caves any time)
	 * @param unreachable     not hunted by the bot (End mobs, bosses, zombified piglins that answer as a pack)
	 */
	public record Hint(Dim dimension, Set<ResourceKey<Biome>> biomes, Set<Block> structureBlocks, boolean dark, boolean unreachable) {}

	private static final Hint ANYWHERE = new Hint(Dim.OVERWORLD, Set.of(), Set.of(), false, false);
	private static final Map<EntityType<?>, Hint> HINTS = new HashMap<>();

	static {
		for (EntityType<?> t : Set.of(EntityTypes.ZOMBIE, EntityTypes.SKELETON, EntityTypes.SPIDER, EntityTypes.CREEPER, EntityTypes.ENDERMAN,
				EntityTypes.CAVE_SPIDER)) {
			dark(t);
		}
		put(EntityTypes.HUSK, Dim.OVERWORLD, Set.of(Biomes.DESERT), Set.of(), true);
		put(EntityTypes.STRAY, Dim.OVERWORLD, Set.of(Biomes.SNOWY_PLAINS, Biomes.ICE_SPIKES, Biomes.FROZEN_RIVER), Set.of(), true);
		put(EntityTypes.DROWNED, Dim.OVERWORLD, Set.of(Biomes.RIVER, Biomes.OCEAN, Biomes.DEEP_OCEAN, Biomes.WARM_OCEAN), Set.of(), true);
		put(EntityTypes.WITCH, Dim.OVERWORLD, Set.of(Biomes.SWAMP, Biomes.MANGROVE_SWAMP), Set.of(), true);
		put(EntityTypes.SLIME, Dim.OVERWORLD, Set.of(Biomes.SWAMP, Biomes.MANGROVE_SWAMP), Set.of(), true);
		put(EntityTypes.BOGGED, Dim.OVERWORLD, Set.of(Biomes.SWAMP, Biomes.MANGROVE_SWAMP), Set.of(), true);
		put(EntityTypes.SQUID, Dim.OVERWORLD, Set.of(Biomes.RIVER, Biomes.OCEAN, Biomes.LUKEWARM_OCEAN, Biomes.COLD_OCEAN), Set.of(), false);
		put(EntityTypes.GLOW_SQUID, Dim.OVERWORLD, Set.of(Biomes.LUSH_CAVES, Biomes.DRIPSTONE_CAVES), Set.of(), false);
		put(EntityTypes.GUARDIAN, Dim.OVERWORLD, Set.of(Biomes.DEEP_OCEAN, Biomes.DEEP_COLD_OCEAN, Biomes.DEEP_LUKEWARM_OCEAN),
				Set.of(Blocks.PRISMARINE, Blocks.PRISMARINE_BRICKS, Blocks.DARK_PRISMARINE), false);
		put(EntityTypes.ELDER_GUARDIAN, Dim.OVERWORLD, Set.of(Biomes.DEEP_OCEAN), Set.of(Blocks.PRISMARINE_BRICKS, Blocks.DARK_PRISMARINE), false);
		put(EntityTypes.BREEZE, Dim.OVERWORLD, Set.of(), Set.of(Blocks.TRIAL_SPAWNER, Blocks.VAULT, Blocks.TUFF_BRICKS, Blocks.CHISELED_TUFF), false);
		put(EntityTypes.RABBIT, Dim.OVERWORLD, Set.of(Biomes.DESERT, Biomes.FLOWER_FOREST, Biomes.SNOWY_PLAINS, Biomes.MEADOW), Set.of(), false);
		put(EntityTypes.ARMADILLO, Dim.OVERWORLD, Set.of(Biomes.SAVANNA, Biomes.SAVANNA_PLATEAU, Biomes.BADLANDS), Set.of(), false);
		put(EntityTypes.GOAT, Dim.OVERWORLD, Set.of(Biomes.SNOWY_SLOPES, Biomes.JAGGED_PEAKS, Biomes.FROZEN_PEAKS, Biomes.MEADOW), Set.of(), false);
		put(EntityTypes.POLAR_BEAR, Dim.OVERWORLD, Set.of(Biomes.ICE_SPIKES, Biomes.SNOWY_PLAINS, Biomes.FROZEN_OCEAN), Set.of(), false);
		put(EntityTypes.PANDA, Dim.OVERWORLD, Set.of(Biomes.BAMBOO_JUNGLE, Biomes.JUNGLE), Set.of(), false);
		put(EntityTypes.FOX, Dim.OVERWORLD, Set.of(Biomes.TAIGA, Biomes.SNOWY_TAIGA, Biomes.OLD_GROWTH_PINE_TAIGA), Set.of(), false);
		put(EntityTypes.FROG, Dim.OVERWORLD, Set.of(Biomes.SWAMP, Biomes.MANGROVE_SWAMP), Set.of(), false);
		put(EntityTypes.CAMEL, Dim.OVERWORLD, Set.of(Biomes.DESERT), Set.of(), false);
		put(EntityTypes.TURTLE, Dim.OVERWORLD, Set.of(Biomes.BEACH), Set.of(), false);
		put(EntityTypes.COD, Dim.OVERWORLD, Set.of(Biomes.OCEAN, Biomes.COLD_OCEAN), Set.of(), false);
		put(EntityTypes.SALMON, Dim.OVERWORLD, Set.of(Biomes.RIVER, Biomes.COLD_OCEAN), Set.of(), false);
		put(EntityTypes.PUFFERFISH, Dim.OVERWORLD, Set.of(Biomes.WARM_OCEAN, Biomes.LUKEWARM_OCEAN), Set.of(), false);
		put(EntityTypes.TROPICAL_FISH, Dim.OVERWORLD, Set.of(Biomes.WARM_OCEAN, Biomes.LUSH_CAVES), Set.of(), false);
		put(EntityTypes.MOOSHROOM, Dim.OVERWORLD, Set.of(Biomes.MUSHROOM_FIELDS), Set.of(), false);
		put(EntityTypes.EVOKER, Dim.OVERWORLD, Set.of(Biomes.DARK_FOREST), Set.of(Blocks.DARK_OAK_PLANKS), false);
		put(EntityTypes.PILLAGER, Dim.OVERWORLD, Set.of(Biomes.PLAINS, Biomes.SAVANNA, Biomes.DESERT, Biomes.TAIGA), Set.of(Blocks.DARK_OAK_LOG),
				false);

		Set<Block> fortress = Set.of(Blocks.NETHER_BRICKS, Blocks.NETHER_BRICK_FENCE, Blocks.NETHER_BRICK_STAIRS);
		put(EntityTypes.BLAZE, Dim.NETHER, Set.of(), fortress, false);
		put(EntityTypes.WITHER_SKELETON, Dim.NETHER, Set.of(), fortress, false);
		put(EntityTypes.GHAST, Dim.NETHER, Set.of(Biomes.SOUL_SAND_VALLEY, Biomes.BASALT_DELTAS, Biomes.NETHER_WASTES), Set.of(), false);
		put(EntityTypes.MAGMA_CUBE, Dim.NETHER, Set.of(Biomes.BASALT_DELTAS), Set.of(), false);
		put(EntityTypes.HOGLIN, Dim.NETHER, Set.of(Biomes.CRIMSON_FOREST), Set.of(), false);
		put(EntityTypes.PIGLIN, Dim.NETHER, Set.of(Biomes.CRIMSON_FOREST, Biomes.NETHER_WASTES), Set.of(), false);
		// hitting one turns every zombified piglin around on the bot (it died to a pack hunting them for gold)
		HINTS.put(EntityTypes.ZOMBIFIED_PIGLIN, new Hint(Dim.NETHER, Set.of(Biomes.NETHER_WASTES), Set.of(), false, true));
		put(EntityTypes.STRIDER, Dim.NETHER, Set.of(), Set.of(), false);
		put(EntityTypes.PIGLIN_BRUTE, Dim.NETHER, Set.of(), Set.of(Blocks.GILDED_BLACKSTONE, Blocks.POLISHED_BLACKSTONE_BRICKS), false);

		for (EntityType<?> t : Set.of(EntityTypes.SHULKER, EntityTypes.ENDERMITE, EntityTypes.ENDER_DRAGON, EntityTypes.WITHER, EntityTypes.WARDEN,
				EntityTypes.PHANTOM, EntityTypes.VEX, EntityTypes.ILLUSIONER, EntityTypes.GIANT, EntityTypes.PLAYER, EntityTypes.ARMOR_STAND)) {
			HINTS.put(t, new Hint(Dim.OVERWORLD, Set.of(), Set.of(), false, true));
		}
	}

	private MobHints() {}

	public static Hint of(EntityType<?> type) {
		return HINTS.getOrDefault(type, ANYWHERE);
	}

	private static void dark(EntityType<?> t) {
		HINTS.put(t, new Hint(Dim.OVERWORLD, Set.of(), Set.of(), true, false));
	}

	private static void put(EntityType<?> t, Dim dim, Set<ResourceKey<Biome>> biomes, Set<Block> blocks, boolean dark) {
		HINTS.put(t, new Hint(dim, biomes, blocks, dark, false));
	}
}
