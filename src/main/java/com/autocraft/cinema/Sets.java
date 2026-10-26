package com.autocraft.cinema;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Pieces several shots build: the logo, the team's cottage, floating islands. */
final class Sets {
	private Sets() {}

	/** The mod icon, 32×32 (row 0 is the top): a gear dissolving into circuit traces. */
	static final String[] LOGO = {
		"................................", "................................", "................................", "................................",
		"...........#####................", "...........#####................", "......##...#####...........###..", ".....####..#####......######.#..",
		"....######.#####.....#.....###..", "...##############.###...........", "...#################......#.....", "....################...##.......",
		".....##############....##.......", "......#############.............", ".###########...######...........", ".##########.....#######.....###.",
		".##########.....#############.#.", ".##########.....#######.....###.", ".###########...######...........", "......#############.............",
		".....##############....##.......", "....################...##.......", "...#################......#.....", "...##############.###...........",
		"....######.#####.....#.....###..", ".....####..#####......######.#..", "......##...#####...........###..", "...........#####................",
		"...........#####................", "................................", "................................", "................................",
	};

	/** Logo pixels: (col 0..31 left to right as the viewer reads it, up 0..31) → true for the gear part. */
	static Map<BlockPos, Boolean> logoPixels() {
		Map<BlockPos, Boolean> px = new LinkedHashMap<>();
		for (int col = 0; col < 32; col++) {
			for (int row = 0; row < 32; row++) {
				if (LOGO[row].charAt(col) == '#') px.put(new BlockPos(col, 31 - row, 0), col < 17);
			}
		}
		return px;
	}

	/** World position of logo pixel (col, up) for a logo centred at `centre`, read by a viewer standing `toward` of it. */
	static BlockPos logoBlock(BlockPos centre, Direction toward, int col, int up) {
		Direction right = toward.getCounterClockWise();   // the viewer faces the logo (opposite `toward`); this is their right
		return centre.relative(right, col - 16).above(up - 16);
	}

	/**
	 * The team's cottage, 7×7 with its door on the north side: cobblestone floor, log-cornered plank walls with
	 * glass windows, a spruce-stair gable roof running east-west. Offsets from its north-west floor corner.
	 */
	static Map<BlockPos, BlockState> cottage() {
		Map<BlockPos, BlockState> s = new LinkedHashMap<>();
		BlockState cobble = Blocks.COBBLESTONE.defaultBlockState(), planks = Blocks.OAK_PLANKS.defaultBlockState();
		BlockState log = Blocks.OAK_LOG.defaultBlockState(), glass = Blocks.GLASS.defaultBlockState();
		for (int x = 0; x < 7; x++) {
			for (int z = 0; z < 7; z++) s.put(new BlockPos(x, 0, z), cobble);
		}
		for (int y = 1; y <= 3; y++) {
			for (int x = 0; x < 7; x++) {
				for (int z = 0; z < 7; z++) {
					boolean edgeX = x == 0 || x == 6, edgeZ = z == 0 || z == 6;
					if (!edgeX && !edgeZ) continue;
					if (z == 0 && x == 3 && y <= 2) continue;                       // the doorway
					boolean corner = edgeX && edgeZ;
					BlockState b = corner ? log : planks;
					boolean window = y == 2 && !corner && ((edgeZ && (x == 1 || x == 5)) || (edgeX && z == 3) || (z == 6 && x == 3));
					s.put(new BlockPos(x, y, z), window ? glass : b);
				}
			}
		}
		BlockState north = Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH);
		BlockState south = Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH);
		BlockState ridge = Blocks.SPRUCE_PLANKS.defaultBlockState();
		for (int k = 0; k < 4; k++) {
			int y = 4 + k;
			for (int x = -1; x <= 7; x++) {
				if (k == 3) {
					s.put(new BlockPos(x, y, 3), ridge);
				} else {
					s.put(new BlockPos(x, y, k), north);
					s.put(new BlockPos(x, y, 6 - k), south);
				}
			}
			for (int z = k + 1; z < 6 - k; z++) {                               // gable ends
				if (k < 3) {
					s.put(new BlockPos(0, y, z), planks);
					s.put(new BlockPos(6, y, z), planks);
				}
			}
		}
		return s;
	}

	/** A floating island: a grassy disc of radius r at (cx, cy, cz) with a rocky underside tapering down. */
	static void island(ServerLevel level, int cx, int cy, int cz, int r, BlockState top) {
		BlockState dirt = top.is(Blocks.GRASS_BLOCK) ? Blocks.DIRT.defaultBlockState() : top;
		for (int x = -r; x <= r; x++) {
			for (int z = -r; z <= r; z++) {
				double d = Math.sqrt(x * x + z * z);
				if (d > r + 0.4) continue;
				int depth = 1 + (int) ((r + 0.5 - d) * 1.3);
				for (int k = 0; k <= depth; k++) {
					BlockState b = k == 0 ? top : k < 2 ? dirt : ((x * 31 + z * 17 + k) % 7 == 0 ? Blocks.ANDESITE.defaultBlockState() : Blocks.STONE.defaultBlockState());
					Stage.set(level, cx + x, cy - k, cz + z, b);
				}
			}
		}
	}

	/** An oak tree (trunk at x, z standing on y). */
	static void oak(ServerLevel level, int x, int y, int z, int height) {
		for (int k = 0; k < height; k++) Stage.set(level, x, y + k, z, Blocks.OAK_LOG.defaultBlockState());
		int top = y + height;
		BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true);
		for (int dy = -2; dy <= 1; dy++) {
			int r = dy < 0 ? 2 : 1;
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.abs(dx) == r && Math.abs(dz) == r && (dy == -1 || ((dx + dz + dy) & 1) == 0)) continue;
					if (dx == 0 && dz == 0 && dy < 0) continue;
					Stage.set(level, x + dx, top + dy, z + dz, leaves);
				}
			}
		}
		Stage.set(level, x, top + 1, z, leaves);
	}
}
