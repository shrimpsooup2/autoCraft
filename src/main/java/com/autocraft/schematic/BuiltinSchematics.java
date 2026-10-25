package com.autocraft.schematic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Structures that ship with the mod, drawn as text layers (bottom layer first; each layer is rows along +Z,
 * characters along +X). {@code ' '} = leave as is, {@code '.'} = must be air. Oak blocks are swapped for
 * whatever wood the bot has when it builds (see BuildPlan).
 */
public final class BuiltinSchematics {
	private BuiltinSchematics() {}

	public static List<Schematic> all() {
		return List.of(starterBase(), cobblestoneGenerator(), sugarCaneFarm(), treeFarm(), wheatFarm(), mobFarm());
	}

	/**
	 * 7×7 cobblestone hut: door in the front (z = 0), glass windows, crafting table + furnace + double chest on
	 * the back wall, two torches. The base marker goes in front of the chests.
	 */
	private static Schematic starterBase() {
		Map<Character, String> legend = Map.of(
				'c', "minecraft:cobblestone",
				'g', "minecraft:glass",
				'D', "minecraft:oak_door[facing=south,half=lower,hinge=left,open=false]",
				'd', "minecraft:oak_door[facing=south,half=upper,hinge=left,open=false]",
				't', "minecraft:torch",
				'T', "minecraft:crafting_table",
				'F', "minecraft:furnace[facing=north]",
				'K', "minecraft:chest[facing=north]",
				'.', "minecraft:air");
		String[][] layers = {
				{"ccccccc", "ccccccc", "ccccccc", "ccccccc", "ccccccc", "ccccccc", "ccccccc"},
				{"cccDccc", "ct...tc", "c.....c", "c.....c", "c.....c", "cTF.KKc", "ccccccc"},
				{"ccgdgcc", "c.....c", "c.....c", "g.....g", "c.....c", "c.....c", "ccgggcc"},
				{"ccccccc", "c.....c", "c.....c", "c.....c", "c.....c", "c.....c", "ccccccc"},
				{"ccccccc", "ccccccc", "ccccccc", "ccccccc", "ccccccc", "ccccccc", "ccccccc"}};
		return design("starter_base", legend, layers, null, new BlockPos(4, 1, 4));
	}

	/**
	 * Lava (west end) flows toward water (east end); the water drops into a pit before it can reach the lava,
	 * so flowing lava meeting it at the output cell turns to cobblestone again and again. The bot mines the
	 * output standing in the wall gap beside it. Water is placed before lava.
	 */
	private static Schematic cobblestoneGenerator() {
		Map<Character, String> legend = Map.of(
				'c', "minecraft:cobblestone",
				'L', "minecraft:lava",
				'W', "minecraft:water",
				'.', "minecraft:air");
		String[][] layers = {
				{"ccccc", "ccccc", "ccccc", "ccccc"},
				{"ccccc", "ccccc", "ccc.c", "ccccc"},
				{"  .  ", "cc.cc", "L...W", "ccccc"},
				{"  .  ", "  .  ", "     ", "     "}};
		// stand in the wall gap right next to the output so drops land in pickup range, not in the lava
		Schematic.Farm farm = new Schematic.Farm("cobblestone", Schematic.Farm.Mode.COBBLE, new BlockPos(2, 2, 2), new BlockPos(2, 2, 1));
		return design("cobblestone_generator", legend, layers, farm, null);
	}

	/** Two rows of nine sugar cane along a water channel, on dirt, capped so the water stays put. */
	private static Schematic sugarCaneFarm() {
		Map<Character, String> legend = Map.of(
				'd', "minecraft:dirt",
				'W', "minecraft:water",
				'S', "minecraft:sugar_cane",
				'.', "minecraft:air");
		String[][] layers = {
				{"ddddddddddd", "ddddddddddd", "ddddddddddd"},
				{"ddddddddddd", "dWWWWWWWWWd", "ddddddddddd"},
				{" SSSSSSSSS ", "...........", " SSSSSSSSS "},
				{" ......... ", "           ", " ......... "}};
		return design("sugar_cane_farm", legend, layers, new Schematic.Farm("sugar_cane", Schematic.Farm.Mode.CANE, null, null), null);
	}

	/** Nine saplings on a 9×9 dirt pad, four apart, with the space above cleared for the trees. */
	private static Schematic treeFarm() {
		Map<Character, String> legend = Map.of(
				'd', "minecraft:dirt",
				'P', "minecraft:oak_sapling",
				'.', "minecraft:air");
		String dirt = "ddddddddd", saplings = "P...P...P", air = ".........";
		String[] pad = {dirt, dirt, dirt, dirt, dirt, dirt, dirt, dirt, dirt};
		String[] plant = {saplings, air, air, air, saplings, air, air, air, saplings};
		String[] open = {air, air, air, air, air, air, air, air, air};
		String[][] layers = {pad, plant, open, open, open, open, open, open};
		return design("tree_farm", legend, layers, new Schematic.Farm("log", Schematic.Farm.Mode.TREE, null, null), null);
	}

	/** 9×9 farmland around one water block, sown with wheat (needs a hoe and seeds). */
	private static Schematic wheatFarm() {
		Map<Character, String> legend = Map.of(
				'd', "minecraft:dirt",
				'f', "minecraft:farmland",
				'W', "minecraft:water",
				'h', "minecraft:wheat[age=0]",
				'.', "minecraft:air");
		String dirt = "ddddddddd", soil = "fffffffff", crops = "hhhhhhhhh";
		String[] pad = {dirt, dirt, dirt, dirt, dirt, dirt, dirt, dirt, dirt};
		String[] land = {soil, soil, soil, soil, "ffffWffff", soil, soil, soil, soil};
		String[] sown = {crops, crops, crops, crops, "hhhh.hhhh", crops, crops, crops, crops};
		String[][] layers = {pad, land, sown};
		return design("wheat_farm", legend, layers, new Schematic.Farm("wheat", Schematic.Farm.Mode.CROP, null, null), null);
	}

	/**
	 * Dark-room mob farm: a roofed 9×9 room (3 high, so endermen fit) 26 blocks up, with a 2×2 hole in the floor
	 * over an enclosed shaft. Mobs spawn in the dark, wander into the hole and die from the fall (26 blocks), so
	 * their drops pile up on the lit landing at the bottom; the bot waits in the doorway beside it. Standing there
	 * it is more than 24 blocks from the spawning floor, as spawning requires. Drops that need a player kill
	 * (spider eyes) don't come from here.
	 */
	private static Schematic mobFarm() {
		Map<Character, String> legend = Map.of(
				'c', "minecraft:cobblestone",
				't', "minecraft:torch",
				'.', "minecraft:air");
		int size = 11, floor = 26, roof = floor + 4;
		String[][] layers = new String[roof + 1][size];
		for (int y = 0; y <= roof; y++) {
			for (int z = 0; z < size; z++) {
				StringBuilder row = new StringBuilder();
				for (int x = 0; x < size; x++) row.append(mobFarmCell(x, y, z, size, floor, roof));
				layers[y][z] = row.toString();
			}
		}
		Schematic.Farm farm = new Schematic.Farm(Schematic.Farm.HOSTILE_MOBS, Schematic.Farm.Mode.MOBS, new BlockPos(5, 1, 5), new BlockPos(8, 1, 5));
		return design("mob_farm", legend, layers, farm, null);
	}

	private static char mobFarmCell(int x, int y, int z, int size, int floor, int roof) {
		boolean hole = (x == 5 || x == 6) && (z == 5 || z == 6);
		boolean shaft = x >= 4 && x <= 7 && z >= 4 && z <= 7;
		boolean edge = x == 0 || z == 0 || x == size - 1 || z == size - 1;
		if (y == 0) return shaft || x == 8 && (z == 5 || z == 6) ? 'c' : ' ';
		if (y <= 2 && x == 8 && (z == 5 || z == 6)) return '.'; // where the bot waits
		if (y < floor) {
			if (!shaft) return ' ';
			if (hole) return y == 1 && x == 5 && z == 5 ? 't' : '.';
			if (x == 7 && (z == 5 || z == 6) && y <= 2) return '.'; // doorway onto the landing
			return 'c';
		}
		if (y == floor) return hole ? '.' : 'c';
		if (y < roof) return edge ? 'c' : '.';
		return 'c';
	}

	private static Schematic design(String name, Map<Character, String> legend, String[][] layers, Schematic.@Nullable Farm farm,
			@Nullable BlockPos baseSpot) {
		List<Schematic.Cell> cells = new ArrayList<>();
		for (int y = 0; y < layers.length; y++) {
			for (int z = 0; z < layers[y].length; z++) {
				String row = layers[y][z];
				for (int x = 0; x < row.length(); x++) {
					char c = row.charAt(x);
					if (c == ' ') continue;
					BlockState state = BlockStates.parse(legend.get(c));
					if (state != null) cells.add(new Schematic.Cell(new BlockPos(x, y, z), state));
				}
			}
		}
		return new Schematic(name, SchematicLoader.size(cells), cells, farm, baseSpot, true);
	}
}
