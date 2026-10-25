package com.autocraft.build;

import com.autocraft.schematic.Schematic;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jspecify.annotations.Nullable;

/**
 * A schematic placed in the world: mirrored, rotated, wood-swapped (built-ins only), split into cells to clear
 * and blocks to place in a buildable order — full blocks bottom-up, then attached/partial blocks, then water,
 * then lava, then plants (sugar cane needs its water first).
 */
public final class BuildPlan {
	/** @param filler a hidden cell: any solid block will do (the state is just what the bot places if empty) */
	public record Task(BlockPos pos, BlockState state, boolean filler) {
		/** The world already satisfies this task. */
		public boolean satisfiedBy(BlockState world) {
			return filler ? world.isSolidRender() : matches(world, state);
		}
	}

	/** Properties fixed at placement time (and so worth checking); everything else is computed by the game. */
	private static final Set<String> PLACEMENT_PROPS = Set.of(
			"facing", "axis", "half", "type", "rotation", "face", "hinge", "hanging", "attachment", "orientation", "vertical_direction");

	private final Schematic schematic;
	private final BlockPos origin;
	private final Rotation rotation;
	private final Mirror mirror;
	private final List<Task> clears = new ArrayList<>();
	private final List<Task> places = new ArrayList<>();
	private final BoundingBox box;

	/**
	 * Mirror first, then rotate clockwise around {@code origin} (the schematic's 0,0,0 corner) — Litematica's order.
	 * Materials are adapted by {@code sub} first (wood swaps, stand-ins, replacements), then hidden cells get its filler.
	 */
	public BuildPlan(Schematic schematic, BlockPos origin, Rotation rotation, Mirror mirror, Substitution sub) {
		this.schematic = schematic;
		this.origin = origin.immutable();
		this.rotation = rotation;
		this.mirror = mirror;
		List<BlockPos> all = new ArrayList<>();
		Map<BlockPos, BlockState> solid = new HashMap<>();
		for (Schematic.Cell cell : schematic.cells()) {
			BlockPos pos = toWorld(cell.pos());
			BlockState state = sub.apply(cell.state()).mirror(mirror).rotate(rotation);
			all.add(pos);
			if (state.isAir() || isCompanionHalf(state)) {
				clears.add(new Task(pos, state, false));
				continue;
			}
			places.add(new Task(pos, state, false));
			if (state.isSolidRender()) solid.put(pos, state);
		}
		if (sub.interiorFiller() != null) {
			BlockState filler = sub.interiorFiller().defaultBlockState();
			places.replaceAll(t -> solid.containsKey(t.pos) && hidden(t.pos, solid) ? new Task(t.pos, filler, true) : t);
		}
		clears.sort(Comparator.comparingInt((Task t) -> -t.pos.getY())); // top down so nothing falls on us
		places.sort(Comparator.comparingInt((Task t) -> pass(t.state)).thenComparingInt(t -> t.pos.getY())
				.thenComparingInt(t -> t.pos.getX()).thenComparingInt(t -> t.pos.getZ()));
		box = BoundingBox.encapsulatingPositions(all).orElse(new BoundingBox(origin));
	}

	/** Surrounded by solid blocks of the design on all six sides: nobody will ever see it. */
	private static boolean hidden(BlockPos pos, Map<BlockPos, BlockState> solid) {
		for (Direction d : Direction.values()) {
			if (!solid.containsKey(pos.relative(d))) return false;
		}
		return true;
	}

	public BlockPos toWorld(BlockPos relative) {
		return origin.offset(StructureTemplate.transform(relative, mirror, rotation, BlockPos.ZERO));
	}

	public Schematic schematic() { return schematic; }
	public BlockPos origin() { return origin; }
	public Rotation rotation() { return rotation; }
	public Mirror mirror() { return mirror; }
	public List<Task> clears() { return clears; }
	public List<Task> places() { return places; }
	public BoundingBox box() { return box; }

	// ---- ordering & matching --------------------------------------------------------------------

	private static int pass(BlockState s) {
		if (s.getFluidState().is(FluidTags.WATER) && s.getBlock() == Blocks.WATER) return 3;
		if (s.getBlock() == Blocks.LAVA) return 4;
		Block b = s.getBlock();
		if (b instanceof VegetationBlock || b instanceof SugarCaneBlock || b instanceof CactusBlock || b instanceof BambooStalkBlock) return 5;
		return s.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) ? 1 : 2;
	}

	/** Upper door/plant halves, bed heads, piston heads: they appear with their main part. */
	public static boolean isCompanionHalf(BlockState s) {
		if (s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && s.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
			return true;
		}
		if (s.hasProperty(BlockStateProperties.BED_PART) && s.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD) return true;
		return s.getBlock() instanceof PistonHeadBlock || s.getBlock() instanceof MovingPistonBlock;
	}

	public static boolean isFluid(BlockState s) {
		return s.getBlock() == Blocks.WATER || s.getBlock() == Blocks.LAVA;
	}

	/** The world already has what the plan wants (same block, same placement-time properties). */
	public static boolean matches(BlockState world, BlockState target) {
		if (target.getBlock() == Blocks.WATER) return world.getFluidState().is(FluidTags.WATER); // flowing water is fine too
		if (target.getBlock() == Blocks.LAVA) return world.getFluidState().is(FluidTags.LAVA) && world.getFluidState().isSource();
		return world.getBlock() == target.getBlock() && sameProps(world, target);
	}

	/** Placement-time properties agree (used for predicted states as well). */
	public static boolean sameProps(BlockState a, BlockState b) {
		for (Property<?> prop : b.getProperties()) {
			String name = prop.getName();
			if (!PLACEMENT_PROPS.contains(name) || !a.hasProperty(prop)) continue;
			if (name.equals("type") && b.getBlock() instanceof ChestBlock) continue; // left/right comes from the neighbour
			if (!a.getValue(prop).equals(b.getValue(prop))) return false;
		}
		return true;
	}

	// ---- items ----------------------------------------------------------------------------------

	/** Item that places this block (buckets for fluids, dirt for farmland/paths), or null if unobtainable. */
	public static @Nullable Item itemFor(BlockState s) {
		Block b = s.getBlock();
		if (b == Blocks.WATER) return Items.WATER_BUCKET;
		if (b == Blocks.LAVA) return Items.LAVA_BUCKET;
		if (b == Blocks.FARMLAND || b == Blocks.DIRT_PATH) return Items.DIRT;
		Item item = b.asItem();
		return item == Items.AIR ? null : item;
	}

	private static int itemsFor(BlockState s) {
		if (s.hasProperty(BlockStateProperties.SLAB_TYPE) && s.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE) return 2;
		return 1;
	}

	/** Items still needed for unfinished placements (in order), up to {@code maxStacks} different stacks. */
	public Map<Item, Integer> bill(BlockGetter level, int maxStacks) {
		Map<Item, Integer> bill = new LinkedHashMap<>();
		int stacks = 0;
		for (Task t : places) {
			if (t.satisfiedBy(level.getBlockState(t.pos))) continue;
			Item item = itemFor(t.state);
			if (item == null) continue;
			int before = bill.getOrDefault(item, 0);
			int after = before + itemsFor(t.state);
			int max = item.getDefaultMaxStackSize();
			if ((after + max - 1) / max > (before + max - 1) / max && ++stacks > maxStacks) break;
			bill.put(item, after);
		}
		return bill;
	}
}
