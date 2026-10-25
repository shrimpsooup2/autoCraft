package com.autocraft.travel;

import com.autocraft.Bot;
import com.autocraft.control.Controls;
import com.autocraft.control.InvUtil;
import com.autocraft.path.Goal;
import com.autocraft.path.Navigator;
import com.autocraft.world.BlockUtil;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Crosses water or ice by boat: place the boat, get in, steer with left/right (boats turn, the rider
 * doesn't steer), get out at the far shore, break the boat and pick it back up.
 */
public final class BoatTravel {
	public enum Status { RUNNING, DONE, FAILED }

	private enum Phase { PLACE, MOUNT, SAIL, DISMOUNT, PICKUP }

	private static final int SAMPLE_LENGTH = 64;
	private static final int MIN_WATER_SAMPLES = 16;
	private static final double ARRIVE_DISTANCE = 6;
	private static final float STEER_DEADBAND = 8;
	private static final int STALL_TICKS = 100;

	private final BlockPos target;
	private Phase phase = Phase.PLACE;
	private int phaseTicks;
	private int placeAttempts;
	private @Nullable AbstractBoat boat;
	private Vec3 lastProgressPos = Vec3.ZERO;
	private int stallTicks;

	public BoatTravel(BlockPos target) {
		this.target = target.immutable();
	}

	public static boolean hasBoat(Player p) {
		return InvUtil.find(p, BoatTravel::isBoat) >= 0;
	}

	private static boolean isBoat(ItemStack s) {
		return s.getItem() instanceof BoatItem;
	}

	/** The straight line toward {@code target} is mostly water/ice and we carry a boat. */
	public static boolean worthIt(Bot bot, BlockPos target) {
		return hasBoat(bot.player()) && waterAhead(bot, target);
	}

	/** The straight line toward {@code target} is mostly water/ice: a boat would carry us (and beats swimming). */
	public static boolean waterAhead(Bot bot, BlockPos target) {
		LocalPlayer p = bot.player();
		Level level = bot.mc().level;
		double dx = target.getX() + 0.5 - p.getX(), dz = target.getZ() + 0.5 - p.getZ();
		double len = Math.sqrt(dx * dx + dz * dz);
		if (len < 8) return false;
		int wet = 0;
		int samples = (int) Math.min(SAMPLE_LENGTH, len);
		for (int i = 1; i <= samples; i++) {
			int x = Mth.floor(p.getX() + dx / len * i), z = Mth.floor(p.getZ() + dz / len * i);
			if (!level.hasChunk(x >> 4, z >> 4)) break;
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
			if (isBoatSurface(level.getBlockState(new BlockPos(x, y, z)))) wet++;
		}
		return wet >= MIN_WATER_SAMPLES;
	}

	private static boolean isBoatSurface(BlockState s) {
		return BlockUtil.isWater(s) || s.is(BlockTags.ICE);
	}

	public Status tick(Bot bot) {
		LocalPlayer p = bot.player();
		Controls c = bot.controls();
		phaseTicks++;
		bot.setStatus("boating " + phase.name().toLowerCase() + " toward " + target.toShortString());
		switch (phase) {
			case PLACE -> {
				if (p.getVehicle() instanceof AbstractBoat riding) {
					boat = riding;
					return enter(Phase.SAIL);
				}
				BlockPos launch = findLaunch(bot);
				if (launch == null || placeAttempts >= 2) return Status.FAILED;
				Vec3 top = Vec3.atCenterOf(launch).add(0, 0.5, 0);
				if (p.getEyePosition().distanceTo(top) > 4.0) {
					bot.nav().setGoal(new Goal.Near(launch.above(), 2));
					if (bot.nav().tick() == Navigator.Status.FAILED) return Status.FAILED;
					return Status.RUNNING;
				}
				bot.nav().stop();
				if (!InvUtil.select(p, BoatTravel::isBoat)) return Status.FAILED;
				c.lookAt(top);
				c.useItem(InteractionHand.MAIN_HAND);
				placeAttempts++;
				return enter(Phase.MOUNT);
			}
			case MOUNT -> {
				if (p.getVehicle() instanceof AbstractBoat riding) {
					boat = riding;
					lastProgressPos = riding.position();
					return enter(Phase.SAIL);
				}
				if (boat == null || !boat.isAlive()) boat = nearestBoat(bot, 6);
				if (boat == null) return phaseTicks > 20 ? enter(Phase.PLACE) : Status.RUNNING;
				if (p.distanceTo(boat) > 3) {
					bot.nav().setGoal(new Goal.Near(boat.blockPosition(), 1));
					bot.nav().tick();
					return phaseTicks > 200 ? Status.FAILED : Status.RUNNING;
				}
				if (phaseTicks % 5 == 0) {
					c.lookAt(boat.position());
					bot.mc().gameMode.interact(p, boat, new EntityHitResult(boat), InteractionHand.MAIN_HAND);
				}
				return phaseTicks > 100 ? Status.FAILED : Status.RUNNING;
			}
			case SAIL -> {
				if (!(p.getVehicle() instanceof AbstractBoat riding)) {
					return boat != null && boat.isAlive() && p.distanceTo(boat) < 6 ? enter(Phase.MOUNT) : Status.FAILED;
				}
				boat = riding;
				return sail(bot, riding, c);
			}
			case DISMOUNT -> {
				if (p.isPassenger()) {
					c.sneak();
					return phaseTicks > 40 ? Status.FAILED : Status.RUNNING;
				}
				return enter(Phase.PICKUP);
			}
			case PICKUP -> {
				return pickup(bot, p, c);
			}
		}
		return Status.RUNNING;
	}

	private Status sail(Bot bot, AbstractBoat riding, Controls c) {
		Vec3 pos = riding.position();
		double dx = target.getX() + 0.5 - pos.x, dz = target.getZ() + 0.5 - pos.z;
		if (dx * dx + dz * dz < ARRIVE_DISTANCE * ARRIVE_DISTANCE) return enter(Phase.DISMOUNT);
		float bearing = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
		float diff = Mth.wrapDegrees(bearing - riding.getYRot());
		if (diff > STEER_DEADBAND) c.right();
		else if (diff < -STEER_DEADBAND) c.left();
		if (Math.abs(diff) < 60) c.forward();
		c.face(riding.getYRot(), 15.0F);

		if (shoreAhead(bot, riding)) return enter(Phase.DISMOUNT);
		if (pos.distanceToSqr(lastProgressPos) > 4) {
			lastProgressPos = pos;
			stallTicks = 0;
		} else if (++stallTicks > STALL_TICKS) {
			return enter(Phase.DISMOUNT); // wedged against something: get out and walk
		}
		return Status.RUNNING;
	}

	/** Solid land (not water/ice) right in front of the boat at water level. */
	private boolean shoreAhead(Bot bot, AbstractBoat riding) {
		Level level = bot.mc().level;
		float yaw = riding.getYRot() * Mth.DEG_TO_RAD;
		Vec3 ahead = riding.position().add(-Mth.sin(yaw) * 1.8, 0, Mth.cos(yaw) * 1.8);
		BlockPos at = BlockPos.containing(ahead);
		BlockState level0 = level.getBlockState(at), below = level.getBlockState(at.below());
		boolean blocked = !level0.getCollisionShape(level, at).isEmpty() && !isBoatSurface(level0);
		boolean dryLand = !isBoatSurface(below) && !below.getCollisionShape(level, at.below()).isEmpty() && level0.isAir();
		return blocked || dryLand;
	}

	private Status pickup(Bot bot, LocalPlayer p, Controls c) {
		if (phaseTicks > 160) return Status.DONE;
		if (boat != null && boat.isAlive()) {
			if (p.distanceTo(boat) <= p.entityInteractionRange()) {
				if (phaseTicks % 6 == 0) c.attack(boat);
			} else if (phaseTicks > 60) {
				return Status.DONE; // drifted away; leave it
			}
			return Status.RUNNING;
		}
		List<ItemEntity> drops = bot.scanner().items(8, BoatTravel::isBoat);
		if (drops.isEmpty()) return phaseTicks > 30 ? Status.DONE : Status.RUNNING;
		bot.nav().setGoal(new Goal.Near(drops.getFirst().blockPosition(), 0));
		return bot.nav().tick() == Navigator.Status.FAILED ? Status.DONE : Status.RUNNING;
	}

	/** Get out of the boat if riding (called when the trip is cancelled). */
	public void abort(Bot bot) {
		if (bot.player().isPassenger()) bot.controls().sneak();
	}

	private Status enter(Phase next) {
		phase = next;
		phaseTicks = 0;
		stallTicks = 0;
		return Status.RUNNING;
	}

	/** Water/ice surface block near the player, preferring the side facing the target. */
	private @Nullable BlockPos findLaunch(Bot bot) {
		LocalPlayer p = bot.player();
		Level level = bot.mc().level;
		BlockPos feet = Navigator.feet(p);
		BlockPos best = null;
		double bestScore = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-4, -2, -4), feet.offset(4, 0, 4))) {
			if (!isBoatSurface(level.getBlockState(pos)) || !level.getBlockState(pos.above()).isAir()) continue;
			double score = pos.distSqr(feet) + pos.distSqr(target) * 0.01;
			if (score < bestScore) {
				bestScore = score;
				best = pos.immutable();
			}
		}
		return best;
	}

	private static @Nullable AbstractBoat nearestBoat(Bot bot, double radius) {
		List<Entity> boats = bot.scanner().entities(radius, e -> e instanceof AbstractBoat b && b.getPassengers().isEmpty());
		Entity nearest = null;
		for (Entity e : boats) {
			if (nearest == null || e.distanceToSqr(bot.player()) < nearest.distanceToSqr(bot.player())) nearest = e;
		}
		return (AbstractBoat) nearest;
	}
}
