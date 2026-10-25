package com.autocraft.control;

import com.autocraft.Settings;
import com.autocraft.loot.LootBook;
import java.util.function.Predicate;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
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
 * Actions (mine/attack/place/use) talk to MultiPlayerGameMode directly. Looking goes through {@link Aim}
 * (smooth, curved turns) unless smooth aim is off; movement keys are meant relative to where the bot is
 * turning to, and are remapped while the view is still on its way there.
 */
public final class Controls {
	/** Remapped movement: a key is pressed when the wanted direction is within ~67° of it. */
	private static final double KEY_THRESHOLD = 0.38;

	private final Minecraft mc;
	private final Aim aim = new Aim();
	private boolean forward, back, left, right, jump, sneak, sprint, use;
	private boolean mining;
	private boolean driving;
	private boolean aimedLastTick = true;
	/** Ancient city: sneak everywhere, never sprint, only wool may be mined or placed. */
	private boolean quiet;

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

	public void setQuiet(boolean value) { quiet = value; }
	public boolean quiet() { return quiet; }

	public void apply() {
		LocalPlayer p = mc.player;
		aim.update(p);
		boolean f = forward, b = back, l = left, r = right;
		float delta = Mth.wrapDegrees(aim.targetYaw(p) - p.getYRot());
		if (Math.abs(delta) > 1 && (f || b || l || r)) {
			// keys were chosen for the target yaw: rotate the wanted direction into the current view
			double fw = (f ? 1 : 0) - (b ? 1 : 0), lw = (l ? 1 : 0) - (r ? 1 : 0);
			double a = Math.toRadians(delta), cos = Math.cos(a), sin = Math.sin(a);
			double lNow = lw * cos - fw * sin, fNow = fw * cos + lw * sin;
			f = fNow > KEY_THRESHOLD;
			b = fNow < -KEY_THRESHOLD;
			l = lNow > KEY_THRESHOLD;
			r = lNow < -KEY_THRESHOLD;
		}
		Options o = mc.options;
		o.keyUp.setDown(f);
		o.keyDown.setDown(b);
		o.keyLeft.setDown(l);
		o.keyRight.setDown(r);
		o.keyJump.setDown(jump);
		// quiet: footsteps, splashes and landings make no vibrations while sneaking; sneaking refuses to step off a
		// ledge though, so let go for that one step (and press again in the air, before landing)
		o.keyShift.setDown(sneak || quiet && !(p.onGround() && aboutToStepDown(p, f, b, l, r)));
		o.keySprint.setDown(sprint && !quiet);
		o.keyUse.setDown(use);
		o.keyAttack.setDown(false);
		driving = true;
		aimedLastTick = aim.settled(); // this tick's movement packet carries the settled view
	}

	/** Walking on with these keys would leave the ground (a drop of a block or more). */
	private boolean aboutToStepDown(LocalPlayer p, boolean f, boolean b, boolean l, boolean r) {
		double fw = (f ? 1 : 0) - (b ? 1 : 0), lw = (l ? 1 : 0) - (r ? 1 : 0);
		if (fw == 0 && lw == 0) return false;
		double yaw = Math.toRadians(p.getYRot());
		double dx = lw * Math.cos(yaw) - fw * Math.sin(yaw), dz = fw * Math.cos(yaw) + lw * Math.sin(yaw);
		double len = Math.sqrt(dx * dx + dz * dz);
		BlockPos below = BlockPos.containing(p.getX() + dx / len * 0.55, p.getY() - 0.5, p.getZ() + dz / len * 0.55);
		return mc.level.getBlockState(below).getCollisionShape(mc.level, below).isEmpty();
	}

	/** Let go of everything (bot stopped / paused). Safe to call repeatedly. */
	public void releaseAll() {
		if (!driving) return;
		beginTick();
		Options o = mc.options;
		o.keyUp.setDown(false);
		o.keyDown.setDown(false);
		o.keyLeft.setDown(false);
		o.keyRight.setDown(false);
		o.keyJump.setDown(false);
		o.keyShift.setDown(false);
		o.keySprint.setDown(false);
		o.keyUse.setDown(false);
		o.keyAttack.setDown(false);
		aim.cancel();
		aimedLastTick = true;
		driving = false;
		if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
	}

	/** Every rendered frame: keep turning along the current curve (smooth on screen, not just per tick). */
	public void frame() {
		if (driving && mc.player != null) aim.update(mc.player);
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

	/** Turn toward yaw/pitch: along a smooth curve with smooth aim on, else at once. */
	public void face(float yaw, float pitch) {
		LocalPlayer p = mc.player;
		if (Settings.get().smoothAim) aim.target(p, yaw, pitch);
		else aim.snap(p, yaw, pitch);
	}

	/** Turn at once (reflexes: shield up toward an arrow, water bucket straight down). */
	public void snap(float yaw, float pitch) {
		aim.snap(mc.player, yaw, pitch);
	}

	/**
	 * True when the view has reached its target and the server has been told (settled since the last tick):
	 * wait for this before clicks whose result depends on facing (stairs, observers, furnaces).
	 */
	public boolean aimed() {
		return aimedLastTick && aim.settled();
	}

	/** Turn to walk toward {@code target} horizontally, keeping pitch level-ish. */
	public void faceHorizontally(Vec3 target) {
		faceHorizontally(target, 10.0F);
	}

	/** Turn toward {@code target}'s direction at {@code pitch} (swimming: dip under, skim the surface, come up for air). */
	public void faceHorizontally(Vec3 target, float pitch) {
		LocalPlayer p = mc.player;
		double dx = target.x - p.getX(), dz = target.z - p.getZ();
		face((float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F, pitch);
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
		if (quiet && !state.is(BlockTags.DAMPENS_VIBRATIONS)) return true; // a sculk sensor would hear it: not here
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
		aim.finish(mc.player); // untargeted use goes where the view points: finish the turn first
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
			if (quiet && !mc.player.getMainHandItem().is(ItemTags.WOOL)) return false; // only wool goes down silently
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
