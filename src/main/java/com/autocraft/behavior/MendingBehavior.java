package com.autocraft.behavior;

import com.autocraft.Bot;
import com.autocraft.combat.Threats;
import com.autocraft.control.InvUtil;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;

/**
 * Mending upkeep. Worn armor and the held tool already soak up XP; when orbs are about to be collected,
 * the most damaged mending item sitting in the inventory is swapped into the offhand so it gets repaired
 * too, then the offhand item (usually the shield) is swapped back once the orbs are gone or a mob shows
 * up. Never claims the tick: at most one inventory click alongside whatever else is running.
 */
public final class MendingBehavior implements Behavior {
	private static final double ORB_RADIUS = 8;
	private static final int RESTORE_AFTER = 40;
	private static final double THREAT_RADIUS = 10;

	/** Inventory index the original offhand item was swapped into, or -1. */
	private int swappedInto = -1;
	private int quietTicks;

	@Override
	public String name() {
		return "mending";
	}

	@Override
	public void reset() {
		swappedInto = -1;
	}

	@Override
	public boolean tick(Bot bot) {
		LocalPlayer p = bot.player();
		if (!InvUtil.inventoryMenuActive(p) || p.isUsingItem()) return false;
		boolean orbs = !bot.scanner().entities(ORB_RADIUS, e -> e instanceof ExperienceOrb).isEmpty();
		if (swappedInto >= 0) {
			quietTicks = orbs ? 0 : quietTicks + 1;
			boolean danger = !Threats.collect(bot, THREAT_RADIUS).isEmpty();
			if (danger || quietTicks > RESTORE_AFTER || !needsRepair(p.getOffhandItem())) {
				InvUtil.moveToOffhand(p, swappedInto); // swap the original offhand item back
				swappedInto = -1;
			}
			return false;
		}
		if (!orbs || !Threats.collect(bot, THREAT_RADIUS).isEmpty() || needsRepair(p.getOffhandItem())) return false;
		int worst = mostDamagedMending(p);
		if (worst >= 0) {
			InvUtil.moveToOffhand(p, worst);
			swappedInto = worst;
			quietTicks = 0;
		}
		return false;
	}

	/** Inventory index of the most worn mending item that isn't already being repaired (held or worn). */
	private static int mostDamagedMending(LocalPlayer p) {
		Inventory inv = p.getInventory();
		int best = -1;
		int mostDamage = 0;
		for (int i = 0; i < InvUtil.MAIN_SIZE; i++) {
			if (i == inv.getSelectedSlot()) continue;
			ItemStack s = inv.getItem(i);
			if (needsRepair(s) && s.getDamageValue() > mostDamage) {
				mostDamage = s.getDamageValue();
				best = i;
			}
		}
		return best;
	}

	private static boolean needsRepair(ItemStack s) {
		if (!s.isDamaged()) return false;
		for (Holder<Enchantment> e : s.getEnchantments().keySet()) {
			if (e.is(Enchantments.MENDING)) return true;
		}
		return false;
	}
}
