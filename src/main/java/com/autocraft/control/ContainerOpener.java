package com.autocraft.control;

import com.autocraft.Bot;
import com.autocraft.world.ChestMemory;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;

/** Right-clicks a block until the expected container menu is open (the server opens it a few ticks later). */
public final class ContainerOpener {
	public enum Status { WAITING, OPEN, FAILED }

	private static final int RETRY_EVERY = 10;
	private static final int TIMEOUT = 60;

	private final BlockPos pos;
	private final Class<? extends AbstractContainerMenu> menu;
	private int ticks;

	public ContainerOpener(BlockPos pos, Class<? extends AbstractContainerMenu> menu) {
		this.pos = pos.immutable();
		this.menu = menu;
	}

	public Status tick(Bot bot) {
		LocalPlayer p = bot.player();
		if (menu.isInstance(p.containerMenu)) return Status.OPEN;
		if (!InvUtil.inventoryMenuActive(p)) p.closeContainer(); // some other screen is in the way
		if (ticks % RETRY_EVERY == 0) {
			ChestMemory.opening(pos);
			bot.controls().interact(pos);
		}
		return ++ticks > TIMEOUT ? Status.FAILED : Status.WAITING;
	}
}
