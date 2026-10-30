package com.autocraft.path;

import com.autocraft.world.BlockUtil;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Ticks of digging on a planned path: with the tools carried, and with a wooden or a stone pickaxe instead. The bot
 * weighs the time making one takes against the time it saves (walking back to its things after a death, it dug
 * through stone by hand). The path was planned with the tools carried, so it already goes around what they dig
 * slowly; with a pickaxe the route can only get cheaper, and the saving here is a floor.
 *
 * @param share how much of the trip the path covers: a partial path stands for the whole trip in proportion
 */
public record DigTime(Goal goal, int now, int withWood, int withStone, double share) {
	/** A partial path stands for at most this many times itself. */
	private static final double MIN_SHARE = 0.05;

	static DigTime of(ClientLevel level, LocalPlayer p, Goal goal, List<Step> steps, BlockPos start, boolean complete) {
		ItemStack wood = Items.WOODEN_PICKAXE.getDefaultInstance(), stone = Items.STONE_PICKAXE.getDefaultInstance();
		int now = 0, withWood = 0, withStone = 0;
		Set<BlockPos> seen = new HashSet<>();
		for (Step step : steps) {
			for (BlockPos b : step.breaks()) {
				if (!seen.add(b)) continue;
				BlockState s = level.getBlockState(b);
				if (BlockUtil.isPassable(level, b, s)) continue;
				int t = BlockUtil.breakTicks(p, level, b, s);
				if (t == Integer.MAX_VALUE) continue;
				now += t;
				withWood += Math.min(t, ticksWith(wood, level, b, s));
				withStone += Math.min(t, ticksWith(stone, level, b, s));
			}
		}
		double share = 1;
		if (!complete) {
			double from = goal.heuristic(start.getX(), start.getY(), start.getZ());
			BlockPos end = steps.isEmpty() ? start : steps.getLast().dest();
			double to = goal.heuristic(end.getX(), end.getY(), end.getZ());
			share = from <= 0 ? 1 : Math.clamp((from - to) / from, MIN_SHARE, 1);
		}
		return new DigTime(goal, now, withWood, withStone, share);
	}

	/** Ticks a pickaxe of this kind ({@link Items#WOODEN_PICKAXE} or {@link Items#STONE_PICKAXE}) saves over the trip. */
	public int savedBy(Item pickaxe) {
		int with = pickaxe == Items.STONE_PICKAXE ? withStone : withWood;
		return (int) Math.round((now - with) / share);
	}

	/** Vanilla break time with {@code tool} in hand (no enchantments or effects), like {@link BlockUtil#breakTicks}. */
	static int ticksWith(ItemStack tool, BlockGetter level, BlockPos pos, BlockState s) {
		float hardness = s.getDestroySpeed(level, pos);
		if (hardness < 0) return Integer.MAX_VALUE;
		if (hardness == 0) return 1;
		boolean harvest = !s.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(s);
		float speed = harvest ? Math.max(1.0F, tool.getDestroySpeed(s)) : 1.0F;
		return (int) Math.ceil(1.0F / (speed / hardness / (harvest ? 30.0F : 100.0F)));
	}
}
