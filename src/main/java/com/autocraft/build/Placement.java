package com.autocraft.build;

import com.autocraft.schematic.Schematic;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

/**
 * A schematic being positioned before it is built, Litematica-style: an origin (the schematic's 0,0,0 corner),
 * a rotation, a mirror and the material choices, all adjustable while a ghost of it is shown in the world.
 */
public final class Placement {
	private final Schematic schematic;
	private BlockPos origin;
	private Rotation rotation = Rotation.NONE;
	private Mirror mirror = Mirror.NONE;
	private MaterialSettings materials;
	private Substitution substitution;
	/** The plan at origin 0,0,0 for the current rotation/mirror/materials; replaced (new identity) when one changes. */
	private @Nullable BuildPlan local;

	public Placement(Schematic schematic, BlockPos origin, MaterialSettings materials) {
		this.schematic = schematic;
		this.origin = origin.immutable();
		this.materials = materials;
		this.substitution = MaterialAdvisor.resolve(materials, schematic);
	}

	public Schematic schematic() { return schematic; }
	public BlockPos origin() { return origin; }
	public Rotation rotation() { return rotation; }
	public Mirror mirror() { return mirror; }
	public MaterialSettings materials() { return materials; }
	public Substitution substitution() { return substitution; }

	/** Apply edited material choices (re-resolved against what grows here; the ghost follows). */
	public void setMaterials(MaterialSettings settings) {
		materials = settings;
		substitution = MaterialAdvisor.resolve(settings, schematic);
		local = null;
	}

	public void move(int dx, int dy, int dz) {
		origin = origin.offset(dx, dy, dz);
	}

	public void setOrigin(BlockPos pos) {
		origin = pos.immutable();
	}

	/** Put the footprint's center on {@code pos} with the bottom layer at its Y. */
	public void centerOn(BlockPos pos) {
		BoundingBox b = localPlan().box();
		origin = new BlockPos(pos.getX() - (b.minX() + b.maxX()) / 2, pos.getY() - b.minY(), pos.getZ() - (b.minZ() + b.maxZ()) / 2);
	}

	public void rotate(boolean clockwise) {
		rotation = rotation.getRotated(clockwise ? Rotation.CLOCKWISE_90 : Rotation.COUNTERCLOCKWISE_90);
		local = null;
	}

	/** None → left/right → front/back → none. */
	public void cycleMirror() {
		mirror = Mirror.values()[(mirror.ordinal() + 1) % Mirror.values().length];
		local = null;
	}

	/** Geometry relative to the origin; the same object until rotation, mirror or materials change (the ghost's cache key). */
	public BuildPlan localPlan() {
		if (local == null) local = new BuildPlan(schematic, BlockPos.ZERO, rotation, mirror, substitution);
		return local;
	}

	public BoundingBox box() {
		return localPlan().box().moved(origin.getX(), origin.getY(), origin.getZ());
	}

	public BuildOrder toOrder() {
		return new BuildOrder(schematic, origin, rotation, mirror, substitution,
				schematic.farm() != null ? BuildOrder.Purpose.FARM : BuildOrder.Purpose.USER);
	}

	public static String describe(Rotation r) {
		return switch (r) {
			case NONE -> "0°";
			case CLOCKWISE_90 -> "90°";
			case CLOCKWISE_180 -> "180°";
			case COUNTERCLOCKWISE_90 -> "270°";
		};
	}

	public static String describe(Mirror m) {
		return switch (m) {
			case NONE -> "none";
			case LEFT_RIGHT -> "left-right";
			case FRONT_BACK -> "front-back";
		};
	}
}
