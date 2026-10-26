package com.autocraft.cinema;

import com.autocraft.mixin.MannequinAccessor;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
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
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * A client-side puppet: a mannequin (the bot, other players) or a mob that exists only on this client and goes
 * exactly where the shot says, every tick. Its walk animation comes from its own movement, like a real mob's.
 * Ids are negative so they never meet a server entity.
 */
final class Actor {
	private static int nextId = -2_000_000;

	final LivingEntity entity;
	private Vec3 pos;
	private float yaw;
	private float headYaw;
	private float pitch;
	private @Nullable Vec3 lookAt;
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
		e.setPos(pos.x, pos.y, pos.z);
		e.setYRot(yaw);
		e.setYHeadRot(yaw);
		e.setYBodyRot(yaw);
		e.setOldPosAndRot();
		level.addEntity(e);
		return a;
	}

	// ---- driving (called by the shot each tick; applied by the puppet's own tick) ----------------------------------

	Actor moveTo(Vec3 p) {
		pos = p;
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

	Vec3 pos() {
		return pos;
	}

	Vec3 eyes() {
		return pos.add(0, entity.getEyeHeight(), 0);
	}

	float yaw() {
		return yaw;
	}

	void apply(LivingEntity e) {
		e.setPos(pos.x, pos.y, pos.z);
		float head = headYaw;
		float p = pitch;
		if (lookAt != null) {
			Vec3 eye = pos.add(0, e.getEyeHeight(), 0);
			head = Anim.yawTo(eye, lookAt);
			p = Anim.pitchTo(eye, lookAt);
			float diff = wrap(head - yaw);
			if (Math.abs(diff) > 50) yaw += diff - Math.signum(diff) * 50;   // the body turns once the head is far round
		}
		e.setYRot(yaw);
		e.setYBodyRot(yaw);
		e.setYHeadRot(head);
		e.setXRot(p);
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
		for (int i = 0; i < 20; i++) {
			double dx = level.getRandom().nextGaussian() * 0.02, dy = level.getRandom().nextGaussian() * 0.02, dz = level.getRandom().nextGaussian() * 0.02;
			level.addParticle(ParticleTypes.POOF, pos.x + (level.getRandom().nextDouble() - 0.5) * 0.8, pos.y + level.getRandom().nextDouble() * 1.8,
					pos.z + (level.getRandom().nextDouble() - 0.5) * 0.8, dx, dy, dz);
		}
		remove();
	}

	void remove() {
		if (!entity.isRemoved()) ((ClientLevel) entity.level()).removeEntity(entity.getId(), Entity.RemovalReason.DISCARDED);
	}
}
