package com.autocraft.travel.portal;

import com.autocraft.Bot;
import com.autocraft.control.InvUtil;
import com.autocraft.path.Goal;
import com.autocraft.path.Navigator;
import com.autocraft.world.BlockUtil;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Makes obsidian from a lava pool: pour the water bucket into a surface lava source (the neighbouring
 * lava sources turn to obsidian), wait, scoop the water back up, repeat on another source. The collector
 * mines the result (diamond pickaxe). Overworld only — water evaporates in the Nether.
 */
public final class ObsidianCaster {
	public enum Status { RUNNING, DONE, FAILED }

	private enum Phase { FIND, APPROACH, POUR, SETTLE, SCOOP }

	private static final int ENOUGH = 10;
	private static final int MAX_ROUNDS = 8;
	private static final int SETTLE_TICKS = 15;

	private final Set<BlockPos> used = new HashSet<>();
	private Phase phase = Phase.FIND;
	private @Nullable BlockPos lava;
	private int rounds;
	private int phaseTicks;

	public Status tick(Bot bot) {
		LocalPlayer p = bot.player();
		ClientLevel level = bot.mc().level;
		phaseTicks++;
		bot.setStatus("casting obsidian: " + phase.name().toLowerCase());
		switch (phase) {
			case FIND -> {
				if (obsidianAvailable(bot) >= ENOUGH) return Status.DONE;
				if (rounds >= MAX_ROUNDS) return Status.FAILED;
				lava = nearestSurfaceLava(bot);
				if (lava == null) return Status.FAILED;
				enter(Phase.APPROACH);
			}
			case APPROACH -> {
				if (inPourRange(p)) {
					bot.nav().stop();
					return enter(Phase.POUR);
				}
				bot.nav().setGoal(new Goal.Near(lava.above(), 3));
				Navigator.Status s = bot.nav().tick();
				if (s == Navigator.Status.FAILED || s == Navigator.Status.ARRIVED && !inPourRange(p)) {
					used.add(lava);
					bot.nav().stop();
					return enter(Phase.FIND);
				}
			}
			case POUR -> {
				if (!InvUtil.select(p, s -> s.is(Items.WATER_BUCKET))) return Status.FAILED;
				bot.controls().lookAt(Vec3.atCenterOf(lava).add(0, 0.4, 0));
				bot.controls().useItem(InteractionHand.MAIN_HAND);
				rounds++;
				used.add(lava);
				return enter(Phase.SETTLE);
			}
			case SETTLE -> {
				if (phaseTicks >= SETTLE_TICKS) return enter(Phase.SCOOP);
			}
			case SCOOP -> {
				if (BlockUtil.isWater(level.getBlockState(lava)) && level.getFluidState(lava).isSource()
						&& InvUtil.select(p, s -> s.is(Items.BUCKET))) {
					bot.controls().lookAt(Vec3.atCenterOf(lava).add(0, 0.4, 0));
					bot.controls().useItem(InteractionHand.MAIN_HAND);
				}
				return enter(Phase.FIND);
			}
		}
		return Status.RUNNING;
	}

	private Status enter(Phase next) {
		phase = next;
		phaseTicks = 0;
		return Status.RUNNING;
	}

	private boolean inPourRange(LocalPlayer p) {
		return Navigator.settled(p) && p.isWithinBlockInteractionRange(lava, -0.5) && !p.isInLava();
	}

	/** Lava source with open air above it, not yet tried. */
	private @Nullable BlockPos nearestSurfaceLava(Bot bot) {
		ClientLevel level = bot.mc().level;
		List<BlockPos> found = bot.scanner().findBlocks(s -> BlockUtil.isLava(s) && s.getFluidState().isSource(), 3, 64);
		for (BlockPos pos : found) {
			if (!used.contains(pos) && level.getBlockState(pos.above()).isAir()) return pos;
		}
		return null;
	}

	/** Obsidian carried plus obsidian blocks lying around nearby. */
	private static int obsidianAvailable(Bot bot) {
		int carried = InvUtil.count(bot.player(), s -> s.is(Items.OBSIDIAN));
		return carried + bot.scanner().findBlocks(s -> s.is(Blocks.OBSIDIAN), 2, ENOUGH).size();
	}
}
