package com.autocraft.control;

import com.autocraft.Bot;
import com.autocraft.path.Navigator;
import java.util.function.Predicate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/** Puts a utility block (crafting table, furnace, shulker box) on the floor right next to the player. */
public final class Placement {
	private Placement() {}

	/**
	 * Place a matching item into a free spot beside the player's feet with air above it (so containers
	 * can open). Returns the position used, or null if no spot or item.
	 */
	public static @Nullable BlockPos besideFeet(Bot bot, Predicate<ItemStack> item) {
		ClientLevel level = bot.mc().level;
		BlockPos feet = Navigator.feet(bot.player());
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockPos spot = feet.relative(d);
			BlockState at = level.getBlockState(spot);
			if (!at.canBeReplaced() || !at.getFluidState().isEmpty()) continue;
			if (!level.getBlockState(spot.above()).isAir()) continue;
			if (level.getBlockState(spot.below()).getCollisionShape(level, spot.below()).isEmpty()) continue;
			if (bot.controls().place(spot, item)) return spot;
		}
		return null;
	}
}
