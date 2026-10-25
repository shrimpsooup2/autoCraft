package com.autocraft.build;

import com.autocraft.Bot;
import com.autocraft.control.InvUtil;
import com.autocraft.path.Goal;
import com.autocraft.path.Navigator;
import com.autocraft.world.BlockUtil;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Fills an empty bucket with water or lava from the nearest source block outside the build area. */
public final class FluidFill {
	public enum Status { RUNNING, DONE, FAILED }

	private static final int TIMEOUT = 20 * 120;
	private static final double REACH = 4.0;

	private final boolean lava;
	private final BoundingBox avoid;
	private @Nullable BlockPos source;
	private int ticks;
	private int scooped;

	public FluidFill(boolean lava, BoundingBox avoid) {
		this.lava = lava;
		this.avoid = avoid;
	}

	public Item filled() {
		return lava ? Items.LAVA_BUCKET : Items.WATER_BUCKET;
	}

	public Status tick(Bot bot) {
		LocalPlayer p = bot.player();
		if (InvUtil.count(p, s -> s.is(filled())) > 0) return Status.DONE;
		if (InvUtil.count(p, s -> s.is(Items.BUCKET)) == 0 || ++ticks > TIMEOUT) return Status.FAILED;
		if (source == null || !isSource(bot.level().getBlockState(source))) {
			List<BlockPos> found = bot.scanner().findBlocks(this::isSource, 4, 32);
			found.removeIf(avoid::isInside);
			if (found.isEmpty()) return Status.FAILED;
			source = found.getFirst();
		}
		bot.setStatus("filling a bucket with " + (lava ? "lava" : "water"));
		if (Navigator.settled(p) && p.getEyePosition().distanceTo(Vec3.atCenterOf(source)) <= REACH) {
			bot.nav().stop();
			if (!InvUtil.select(p, s -> s.is(Items.BUCKET))) return Status.RUNNING;
			bot.controls().lookAt(Vec3.atCenterOf(source).add(0, 0.3, 0));
			bot.controls().useItem(InteractionHand.MAIN_HAND);
			if (++scooped > 5) source = null; // something in the way: try another source
			return Status.RUNNING;
		}
		bot.nav().setGoal(new Goal.Reach(source, REACH - 0.5));
		if (bot.nav().tick() == Navigator.Status.FAILED) {
			bot.nav().stop();
			source = null;
		}
		return Status.RUNNING;
	}

	private boolean isSource(BlockState s) {
		return s.getFluidState().isSource() && (lava ? BlockUtil.isLava(s) : BlockUtil.isWater(s));
	}
}
