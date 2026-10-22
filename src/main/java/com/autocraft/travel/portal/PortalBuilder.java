package com.autocraft.travel.portal;

import com.autocraft.Bot;
import com.autocraft.control.InvUtil;
import com.autocraft.path.Goal;
import com.autocraft.path.Navigator;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Builds and lights a portal at a {@link PortalFrame}: walk to the build spot, dig the space, place the
 * frame in support order, light it with flint and steel, and check that portal blocks appeared.
 */
public final class PortalBuilder {
	public enum Status { RUNNING, DONE, FAILED }

	private enum Phase { WALK, CLEAR, FRAME, LIGHT }

	private static final int TIMEOUT = 20 * 120;
	private static final int PLACE_WAIT = 10;
	private static final int MAX_LIGHT_TRIES = 3;

	private final PortalFrame frame;
	private final Map<BlockPos, Integer> placedAt = new HashMap<>();
	private Phase phase = Phase.WALK;
	private int ticks;
	private int placeFailures;
	private int lightTries;
	private int lastLight = -100;

	public PortalBuilder(PortalFrame frame) {
		this.frame = frame;
	}

	public Status tick(Bot bot) {
		if (++ticks > TIMEOUT) return Status.FAILED;
		LocalPlayer p = bot.player();
		ClientLevel level = bot.mc().level;
		bot.setStatus("building portal: " + phase.name().toLowerCase());
		switch (phase) {
			case WALK -> {
				bot.nav().setGoal(new Goal.Block(frame.standSpot()));
				Navigator.Status s = bot.nav().tick();
				if (s == Navigator.Status.FAILED) return Status.FAILED;
				if (s == Navigator.Status.ARRIVED) {
					bot.nav().stop();
					phase = Phase.CLEAR;
				}
			}
			case CLEAR -> {
				for (BlockPos c : frame.cellsToClear()) {
					if (c.equals(Navigator.feet(p)) || c.equals(Navigator.feet(p).above())) continue;
					BlockState s = level.getBlockState(c);
					if (s.isAir()) continue; // the opening must be true air (grass/snow would block the portal)
					if (!s.getFluidState().isEmpty() || !p.isWithinBlockInteractionRange(c, 0.0)) return Status.FAILED;
					if (bot.controls().mine(c) && !level.getBlockState(c).canBeReplaced()) return Status.FAILED; // unbreakable
					return Status.RUNNING;
				}
				phase = Phase.FRAME;
			}
			case FRAME -> {
				for (PortalFrame.Placement pl : frame.placements()) {
					BlockState s = level.getBlockState(pl.pos());
					boolean done = pl.obsidian() ? s.is(Blocks.OBSIDIAN) : !s.canBeReplaced();
					if (done) continue;
					Integer at = placedAt.get(pl.pos());
					if (at != null && ticks - at < PLACE_WAIT) return Status.RUNNING; // wait for the server
					boolean ok = pl.obsidian()
							? bot.controls().place(pl.pos(), st -> st.is(Items.OBSIDIAN))
							: bot.controls().place(pl.pos(), InvUtil.scaffold(p, bot::isJobItem));
					if (!ok && ++placeFailures > 20) return Status.FAILED;
					placedAt.put(pl.pos(), ticks);
					return Status.RUNNING;
				}
				phase = Phase.LIGHT;
			}
			case LIGHT -> {
				if (level.getBlockState(frame.at(1, 1)).is(Blocks.NETHER_PORTAL)) return Status.DONE;
				if (ticks - lastLight < 20) return Status.RUNNING;
				if (lightTries++ >= MAX_LIGHT_TRIES || !InvUtil.select(p, s -> s.is(Items.FLINT_AND_STEEL))) return Status.FAILED;
				BlockPos base = frame.ignitionBase();
				Vec3 hit = Vec3.atCenterOf(base).add(0, 0.5, 0);
				bot.controls().lookAt(hit);
				bot.mc().gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, base, false));
				lastLight = ticks;
			}
		}
		return Status.RUNNING;
	}
}
