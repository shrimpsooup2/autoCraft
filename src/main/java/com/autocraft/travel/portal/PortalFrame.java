package com.autocraft.travel.portal;

import com.autocraft.Bot;
import com.autocraft.path.Navigator;
import com.autocraft.world.BlockUtil;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Geometry of a minimal Nether portal: 4 wide × 5 tall frame (10 obsidian, corners optional) around a
 * 2 × 3 opening, standing on solid ground, built from a spot one block in front of it.
 * Coordinates are (u along the frame, v up) from {@code origin}, the bottom-left corner cell.
 *
 * @param origin bottom-left corner cell (resting on the ground)
 * @param along  horizontal direction of the frame's width
 */
public record PortalFrame(BlockPos origin, Direction along) {
	/** One block to put in place, in an order where each has a solid neighbour to click. */
	public record Placement(BlockPos pos, boolean obsidian) {}

	private static final int[][] ORDER = {
			{0, 0, 0}, {3, 0, 0}, // bottom corners (throwaway): give the side columns something to stand on
			{1, 0, 1}, {2, 0, 1},
			{0, 1, 1}, {0, 2, 1}, {0, 3, 1},
			{3, 1, 1}, {3, 2, 1}, {3, 3, 1},
			{0, 4, 0}, // top-left corner (throwaway): support for the top row
			{1, 4, 1}, {2, 4, 1}};
	private static final int SEARCH = 5;

	public BlockPos at(int u, int v) {
		return origin.relative(along, u).above(v);
	}

	/** Where the bot stands while building: in front of the left half of the opening. */
	public BlockPos standSpot() {
		return at(1, 0).relative(along.getCounterClockWise());
	}

	public List<Placement> placements() {
		List<Placement> out = new ArrayList<>();
		for (int[] o : ORDER) out.add(new Placement(at(o[0], o[1]), o[2] == 1));
		return out;
	}

	/** The block the flint and steel is used on (its top face lights the opening). */
	public BlockPos ignitionBase() {
		return at(1, 0);
	}

	/** Every cell that must be empty before placing: frame, corners used, opening, and our standing room. */
	public List<BlockPos> cellsToClear() {
		List<BlockPos> out = new ArrayList<>();
		for (int[] o : ORDER) out.add(at(o[0], o[1]));
		for (int u = 1; u <= 2; u++) {
			for (int v = 1; v <= 3; v++) out.add(at(u, v));
		}
		out.add(standSpot());
		out.add(standSpot().above());
		out.sort((a, b) -> Integer.compare(b.getY(), a.getY())); // top first so nothing falls on us
		return out;
	}

	/** Best buildable site near the player (fewest blocks to dig), or null. */
	public static @Nullable PortalFrame find(Bot bot) {
		ClientLevel level = bot.mc().level;
		BlockPos feet = Navigator.feet(bot.player());
		PortalFrame best = null;
		int bestCost = Integer.MAX_VALUE;
		for (Direction along : new Direction[] {Direction.EAST, Direction.SOUTH}) {
			for (BlockPos o : BlockPos.betweenClosed(feet.offset(-SEARCH, -1, -SEARCH), feet.offset(SEARCH, 1, SEARCH))) {
				PortalFrame f = new PortalFrame(o.immutable(), along);
				int cost = f.clearingCost(level, feet);
				if (cost >= 0 && cost < bestCost) {
					bestCost = cost;
					best = f;
				}
			}
		}
		return best;
	}

	/** Blocks to dig plus distance, or -1 if the site is unusable (fluids, unbreakables, no ground). */
	private int clearingCost(ClientLevel level, BlockPos feet) {
		for (int u = 0; u <= 3; u++) {
			BlockPos ground = at(u, -1);
			if (!BlockUtil.canStandOn(level, ground, level.getBlockState(ground))) return -1;
		}
		BlockPos standGround = standSpot().below();
		if (!BlockUtil.canStandOn(level, standGround, level.getBlockState(standGround))) return -1;
		int dig = 0;
		for (BlockPos c : cellsToClear()) {
			BlockState s = level.getBlockState(c);
			if (!s.getFluidState().isEmpty()) return -1;
			if (s.canBeReplaced()) continue;
			if (!BlockUtil.isBreakable(level, c, s)) return -1;
			dig++;
		}
		for (Placement pl : placements()) {
			if (pl.pos.equals(feet) || pl.pos.equals(feet.above())) return -1; // can't build inside ourselves
		}
		return dig * 4 + (int) Math.sqrt(standSpot().distSqr(feet));
	}
}
