package com.autocraft.procedure;

import com.autocraft.Bot;
import com.autocraft.control.InvUtil;
import com.autocraft.path.Goal;
import com.autocraft.path.Navigator;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Small building blocks shared by procedures: walk up to things, use items on blocks and animals, pick up drops. */
public final class Actions {
	public enum Result { WORKING, DONE, FAILED }

	private Actions() {}

	/** Walk until {@code pos} is within reach. DONE when in reach. */
	public static Result reach(Bot bot, BlockPos pos) {
		LocalPlayer p = bot.player();
		if (Navigator.settled(p) && p.isWithinBlockInteractionRange(pos, -0.5)) {
			bot.nav().stop();
			return Result.DONE;
		}
		bot.nav().setGoal(new Goal.Reach(pos, 3.5));
		return bot.nav().tick() == Navigator.Status.FAILED ? Result.FAILED : Result.WORKING;
	}

	/** Walk next to {@code entity}. DONE when it's in interaction range. */
	public static Result reach(Bot bot, Entity entity, BlockPos[] chaseAt) {
		LocalPlayer p = bot.player();
		if (p.isWithinEntityInteractionRange(entity, 0) && p.hasLineOfSight(entity)) {
			bot.nav().stop();
			return Result.DONE;
		}
		BlockPos at = entity.blockPosition();
		if (chaseAt[0] == null || chaseAt[0].distSqr(at) > 4) chaseAt[0] = at;
		bot.nav().setGoal(new Goal.Near(chaseAt[0], 1));
		return bot.nav().tick() == Navigator.Status.FAILED ? Result.FAILED : Result.WORKING;
	}

	/** Right-click {@code pos} holding an item matching {@code item} (on the face toward us). */
	public static boolean useOn(Bot bot, BlockPos pos, Predicate<ItemStack> item) {
		LocalPlayer p = bot.player();
		if (!InvUtil.inventoryMenuActive(p)) p.closeContainer();
		if (!InvUtil.select(p, item)) return false;
		Direction side = bot.controls().faceToward(pos);
		Vec3 hit = Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(side.getUnitVec3i()).scale(0.5));
		bot.controls().lookAt(hit);
		InteractionResult r = bot.mc().gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(hit, side, pos, false));
		p.swing(InteractionHand.MAIN_HAND, p.getMainHandItem().getAttackAnimation(), false);
		return r.consumesAction();
	}

	/** Right-click an animal holding an item matching {@code item} (shears on sheep, bucket on cow). */
	public static boolean useOn(Bot bot, Entity entity, Predicate<ItemStack> item) {
		LocalPlayer p = bot.player();
		if (!InvUtil.inventoryMenuActive(p)) p.closeContainer();
		if (!InvUtil.select(p, item)) return false;
		Vec3 center = entity.getBoundingBox().getCenter();
		bot.controls().lookAt(center);
		InteractionResult r = bot.mc().gameMode.interact(p, entity, new EntityHitResult(entity, center), InteractionHand.MAIN_HAND);
		p.swing(InteractionHand.MAIN_HAND, p.getMainHandItem().getAttackAnimation(), false);
		return r.consumesAction();
	}

	/** Walk over the nearest matching drop within {@code radius}. False when there's nothing to pick up. */
	public static boolean pickUp(Bot bot, Predicate<ItemStack> what, double radius) {
		List<ItemEntity> items = bot.scanner().items(radius, what);
		if (items.isEmpty()) return false;
		ItemEntity nearest = items.stream().min(Comparator.comparingDouble(e -> e.distanceToSqr(bot.player()))).orElseThrow();
		bot.nav().setGoal(new Goal.Near(nearest.blockPosition(), 0));
		return bot.nav().tick() != Navigator.Status.FAILED;
	}

	/** Nearest block matching within the scanner's range, or null. */
	public static @Nullable BlockPos nearestBlock(Bot bot, Predicate<net.minecraft.world.level.block.state.BlockState> match, int chunks) {
		List<BlockPos> found = bot.scanner().findBlocks(match, chunks, 16);
		BlockPos feet = Navigator.feet(bot.player());
		return found.stream().min(Comparator.comparingDouble(b -> b.distSqr(feet))).orElse(null);
	}

	/** A carried tool that will do (any tier, not nearly broken), else {@code fallback}: what a procedure lists as its need. */
	static net.minecraft.world.item.Item carriedTool(Predicate<ItemStack> ok, net.minecraft.world.item.Item fallback) {
		LocalPlayer p = net.minecraft.client.Minecraft.getInstance().player;
		int i = p == null ? -1 : InvUtil.find(p, s -> ok.test(s) && !InvUtil.nearlyBroken(s));
		return i < 0 ? fallback : p.getInventory().getItem(i).getItem();
	}
}
