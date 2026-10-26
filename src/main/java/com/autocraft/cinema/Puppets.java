package com.autocraft.cinema;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * Vanilla mobs (rendered by vanilla, with vanilla animations and any shader pack) whose tick first moves them to
 * where their {@link Actor} says. The level has already stored last tick's position, so the step shows up as
 * movement: legs swing, and rendering interpolates between ticks.
 */
final class Puppets {
	private Puppets() {}

	static final class Man extends Mannequin implements Actor.Puppet {
		private @Nullable Actor actor;

		Man(EntityType<Mannequin> type, Level level) {
			super(type, level);
		}

		@Override
		public void actor(Actor actor) {
			this.actor = actor;
		}

		@Override
		public void tick() {
			if (actor != null) actor.apply(this);
			super.tick();
		}
	}

	static final class Zom extends Zombie implements Actor.Puppet {
		private @Nullable Actor actor;

		Zom(EntityType<Zombie> type, Level level) {
			super(type, level);
		}

		@Override
		public void actor(Actor actor) {
			this.actor = actor;
		}

		@Override
		public void tick() {
			if (actor != null) actor.apply(this);
			super.tick();
		}
	}

	static final class Skel extends Skeleton implements Actor.Puppet {
		private @Nullable Actor actor;

		Skel(EntityType<Skeleton> type, Level level) {
			super(type, level);
		}

		@Override
		public void actor(Actor actor) {
			this.actor = actor;
		}

		@Override
		public void tick() {
			if (actor != null) actor.apply(this);
			super.tick();
		}
	}

	static final class Creep extends Creeper implements Actor.Puppet {
		private @Nullable Actor actor;

		Creep(EntityType<Creeper> type, Level level) {
			super(type, level);
		}

		@Override
		public void actor(Actor actor) {
			this.actor = actor;
		}

		@Override
		public void tick() {
			if (actor != null) actor.apply(this);
			super.tick();
		}
	}
}
