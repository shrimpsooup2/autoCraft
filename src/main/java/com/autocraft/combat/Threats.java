package com.autocraft.combat;

import com.autocraft.Bot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.cubemob.MagmaCube;
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import net.minecraft.world.entity.monster.piglin.PiglinBrute;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.skeleton.WitherSkeleton;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;

/**
 * Who counts as a threat and how dangerous each mob is. Neutral mobs (zombified piglins, endermen,
 * spiders by day, piglins while we wear gold, wolves/bees/golems) only count once they are aggressive,
 * so the bot never provokes them.
 */
public final class Threats {
	private Threats() {}

	/** Mobs within {@code radius} that are (or will be) attacking us, nearest first. */
	public static List<Mob> collect(Bot bot, double radius) {
		Player p = bot.player();
		List<Mob> out = new ArrayList<>();
		for (Entity e : bot.scanner().entities(radius, e -> e instanceof Mob)) {
			Mob m = (Mob) e;
			if (isThreat(p, m)) out.add(m);
		}
		out.sort(Comparator.comparingDouble(m -> m.distanceToSqr(p)));
		return out;
	}

	public static boolean isThreat(Player p, Mob m) {
		if (!m.isAlive()) return false;
		if (m instanceof ZombifiedPiglin) return m.isAggressive();
		if (m instanceof Enderman enderman) return enderman.isCreepy() || m.isAggressive();
		if (m instanceof PiglinBrute) return true;
		if (m instanceof Piglin) return !PiglinAi.isWearingSafeArmor(p) || m.isAggressive();
		if (m instanceof Spider) return m.isAggressive() || !m.level().isBrightOutside();
		if (m instanceof Enemy) return true;
		return m instanceof NeutralMob && m.isAggressive();
	}

	/** Rough danger rating (zombie = 1). */
	public static double danger(Mob m) {
		if (m instanceof Warden) return 50;
		if (m instanceof Creeper c) return c.isPowered() ? 6 : 2.5;
		if (m instanceof PiglinBrute) return 4;
		if (m instanceof WitherSkeleton || m instanceof Hoglin) return 3;
		if (m instanceof Enderman || m instanceof Witch || m instanceof Blaze) return 2.5;
		if (m instanceof Ghast || m instanceof ZombifiedPiglin) return 2;
		if (m instanceof MagmaCube || m instanceof AbstractSkeleton || m instanceof Pillager) return 1.5;
		if (m instanceof Slime) return 0.8;
		if (m instanceof Spider) return 1.2;
		return Math.max(0.5, meleeHit(m) / 3.0);
	}

	/** Shoots from range: bows, crossbows (pillagers, and piglins carrying one — they shot the bot off its pillar), potions, fireballs. */
	public static boolean isRanged(Mob m) {
		return m instanceof AbstractSkeleton && !(m instanceof WitherSkeleton) || m instanceof Pillager || m instanceof Witch
				|| m instanceof Blaze || m instanceof Ghast || m instanceof Piglin && m.getMainHandItem().is(Items.CROSSBOW);
	}

	/**
	 * An archer's shot is on its way: a bow being drawn, or a crossbow loaded (it fires a second or two later). A
	 * crossbow being loaded is the opposite — nothing can come for over a second, the opening to close in. Read as
	 * "using an item" for both, the bot shielded through every reload and the trained fight (taught on bow draws)
	 * held back exactly then: a lone crossbow piglin went 90 s without taking a hit.
	 */
	public static boolean drawing(Mob m) {
		ItemStack hand = m.getMainHandItem();
		if (hand.getItem() instanceof CrossbowItem) return CrossbowItem.isCharged(hand);
		return m.isUsingItem();
	}

	/** Flying shooters we usually can't reach with a sword. */
	public static boolean isUnreachableShooter(Mob m) {
		return m instanceof Ghast || m instanceof Blaze && !m.onGround();
	}

	/** Melee damage of a stack in the main hand (1 = fist). */
	public static double attackDamage(ItemStack s) {
		ItemAttributeModifiers mods = s.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
		return mods.compute(Attributes.ATTACK_DAMAGE, 1.0, EquipmentSlot.MAINHAND);
	}

	/**
	 * A melee mob's hit as the server deals it: base attack damage plus the weapon in its hand, scaled for difficulty.
	 * Attack damage isn't synced, so the client's attribute value lacks the weapon — a piglin brute's golden axe read
	 * as 7 instead of 13, and the bot took on a brute it couldn't survive three hits from.
	 */
	public static double meleeHit(Mob m) {
		double base = m.getAttributes().hasAttribute(Attributes.ATTACK_DAMAGE) ? m.getAttributeBaseValue(Attributes.ATTACK_DAMAGE) : 0;
		ItemAttributeModifiers mods = m.getMainHandItem().getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
		double hit = mods.compute(Attributes.ATTACK_DAMAGE, base, EquipmentSlot.MAINHAND);
		return switch (m.level().getDifficulty()) {
			case PEACEFUL -> 0;
			case EASY -> Math.min(hit / 2 + 1, hit);
			case NORMAL -> hit;
			case HARD -> hit * 1.5;
		};
	}

	/** Its hits disable a raised shield for five seconds (it swings an axe): the shield is no cover against it. */
	public static boolean breaksShields(Mob m) {
		return m.getMainHandItem().is(ItemTags.AXES);
	}
}
