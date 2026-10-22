package com.autocraft.combat;

import com.autocraft.Bot;
import com.autocraft.control.InvUtil;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** Shield handling shared by the defense reflex and combat. */
public final class Guard {
	private Guard() {}

	public static boolean hasShield(LocalPlayer p) {
		return p.getOffhandItem().is(Items.SHIELD);
	}

	/** Raise the offhand shield toward {@code threat}. False if there is no shield. */
	public static boolean raiseShield(Bot bot, Vec3 threat) {
		LocalPlayer p = bot.player();
		if (!hasShield(p)) return false;
		if (!InvUtil.inventoryMenuActive(p)) p.closeContainer();
		safeMainHand(p);
		bot.controls().lookAt(threat);
		if (!p.isUsingItem()) bot.controls().useItem(InteractionHand.OFF_HAND);
		bot.controls().holdUse();
		return true;
	}

	/**
	 * Hold something with no right-click action (weapon, pickaxe, or nothing) so holding "use" can't
	 * place blocks, eat or strip logs by accident.
	 */
	public static void safeMainHand(LocalPlayer p) {
		if (isSafe(p.getMainHandItem())) return;
		Inventory inv = p.getInventory();
		for (int i = 0; i < Inventory.getSelectionSize(); i++) {
			if (isSafe(inv.getItem(i))) {
				inv.setSelectedSlot(i);
				return;
			}
		}
	}

	private static boolean isSafe(ItemStack s) {
		if (s.isEmpty()) return true;
		return Threats.attackDamage(s) > 2 && s.getItem() != Items.SHIELD; // swords, axes, pickaxes; axes only strip when clicking logs
	}
}
