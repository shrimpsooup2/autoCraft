package com.autocraft.build;

import com.autocraft.schematic.Schematic;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

/**
 * Something to build, where, how, and why.
 *
 * @param origin       world position of the schematic's 0,0,0 corner (mirror and rotation pivot around it)
 * @param rotation     clockwise quarter turns around the origin
 * @param mirror       applied before the rotation
 * @param substitution material changes (wood swaps, stand-ins, replacements, filler)
 */
public record BuildOrder(Schematic schematic, BlockPos origin, Rotation rotation, Mirror mirror, Substitution substitution, Purpose purpose) {
	public enum Purpose {
		/** {@code !ac build} / a confirmed placement: the user picked it and where it goes. */
		USER,
		/** A farm the bot decided to build for a job resource (or {@code !ac farm}). */
		FARM
	}
}
