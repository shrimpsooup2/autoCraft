package com.autocraft.control;

import com.autocraft.Bot;
import com.autocraft.path.Navigator;
import com.autocraft.world.BlockUtil;
import java.util.function.Predicate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/** Puts a utility block (crafting table, furnace, shulker box) down right next to the player. */
public final class Placement {
	private Placement() {}

	/**
	 * Place a matching item into a free cell around the player: beside the feet, beside the head, then the
	 * corners. {@code needsOpenTop}: the block opens upward (shulker boxes) so the cell above must be clear.
	 * Returns the position used, or null if no cell or no item.
	 */
	public static @Nullable BlockPos besideFeet(Bot bot, Predicate<ItemStack> item, boolean needsOpenTop) {
		ClientLevel level = bot.mc().level;
		BlockPos feet = Navigator.feet(bot.player());
		AABB body = bot.player().getBoundingBox();
		for (int dy = 0; dy <= 1; dy++) {
			for (Direction d : Direction.Plane.HORIZONTAL) {
				BlockPos spot = feet.relative(d).above(dy);
				if (usable(level, spot, body, needsOpenTop) && bot.controls().place(spot, item)) return spot;
			}
		}
		for (int dx = -1; dx <= 1; dx += 2) {
			for (int dz = -1; dz <= 1; dz += 2) {
				BlockPos spot = feet.offset(dx, 0, dz);
				if (usable(level, spot, body, needsOpenTop) && bot.controls().place(spot, item)) return spot;
			}
		}
		return null;
	}

	private static boolean usable(ClientLevel level, BlockPos spot, AABB body, boolean needsOpenTop) {
		BlockState at = level.getBlockState(spot);
		if (!at.canBeReplaced() || !at.getFluidState().isEmpty()) return false;
		if (new AABB(spot).intersects(body)) return false; // the server refuses blocks inside the player
		if (needsOpenTop && !level.getBlockState(spot.above()).getCollisionShape(level, spot.above()).isEmpty()) return false;
		for (Direction d : Direction.values()) { // needs some face to click
			BlockState n = level.getBlockState(spot.relative(d));
			if (!n.isAir() && !n.canBeReplaced()) return true;
		}
		return false;
	}

	/**
	 * Nowhere to put a block (standing on a pillar, wedged in a 1x1 shaft): a wall cell beside the feet that can
	 * be mined out to make room, or null.
	 */
	public static @Nullable BlockPos roomToDig(Bot bot) {
		ClientLevel level = bot.mc().level;
		BlockPos feet = Navigator.feet(bot.player());
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockPos spot = feet.relative(d);
			BlockState s = level.getBlockState(spot);
			if (s.isAir() || !BlockUtil.isBreakable(level, spot, s) || BlockUtil.isDangerous(level.getBlockState(spot.relative(d)))) continue;
			if (!level.getBlockState(spot.above()).getFluidState().isEmpty()) continue;
			return spot;
		}
		return null;
	}
}
