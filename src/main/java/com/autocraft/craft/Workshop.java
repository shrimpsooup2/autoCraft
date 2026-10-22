package com.autocraft.craft;

import com.autocraft.Bot;
import com.autocraft.behavior.Harvest;
import com.autocraft.control.InvUtil;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Runs crafting and smelting steps from the {@link Planner} and cleans up afterwards: crafting tables and
 * furnaces it had to place are picked back up when {@link #packUp} is called. Shared by the gear planner
 * and the builder.
 */
public final class Workshop {
	public enum Status { IDLE, RUNNING, DONE, FAILED }

	private @Nullable Crafter crafter;
	private @Nullable Smelter smelter;
	private @Nullable Harvest pickup;
	private final List<BlockPos> placed = new ArrayList<>();

	public boolean busy() {
		return crafter != null || smelter != null || pickup != null;
	}

	/** Begin a Craft or Smelt step; other steps are ignored. */
	public void start(Planner.Step step) {
		switch (step) {
			case Planner.Craft c -> crafter = new Crafter(c.recipe());
			case Planner.Smelt s -> smelter = new Smelter(s.input(), s.count());
			default -> {
			}
		}
	}

	public Status tick(Bot bot) {
		if (crafter != null) {
			Crafter.Status s = crafter.tick(bot);
			bot.setStatus("crafting " + crafter.recipe().output().getDefaultInstance().getHoverName().getString());
			if (s == Crafter.Status.RUNNING) return Status.RUNNING;
			remember(crafter.placedTable());
			crafter = null;
			return s == Crafter.Status.DONE ? Status.DONE : Status.FAILED;
		}
		if (smelter != null) {
			Smelter.Status s = smelter.tick(bot);
			if (s == Smelter.Status.RUNNING) return Status.RUNNING;
			remember(smelter.placedFurnace());
			smelter = null;
			return s == Smelter.Status.DONE ? Status.DONE : Status.FAILED;
		}
		if (pickup != null) {
			bot.setStatus("picking up crafting station");
			if (pickup.tick(bot) == Harvest.Status.RUNNING) return Status.RUNNING;
			pickup = null;
			return Status.DONE;
		}
		return Status.IDLE;
	}

	/** Close any station screen and start picking our placed tables/furnaces back up. True if it started. */
	public boolean packUp(Bot bot) {
		LocalPlayer p = bot.player();
		if (!InvUtil.inventoryMenuActive(p)) p.closeContainer();
		while (!placed.isEmpty()) {
			BlockPos pos = placed.removeFirst();
			BlockState s = bot.mc().level.getBlockState(pos);
			if (s.is(Blocks.CRAFTING_TABLE) || s.is(Blocks.FURNACE)) {
				pickup = new Harvest(pos, st -> st.is(Items.CRAFTING_TABLE) || st.is(Items.FURNACE));
				return true;
			}
		}
		return false;
	}

	public void reset() {
		crafter = null;
		smelter = null;
		pickup = null;
		placed.clear();
	}

	private void remember(@Nullable BlockPos pos) {
		if (pos != null && !placed.contains(pos)) placed.add(pos);
	}
}
