package com.autocraft.control;

import com.autocraft.loot.LootBook;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The bot's hands and feet. Movement keys are "desired state" for the current tick: {@link #beginTick()}
 * clears them, behaviors set what they need, {@link #apply()} pushes them into the key mappings.
 * Actions (mine/attack/place/use) talk to MultiPlayerGameMode directly.
 */
public final class Controls {
	private final Minecraft mc;
	private boolean forward, back, left, right, jump, sneak, sprint, use;
	private boolean mining;
	private boolean driving;

	public Controls(Minecraft mc) {
		this.mc = mc;
	}

	// ---- per-tick key state -------------------------------------------------------------------

	public void beginTick() {
		forward = back = left = right = jump = sneak = sprint = use = false;
		mining = false;
	}

	public void forward() { forward = true; }
	public void back() { back = true; }
	public void left() { left = true; }
	public void right() { right = true; }
	public void jump() { jump = true; }
	public void sneak() { sneak = true; }
	public void sprint() { sprint = true; }
	/** Hold the use key (keeps eating / blocking / drawing alive). Start the use with {@link #useItem}. */
	public void holdUse() { use = true; }

	/** True while the bot is breaking a block this tick; the mixin keeps vanilla from aborting it. */
	public boolean isMining() { return mining; }

	public void apply() {
		Options o = mc.options;
		o.keyUp.setDown(forward);
		o.keyDown.setDown(back);
		o.keyLeft.setDown(left);
		o.keyRight.setDown(right);
		o.keyJump.setDown(jump);
		o.keyShift.setDown(sneak);
		o.keySprint.setDown(sprint);
		o.keyUse.setDown(use);
		o.keyAttack.setDown(false);
		driving = true;
	}

	/** Let go of everything (bot stopped / paused). Safe to call repeatedly. */
	public void releaseAll() {
		if (!driving) return;
		beginTick();
		apply();
		driving = false;
		if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
	}

	// ---- looking ----------------------------------------------------------------------------

	public void lookAt(Vec3 target) {
		LocalPlayer p = mc.player;
		Vec3 eye = p.getEyePosition();
		double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
		float pitch = (float) -(Mth.atan2(dy, horizontal) * Mth.RAD_TO_DEG);
		face(yaw, pitch);
	}

	public void lookAt(BlockPos pos) {
		lookAt(Vec3.atCenterOf(pos));
	}

	public void face(float yaw, float pitch) {
		LocalPlayer p = mc.player;
		p.setYRot(Mth.wrapDegrees(yaw));
		p.setXRot(Mth.clamp(pitch, -90.0F, 90.0F));
		p.setYHeadRot(p.getYRot());
	}

	/** Turn to walk toward {@code target} horizontally, keeping pitch level-ish. */
	public void faceHorizontally(Vec3 target) {
		LocalPlayer p = mc.player;
		double dx = target.x - p.getX(), dz = target.z - p.getZ();
		face((float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F, 10.0F);
	}

	// ---- actions ----------------------------------------------------------------------------

	/**
	 * Break the block at {@code pos} with the best tool. Call every tick until it returns true
	 * (block gone or not breakable from here).
	 */
	public boolean mine(BlockPos pos) {
		return mine(pos, LootBook.ToolUse.ANY);
	}

	/** As {@link #mine(BlockPos)} with the tool a loot drop needs (shears, or anything but shears). */
	public boolean mine(BlockPos pos, LootBook.ToolUse tool) {
		BlockState state = mc.level.getBlockState(pos);
		if (state.isAir() || state.canBeReplaced() && !state.getFluidState().isEmpty()) return true; // gone, or plain fluid
		if (state.getDestroySpeed(mc.level, pos) < 0) return true; // unbreakable: caller must route around
		InvUtil.selectTool(mc.player, state, tool);
		Direction side = faceToward(pos);
		lookAt(Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(side.getUnitVec3i()).scale(0.5)));
		mining = true;
		if (mc.gameMode.continueDestroyBlock(pos, side)) {
			swingMainHand();
		}
		return false;
	}

	/** The block face pointing most toward the player's eyes. */
	public Direction faceToward(BlockPos pos) {
		Vec3 d = mc.player.getEyePosition().subtract(Vec3.atCenterOf(pos));
		return Direction.getApproximateNearest(d.x, d.y, d.z);
	}

	public void attack(Entity target) {
		lookAt(target.getBoundingBox().getCenter());
		mc.gameMode.attack(mc.player, target);
		swingMainHand();
	}

	private void swingMainHand() {
		LocalPlayer p = mc.player;
		p.swing(InteractionHand.MAIN_HAND, p.getMainHandItem().getAttackAnimation(), false);
		p.connection.send(ServerboundPunchPacket.INSTANCE);
	}

	/** Start using the item in {@code hand} (eat, drink, raise shield, bucket). Hold with {@link #holdUse()}. */
	public InteractionResult useItem(InteractionHand hand) {
		return mc.gameMode.useItem(mc.player, hand);
	}

	/** Right-click a block (open chest/table/furnace, press button...). */
	public InteractionResult interact(BlockPos pos) {
		Direction side = faceToward(pos);
		Vec3 hit = Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(side.getUnitVec3i()).scale(0.5));
		lookAt(hit);
		return mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, side, pos, false));
	}

	/**
	 * Place a block matching {@code item} into {@code target} by clicking a solid neighbor face.
	 * Returns false if there is no item or no face to click.
	 */
	public boolean place(BlockPos target, Predicate<ItemStack> item) {
		if (!mc.level.getBlockState(target).canBeReplaced()) return false;
		for (Direction dir : Direction.values()) {
			BlockPos against = target.relative(dir);
			BlockState s = mc.level.getBlockState(against);
			if (s.isAir() || s.canBeReplaced()) continue;
			Direction clickedFace = dir.getOpposite();
			Vec3 hit = Vec3.atCenterOf(against).add(Vec3.atLowerCornerOf(clickedFace.getUnitVec3i()).scale(0.5));
			if (mc.player.getEyePosition().distanceToSqr(hit) > sq(mc.player.blockInteractionRange())) continue;
			if (!InvUtil.select(mc.player, item)) return false;
			lookAt(hit);
			InteractionResult r = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
					new BlockHitResult(hit, clickedFace, against, false));
			if (r instanceof InteractionResult.Success) {
				swingMainHand();
			}
			return r.consumesAction();
		}
		return false;
	}

	private static double sq(double v) {
		return v * v;
	}
}
