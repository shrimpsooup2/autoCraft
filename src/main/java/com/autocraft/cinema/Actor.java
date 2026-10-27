package com.autocraft.cinema;

import com.autocraft.mixin.MannequinAccessor;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * A client-side puppet: a mannequin (the bot, other players) or a mob that exists only on this client and goes
 * where the shot says, every tick. It doesn't go through blocks: on its feet it stands on the floor under the
 * scripted spot (stepping up a block or down a little as the ground goes), and when a wall is in the way it waits
 * there (only after a second does it give up and go through, to stay with the script). Off its feet on purpose —
 * a jump, a fall, standing where the script says on a wall it is building — it's {@link #free}: only kept out of
 * blocks. Its walk animation comes from its own movement, like a real mob's. Ids are negative so they never meet a
 * server entity.
 */
final class Actor {
	private static int nextId = -2_000_000;
	/** Highest step up, lowest step down it takes on its feet; how far up it's pushed out of a block when free. */
	private static final double STEP = 1.1, DROP = 1.3, PUSH = 1.6;
	/** Ticks it waits at a wall before going through anyway. */
	private static final int PATIENCE = 20;

	final LivingEntity entity;
	private Vec3 pos;
	private float yaw;
	private float headYaw;
	private float pitch;
	private @Nullable Vec3 lookAt;
	/** Where it really is: the scripted spot kept out of blocks. */
	private @Nullable Vec3 at;
	private int blocked;
	/** Off its feet on purpose (jumping, falling, perched where the script says): not put on the floor, only kept out of blocks. */
	boolean free;
	/** The status light floating over the bot's head (0 = none). */
	int light;
	double lightFlash;

	private Actor(LivingEntity entity, Vec3 pos, float yaw) {
		this.entity = entity;
		this.pos = pos;
		this.yaw = this.headYaw = yaw;
	}

	interface Puppet {
		void actor(Actor actor);
	}

	// ---- spawning ---------------------------------------------------------------------------------------------

	/** A player-shaped puppet wearing this player's skin. */
	static Actor me(ClientLevel level, Vec3 pos, float yaw) {
		Minecraft mc = Minecraft.getInstance();
		GameProfile profile = mc.player != null ? mc.player.getGameProfile() : mc.getGameProfile();
		return mannequin(level, pos, yaw, ResolvableProfile.createResolved(profile));
	}

	/** A player-shaped puppet with a default skin (picked by the name). */
	static Actor player(ClientLevel level, Vec3 pos, float yaw, String name) {
		UUID id = UUID.nameUUIDFromBytes(("autocraft-film:" + name).getBytes());
		return mannequin(level, pos, yaw, ResolvableProfile.createResolved(new GameProfile(id, name)));
	}

	private static Actor mannequin(ClientLevel level, Vec3 pos, float yaw, ResolvableProfile profile) {
		Puppets.Man m = new Puppets.Man(level);
		((MannequinAccessor) (Object) m).autocraft$setProfile(profile);
		((MannequinAccessor) (Object) m).autocraft$setHideDescription(true);
		return spawn(level, m, pos, yaw);
	}

	static Actor zombie(ClientLevel level, Vec3 pos, float yaw) {
		return spawn(level, new Puppets.Zom(EntityTypes.ZOMBIE, level), pos, yaw);
	}

	static Actor skeleton(ClientLevel level, Vec3 pos, float yaw) {
		Actor a = spawn(level, new Puppets.Skel(EntityTypes.SKELETON, level), pos, yaw);
		a.hold(Items.BOW);
		return a;
	}

	static Actor creeper(ClientLevel level, Vec3 pos, float yaw) {
		return spawn(level, new Puppets.Creep(EntityTypes.CREEPER, level), pos, yaw);
	}

	private static Actor spawn(ClientLevel level, LivingEntity e, Vec3 pos, float yaw) {
		Actor a = new Actor(e, pos, yaw);
		((Puppet) e).actor(a);
		e.setId(nextId--);
		Vec3 p = a.settle(e, pos);
		a.at = p;
		e.setPos(p.x, p.y, p.z);
		e.setYRot(yaw);
		e.setYHeadRot(yaw);
		e.setYBodyRot(yaw);
		e.setOldPosAndRot();
		level.addEntity(e);
		return a;
	}

	/** The puppet's tick: drive it, and if that ever throws, stop the film rather than the game. */
	static void drive(@Nullable Actor a, LivingEntity e) {
		if (a == null) return;
		try {
			a.apply(e);
		} catch (RuntimeException ex) {
			Film.fail("a puppet", ex);
		}
	}

	// ---- driving (called by the shot each tick; applied by the puppet's own tick) ----------------------------------

	Actor moveTo(Vec3 p) {
		pos = p;
		return this;
	}

	/** Jump straight there: no frame is drawn of it sliding across, and nothing in between stops it (a cut, a hop). */
	Actor teleport(Vec3 p) {
		pos = p;
		at = settle(entity, p);
		blocked = 0;
		entity.setPos(at.x, at.y, at.z);
		entity.setOldPosAndRot();
		return this;
	}

	Actor face(float yawDegrees) {
		yaw = headYaw = yawDegrees;
		lookAt = null;
		return this;
	}

	/** Turn the head (and, if far enough round, the body) toward a point; null = look ahead. */
	Actor look(@Nullable Vec3 point) {
		lookAt = point;
		return this;
	}

	Actor pitch(float degrees) {
		pitch = degrees;
		return this;
	}

	/** Follow a track: position at t, facing the way it goes when moving. */
	Actor follow(Anim.Track track, double t) {
		pos = track.pos(t);
		Vec3 h = track.heading(t);
		if (h.lengthSqr() > 1e-6) yaw = headYaw = Anim.yaw(h);
		return this;
	}

	/** Where the script puts it. */
	Vec3 pos() {
		return pos;
	}

	Vec3 eyes() {
		return (at != null ? at : pos).add(0, entity.getEyeHeight(), 0);
	}

	float yaw() {
		return yaw;
	}

	void apply(LivingEntity e) {
		Vec3 p = settle(e, pos);
		at = p;
		e.setPos(p.x, p.y, p.z);
		float head = headYaw;
		float pt = pitch;
		if (lookAt != null) {
			Vec3 eye = p.add(0, e.getEyeHeight(), 0);
			head = Anim.yawTo(eye, lookAt);
			pt = Anim.pitchTo(eye, lookAt);
			float diff = wrap(head - yaw);
			if (Math.abs(diff) > 50) yaw += diff - Math.signum(diff) * 50;   // the body turns once the head is far round
		}
		e.setYRot(yaw);
		e.setYBodyRot(yaw);
		e.setYHeadRot(head);
		e.setXRot(pt);
	}

	// ---- keeping out of blocks -------------------------------------------------------------------------------------

	/** The scripted spot kept out of blocks: on the floor (on its feet), pushed up out of a block, or waiting at a wall. */
	private Vec3 settle(LivingEntity e, Vec3 want) {
		Level level = e.level();
		EntityDimensions dim = e.getDimensions(e.getPose());
		Vec3 p = want;
		if (!free && !wet(level, want)) {
			Double floor = floor(level, e, dim, want);
			if (floor != null) p = new Vec3(want.x, floor, want.z);
		}
		double reach = free ? PUSH : STEP;
		for (double dy = 0; dy <= reach + 1e-6; dy += 0.0625) {
			Vec3 up = p.add(0, dy, 0);
			if (fits(level, e, dim, up)) {
				blocked = 0;
				return up;
			}
		}
		// a wall: wait where it was; after a while go through anyway, to stay with the script
		if (at != null && blocked < PATIENCE && fits(level, e, dim, at)) {
			blocked++;
			return at;
		}
		return p;
	}

	/**
	 * The floor to stand on under the scripted spot: the highest surface from a step above it to a little below it
	 * with room to stand on it. Null when there is none in that range (a drop, a pit): then it goes where scripted.
	 */
	private static @Nullable Double floor(Level level, Entity e, EntityDimensions dim, Vec3 want) {
		double half = dim.width() / 2 - 1e-3;
		AABB column = new AABB(want.x - half, want.y - DROP, want.z - half, want.x + half, want.y + STEP, want.z + half);
		double best = Double.NEGATIVE_INFINITY;
		for (VoxelShape s : level.getBlockCollisions(e, column)) {
			if (s.isEmpty()) continue;
			double top = s.max(Direction.Axis.Y);
			if (top > want.y + STEP + 1e-6 || top < want.y - DROP || top <= best) continue;
			if (fits(level, e, dim, new Vec3(want.x, top, want.z))) best = top;
		}
		return best == Double.NEGATIVE_INFINITY ? null : best;
	}

	private static boolean fits(Level level, Entity e, EntityDimensions dim, Vec3 feet) {
		return level.noCollision(e, dim.makeBoundingBox(feet).deflate(1e-3));
	}

	/** Wading or swimming: the water holds it where the script says. */
	private static boolean wet(Level level, Vec3 want) {
		return !level.getFluidState(BlockPos.containing(want.x, want.y + 0.2, want.z)).isEmpty()
				|| !level.getFluidState(BlockPos.containing(want.x, want.y + 1.0, want.z)).isEmpty();
	}

	private static float wrap(float deg) {
		deg %= 360;
		if (deg >= 180) deg -= 360;
		if (deg < -180) deg += 360;
		return deg;
	}

	// ---- actions ----------------------------------------------------------------------------------------------

	void swing() {
		entity.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
	}

	void hold(ItemLike item) {
		entity.setItemSlot(EquipmentSlot.MAINHAND, item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item));
	}

	void holdOff(ItemLike item) {
		entity.setItemSlot(EquipmentSlot.OFFHAND, item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item));
	}

	void crouch(boolean on) {
		entity.setShiftKeyDown(on);
		entity.setPose(on ? Pose.CROUCHING : Pose.STANDING);
	}

	void hurt() {
		entity.hurtDuration = 10;
		entity.hurtTime = 10;
	}

	void aggressive(boolean on) {
		if (entity instanceof net.minecraft.world.entity.Mob mob) mob.setAggressive(on);
	}

	/** Creeper fuse: +1 swells and flashes, -1 calms down. */
	void fuse(int dir) {
		if (entity instanceof Creeper c) c.setSwellDir(dir);
	}

	/** Vanish in a puff of smoke, like a mob dying (or a player logging off). */
	void poof() {
		ClientLevel level = (ClientLevel) entity.level();
		Vec3 p = at != null ? at : pos;
		for (int i = 0; i < 20; i++) {
			double dx = level.getRandom().nextGaussian() * 0.02, dy = level.getRandom().nextGaussian() * 0.02, dz = level.getRandom().nextGaussian() * 0.02;
			level.addParticle(ParticleTypes.POOF, p.x + (level.getRandom().nextDouble() - 0.5) * 0.8, p.y + level.getRandom().nextDouble() * 1.8,
					p.z + (level.getRandom().nextDouble() - 0.5) * 0.8, dx, dy, dz);
		}
		remove();
	}

	void remove() {
		if (!entity.isRemoved()) ((ClientLevel) entity.level()).removeEntity(entity.getId(), Entity.RemovalReason.DISCARDED);
	}
}
