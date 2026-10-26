package com.autocraft.cinema;

import com.autocraft.schematic.Schematic;
import com.autocraft.schematic.SchematicLibrary;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The build in the Team and End shots: the first schematic in autoCraft's schematic folder whose name starts with
 * "film" ({@code film.litematic}, {@code film-modern_house.schem} …), or the built-in cottage when there's none.
 * Blocks come in build order, layer by layer, snaking across each layer the way a builder walks it.
 */
final class Blueprint {
	/** What the build is called on screen: the file name after "film-", or "house" / "cottage". */
	final String name;
	final Vec3i size;
	/** Relative to the build's minimum corner, in build order. */
	final List<BlockPos> cells = new ArrayList<>();
	final List<BlockState> states = new ArrayList<>();

	private Blueprint(String name, Vec3i size) {
		this.name = name;
		this.size = size;
	}

	static Blueprint load() {
		Map<BlockPos, BlockState> raw = new HashMap<>();
		String name = "cottage";
		for (String n : SchematicLibrary.names()) {
			if (!n.toLowerCase(Locale.ROOT).startsWith("film")) continue;
			Schematic s = SchematicLibrary.get(n);
			if (s == null) continue;
			for (Schematic.Cell c : s.cells()) {
				if (!c.state().isAir()) raw.put(c.pos(), c.state());
			}
			if (raw.isEmpty()) continue;
			String rest = n.substring(4).replaceFirst("^[-_ ]+", "");
			name = rest.isEmpty() ? "house" : rest;
			break;
		}
		if (raw.isEmpty()) raw.putAll(Sets.cottage());
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE,
				maxZ = Integer.MIN_VALUE;
		for (BlockPos p : raw.keySet()) {
			minX = Math.min(minX, p.getX());
			minY = Math.min(minY, p.getY());
			minZ = Math.min(minZ, p.getZ());
			maxX = Math.max(maxX, p.getX());
			maxY = Math.max(maxY, p.getY());
			maxZ = Math.max(maxZ, p.getZ());
		}
		Blueprint b = new Blueprint(name, new Vec3i(maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1));
		BlockPos min = new BlockPos(minX, minY, minZ);
		List<Map.Entry<BlockPos, BlockState>> order = new ArrayList<>();
		for (Map.Entry<BlockPos, BlockState> e : raw.entrySet()) order.add(Map.entry(e.getKey().subtract(min), e.getValue()));
		order.sort(Comparator.<Map.Entry<BlockPos, BlockState>>comparingInt(e -> e.getKey().getY())
				.thenComparingInt(e -> e.getKey().getZ())
				.thenComparingInt(e -> (e.getKey().getZ() & 1) == 0 ? e.getKey().getX() : -e.getKey().getX()));
		for (Map.Entry<BlockPos, BlockState> e : order) {
			b.cells.add(e.getKey());
			b.states.add(e.getValue());
		}
		return b;
	}

	int count() {
		return cells.size();
	}

	/** Half the larger side: how far the build reaches from its centre. */
	int reach() {
		return Math.max(size.getX(), size.getZ()) / 2 + 1;
	}

	/** The minimum corner that centres the build on (x, z) with its bottom layer at y. */
	BlockPos corner(BlockPos centre) {
		return centre.offset(-size.getX() / 2, 0, -size.getZ() / 2);
	}

	/** The whole build, at once. */
	void build(ServerLevel level, BlockPos corner) {
		for (int i = 0; i < cells.size(); i++) Stage.set(level, corner.offset(cells.get(i)), states.get(i));
	}

	/** Client: ghost blocks in build order, with the sides that touch the build's own solid blocks left out. */
	List<Fx.GhostBlock> ghosts(BlockPos corner) {
		Map<BlockPos, BlockState> at = new HashMap<>();
		for (int i = 0; i < cells.size(); i++) at.put(cells.get(i), states.get(i));
		List<Fx.GhostBlock> out = new ArrayList<>(cells.size());
		for (int i = 0; i < cells.size(); i++) {
			BlockPos p = cells.get(i);
			int hidden = 0;
			for (Direction d : Direction.values()) {
				BlockState n = at.get(p.relative(d));
				if (n != null && n.isSolidRender()) hidden |= 1 << d.ordinal();
			}
			out.add(new Fx.GhostBlock(corner.offset(p), Fx.model(states.get(i)), hidden));
		}
		return out;
	}
}
