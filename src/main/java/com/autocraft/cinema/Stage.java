package com.autocraft.cinema;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

/**
 * The set crew: works on the singleplayer world through its integrated server — commands (time, weather, game
 * rules), and building sets block by block. Set blocks go in without neighbour updates, so water stays put and
 * nothing pops off.
 */
final class Stage {
	/** No neighbour shape updates: blocks stay exactly as the set says (a door half, a torch placed before its wall). */
	static final int SET_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

	private Stage() {}

	static @Nullable MinecraftServer server() {
		return Minecraft.getInstance().getSingleplayerServer();
	}

	/** Run `work` on the server thread against the overworld and wait for it. */
	static void sync(Consumer<ServerLevel> work) {
		MinecraftServer server = server();
		if (server == null) throw new IllegalStateException("films need a singleplayer world");
		server.submit(() -> work.accept(server.overworld())).join();
	}

	/** A command as the server (no chat feedback). */
	static void command(String cmd) {
		MinecraftServer server = server();
		if (server == null) return;
		server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), cmd));
	}

	static void set(ServerLevel level, BlockPos pos, BlockState state) {
		level.setBlock(pos, state, SET_FLAGS);
	}

	static void set(ServerLevel level, int x, int y, int z, BlockState state) {
		level.setBlock(new BlockPos(x, y, z), state, SET_FLAGS);
	}

	static void fill(ServerLevel level, int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
		for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
			for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) {
				for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) set(level, x, y, z, state);
			}
		}
	}

	/** The surface: the y of the highest solid, non-leaf block at (x, z). */
	static int ground(ServerLevel level, int x, int z) {
		return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
	}

	/**
	 * A flat round lawn of `radius` around (cx, cz) at the area's median height, its edge sloping back into the
	 * land around it, with everything above (trees, tall grass) cleared. Returns the lawn's surface y.
	 */
	static int pad(ServerLevel level, int cx, int cz, int radius) {
		List<Integer> heights = new ArrayList<>();
		for (int x = -radius; x <= radius; x += 2) {
			for (int z = -radius; z <= radius; z += 2) {
				if (x * x + z * z <= radius * radius) heights.add(ground(level, cx + x, cz + z));
			}
		}
		heights.sort(null);
		int y0 = Math.max(level.getSeaLevel() + 1, heights.get(heights.size() / 2));
		int edge = 6;
		for (int x = -radius - edge; x <= radius + edge; x++) {
			for (int z = -radius - edge; z <= radius + edge; z++) {
				double d = Math.sqrt(x * x + z * z);
				if (d > radius + edge) continue;
				int wx = cx + x, wz = cz + z;
				int g = ground(level, wx, wz);
				double f = d <= radius ? 1 : 1 - (d - radius) / edge;
				int target = (int) Math.round(y0 * f + g * (1 - f));
				for (int y = target + 1; y <= Math.max(target, g) + 30; y++) {
					BlockPos p = new BlockPos(wx, y, wz);
					if (!level.getBlockState(p).isAir()) set(level, p, Blocks.AIR.defaultBlockState());
				}
				for (int y = Math.min(target, g) - 1; y < target; y++) set(level, wx, y, wz, Blocks.DIRT.defaultBlockState());
				set(level, wx, target, wz, Blocks.GRASS_BLOCK.defaultBlockState());
			}
		}
		return y0;
	}

	/** Clear a box to air. */
	static void clear(ServerLevel level, int x0, int y0, int z0, int x1, int y1, int z1) {
		fill(level, x0, y0, z0, x1, y1, z1, Blocks.AIR.defaultBlockState());
	}
}
