package com.autocraft.behavior;

import com.autocraft.Bot;
import com.autocraft.combat.Threats;
import com.autocraft.control.InvUtil;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/** Drink milk to clear harmful effects (honey for plain poison) once no mob is right on top of us. */
public final class EffectReflex implements Behavior {
	private static final List<Holder<MobEffect>> HARMFUL = List.of(
			MobEffects.POISON, MobEffects.WITHER, MobEffects.HUNGER, MobEffects.WEAKNESS, MobEffects.SLOWNESS,
			MobEffects.MINING_FATIGUE, MobEffects.BLINDNESS, MobEffects.NAUSEA, MobEffects.DARKNESS, MobEffects.LEVITATION);
	/** Ignore effects about to run out on their own. */
	private static final int MIN_DURATION = 100;
	private static final Predicate<ItemStack> MILK = s -> s.is(Items.MILK_BUCKET);
	private static final Predicate<ItemStack> MILK_OR_HONEY = s -> s.is(Items.MILK_BUCKET) || s.is(Items.HONEY_BOTTLE);

	@Override
	public String name() {
		return "effects";
	}

	@Override
	public boolean tick(Bot bot) {
		LocalPlayer p = bot.player();
		Holder<MobEffect> bad = harmfulEffect(p, false);
		if (bad == null) return false;
		for (Mob m : Threats.collect(bot, 4)) {
			if (m.distanceTo(p) < 4) return false;
		}
		Predicate<ItemStack> cure = harmfulEffect(p, true) == null ? MILK_OR_HONEY : MILK; // honey only cures poison
		if (InvUtil.find(p, cure) < 0) return false;
		if (!InvUtil.inventoryMenuActive(p)) p.closeContainer();
		if (!InvUtil.select(p, cure) && !p.isUsingItem()) return false;
		bot.controls().face(p.getYRot(), -90.0F);
		if (!p.isUsingItem()) bot.controls().useItem(InteractionHand.MAIN_HAND);
		bot.controls().holdUse();
		bot.setStatus("curing " + bad.value().getDisplayName().getString());
		return true;
	}

	private static @Nullable Holder<MobEffect> harmfulEffect(LocalPlayer p, boolean ignorePoison) {
		for (Holder<MobEffect> e : HARMFUL) {
			if (ignorePoison && e == MobEffects.POISON) continue;
			MobEffectInstance inst = p.getEffect(e);
			if (inst != null && (inst.getDuration() > MIN_DURATION || inst.isInfiniteDuration())) return e;
		}
		return null;
	}
}
