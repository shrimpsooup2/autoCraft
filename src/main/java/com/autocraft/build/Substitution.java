package com.autocraft.build;

import com.autocraft.schematic.BlockStates;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.jspecify.annotations.Nullable;

/**
 * How a schematic's blocks are adapted where it is built, resolved from {@link MaterialSettings}: explicit
 * per-block replacements, wood-type swaps, stand-ins for structural wood, and a filler for hidden blocks.
 *
 * @param woods          wood type → wood type (e.g. oak → birch)
 * @param standIn        family replacing structural wood (planks, logs, stairs, slabs, fences), or null
 * @param overrides      original block → block to build instead (wins over everything else)
 * @param interiorFiller block for cells no one can see (all six neighbors solid), or null
 * @param reason         one-line explanation for chat and the panel
 */
public record Substitution(Map<String, String> woods, @Nullable StandIn standIn, Map<Block, Block> overrides, @Nullable Block interiorFiller,
		String reason) {
	public static final Substitution NONE = new Substitution(Map.of(), null, Map.of(), null, "as designed");

	/** Wood families the bot can swap between (longest names first so "dark_oak" isn't read as "oak"). */
	public static final List<String> WOODS = List.of("pale_oak", "dark_oak", "mangrove", "spruce", "jungle", "acacia", "cherry", "birch", "oak",
			"crimson", "warped");
	private static final Set<String> NETHER = Set.of("crimson", "warped");
	private static final Set<String> SUFFIXES = Set.of("log", "wood", "stem", "hyphae", "planks", "stairs", "slab", "fence", "fence_gate", "door",
			"trapdoor", "button", "pressure_plate", "sign", "wall_sign", "hanging_sign", "wall_hanging_sign", "leaves", "sapling");
	private static final Set<String> STRUCTURAL = Set.of("log", "wood", "stem", "hyphae", "planks", "stairs", "slab", "fence");

	/** Sub-optimal but functional replacements for structural wood. */
	public enum StandIn {
		SANDSTONE("sandstone", "cut_sandstone", "sandstone_stairs", "sandstone_slab", "sandstone_wall"),
		RED_SANDSTONE("red_sandstone", "cut_red_sandstone", "red_sandstone_stairs", "red_sandstone_slab", "red_sandstone_wall"),
		COBBLESTONE("cobblestone", "cobblestone", "cobblestone_stairs", "cobblestone_slab", "cobblestone_wall"),
		STONE_BRICKS("stone_bricks", "stone_bricks", "stone_brick_stairs", "stone_brick_slab", "stone_brick_wall"),
		COBBLED_DEEPSLATE("cobbled_deepslate", "cobbled_deepslate", "cobbled_deepslate_stairs", "cobbled_deepslate_slab", "cobbled_deepslate_wall"),
		BLACKSTONE("blackstone", "blackstone", "blackstone_stairs", "blackstone_slab", "blackstone_wall"),
		MUD_BRICKS("mud_bricks", "mud_bricks", "mud_brick_stairs", "mud_brick_slab", "mud_brick_wall"),
		BRICKS("bricks", "bricks", "brick_stairs", "brick_slab", "brick_wall");

		private final String full;
		private final String pillar;
		private final String stairs;
		private final String slab;
		private final String wall;

		StandIn(String full, String pillar, String stairs, String slab, String wall) {
			this.full = full;
			this.pillar = pillar;
			this.stairs = stairs;
			this.slab = slab;
			this.wall = wall;
		}

		public String label() {
			return name().charAt(0) + name().substring(1).toLowerCase(Locale.ROOT).replace('_', ' ');
		}

		BlockState replace(BlockState original, String suffix) {
			String id = switch (suffix) {
				case "planks" -> full;
				case "stairs" -> stairs;
				case "slab" -> slab;
				case "fence" -> wall;
				default -> pillar;
			};
			BlockState target = BlockStates.parse("minecraft:" + id);
			if (target == null) return original;
			return suffix.equals("stairs") || suffix.equals("slab") ? carryProps(original, target) : target;
		}
	}

	/** A wood family block: its wood type and suffix ("oak", "stairs"), or null. */
	public record WoodPart(String wood, String suffix, boolean stripped) {}

	public static @Nullable WoodPart woodPart(Block block) {
		String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
		boolean stripped = path.startsWith("stripped_");
		if (stripped) path = path.substring("stripped_".length());
		for (String wood : WOODS) {
			if (!path.startsWith(wood + "_")) continue;
			String suffix = path.substring(wood.length() + 1);
			return SUFFIXES.contains(suffix) ? new WoodPart(wood, suffix, stripped) : null;
		}
		return null;
	}

	public boolean isIdentity() {
		return woods.isEmpty() && standIn == null && overrides.isEmpty() && interiorFiller == null;
	}

	/** The block to build for {@code original} (before rotation/mirror; interior filling is done by the plan). */
	public BlockState apply(BlockState original) {
		Block override = overrides.get(original.getBlock());
		if (override != null) return carryProps(original, override.defaultBlockState());
		WoodPart part = woodPart(original.getBlock());
		if (part == null) return original;
		if (standIn != null && STRUCTURAL.contains(part.suffix)) return standIn.replace(original, part.suffix);
		String to = woods.get(part.wood);
		return to == null || to.equals(part.wood) ? original : swapWood(original, part, to);
	}

	private static BlockState swapWood(BlockState s, WoodPart part, String to) {
		String suffix = part.suffix;
		boolean toNether = NETHER.contains(to);
		if (toNether && suffix.equals("log")) suffix = "stem";
		else if (toNether && suffix.equals("wood")) suffix = "hyphae";
		else if (!toNether && suffix.equals("stem")) suffix = "log";
		else if (!toNether && suffix.equals("hyphae")) suffix = "wood";
		BlockState swapped = BlockStates.parse("minecraft:" + (part.stripped ? "stripped_" : "") + to + "_" + suffix);
		return swapped == null ? s : carryProps(s, swapped); // no such block (crimson leaves...): keep the original
	}

	/** Copy every property both states have (facing, half, type, waterlogged...). */
	static BlockState carryProps(BlockState from, BlockState to) {
		Map<String, String> props = new HashMap<>();
		for (Property<?> prop : from.getProperties()) props.put(prop.getName(), valueName(from, prop));
		return BlockStates.withProps(to, props);
	}

	private static <T extends Comparable<T>> String valueName(BlockState s, Property<T> prop) {
		return prop.getName(s.getValue(prop));
	}

	// ---- wire format (team members must build exactly the same thing) ---------------------------

	/** Single token, no spaces: {@code w=oak>birch,spruce>birch;s=SANDSTONE;o=oak_door>iron_door;f=cobblestone}. */
	public String encode() {
		List<String> parts = new ArrayList<>();
		if (!woods.isEmpty()) {
			parts.add("w=" + woods.entrySet().stream().map(e -> e.getKey() + ">" + e.getValue()).collect(Collectors.joining(",")));
		}
		if (standIn != null) parts.add("s=" + standIn.name());
		if (!overrides.isEmpty()) {
			parts.add("o=" + overrides.entrySet().stream().map(e -> id(e.getKey()) + ">" + id(e.getValue())).collect(Collectors.joining(",")));
		}
		if (interiorFiller != null) parts.add("f=" + id(interiorFiller));
		return parts.isEmpty() ? "-" : String.join(";", parts);
	}

	public static Substitution decode(String token) {
		if (token.equals("-")) return NONE;
		Map<String, String> woods = new LinkedHashMap<>();
		StandIn standIn = null;
		Map<Block, Block> overrides = new LinkedHashMap<>();
		Block filler = null;
		for (String part : token.split(";")) {
			int eq = part.indexOf('=');
			if (eq < 0) continue;
			String value = part.substring(eq + 1);
			switch (part.substring(0, eq)) {
				case "w" -> pairs(value).forEach(woods::put);
				case "s" -> standIn = StandIn.valueOf(value);
				case "o" -> pairs(value).forEach((a, b) -> {
					Block from = block(a);
					Block to = block(b);
					if (from != null && to != null) overrides.put(from, to);
				});
				case "f" -> filler = block(value);
				default -> {
				}
			}
		}
		return new Substitution(woods, standIn, overrides, filler, "as chosen by the team leader");
	}

	private static Map<String, String> pairs(String value) {
		Map<String, String> out = new LinkedHashMap<>();
		for (String pair : value.split(",")) {
			int gt = pair.indexOf('>');
			if (gt > 0) out.put(pair.substring(0, gt), pair.substring(gt + 1));
		}
		return out;
	}

	public static String id(Block b) {
		return BuiltInRegistries.BLOCK.getKey(b).getPath();
	}

	public static @Nullable Block block(String id) {
		Identifier rl = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
		if (rl == null) return null;
		Block b = BuiltInRegistries.BLOCK.getOptional(rl).orElse(null);
		return b == null || b == Blocks.AIR ? null : b;
	}

	/** Wood types a schematic uses, most used first. */
	public static List<String> woodsUsed(Iterable<BlockState> states) {
		Map<String, Integer> counts = new HashMap<>();
		for (BlockState s : states) {
			WoodPart part = woodPart(s.getBlock());
			if (part != null) counts.merge(part.wood, 1, Integer::sum);
		}
		return counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())).map(Map.Entry::getKey)
				.toList();
	}
}
