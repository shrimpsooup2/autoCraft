package com.autocraft.schematic;

import java.util.List;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import org.jspecify.annotations.Nullable;

/**
 * User farms are recognised by file name: {@code farm-<resource>[-anything].litematic}, e.g.
 * {@code farm-sugar_cane-zero_tick.litematic}. How to harvest is guessed from the contents: anything with
 * chests/barrels/hoppers is an automatic farm (empty its containers), otherwise cane/tree/crop rules.
 */
final class FarmNames {
	private static final String PREFIX = "farm-";

	private FarmNames() {}

	static Schematic.@Nullable Farm farmFor(String fileName, List<Schematic.Cell> cells) {
		if (!fileName.toLowerCase().startsWith(PREFIX)) return null;
		String rest = fileName.substring(PREFIX.length()).toLowerCase();
		int dash = rest.indexOf('-');
		String resource = dash < 0 ? rest : rest.substring(0, dash);
		return resource.isEmpty() ? null : new Schematic.Farm(resource, detect(cells), null, null);
	}

	private static Schematic.Farm.Mode detect(List<Schematic.Cell> cells) {
		boolean cane = false, tree = false, crop = false;
		for (Schematic.Cell c : cells) {
			Block b = c.state().getBlock();
			if (b instanceof ChestBlock || b instanceof BarrelBlock || b instanceof HopperBlock) return Schematic.Farm.Mode.CONTAINERS;
			cane |= b instanceof SugarCaneBlock || b instanceof BambooStalkBlock || b instanceof CactusBlock;
			tree |= b instanceof SaplingBlock;
			crop |= b instanceof CropBlock;
		}
		if (cane) return Schematic.Farm.Mode.CANE;
		if (tree) return Schematic.Farm.Mode.TREE;
		if (crop) return Schematic.Farm.Mode.CROP;
		return Schematic.Farm.Mode.CONTAINERS;
	}
}
