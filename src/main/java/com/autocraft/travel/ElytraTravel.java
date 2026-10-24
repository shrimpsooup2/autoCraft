package com.autocraft.travel;

import com.autocraft.Bot;
import com.autocraft.Chat;
import com.autocraft.control.Controls;
import com.autocraft.control.InvUtil;
import com.autocraft.goal.Requirement;
import com.autocraft.path.Navigator;
import java.util.function.BooleanSupplier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

/**
 * Long-distance flight: swap the chestplate for the elytra, take off with a jump + glide + rocket, climb
 * to a safe altitude above the terrain ahead, cruise with rockets toward the target, glide down, land and
 * put the chestplate back. Only plain rockets are used (rockets with stars hurt the user). Durability and
 * rocket count are checked before leaving and during the flight; running low means landing early.
 */
public final class ElytraTravel {
	public enum Status { RUNNING, DONE, FAILED }

	private enum Phase { EQUIP, TAKEOFF, CLIMB, CRUISE, LAND, RESTORE }

	/** Shorter trips are faster on foot. */
	public static final double MIN_DISTANCE = 150;
	private static final int CHEST_MENU_SLOT = 6;
	private static final double BLOCKS_PER_SECOND = 25;
	private static final int DURABILITY_RESERVE = 20;
	private static final int ROCKET_EVERY = 30;
	private static final int CLEARANCE = 25;
	private static final int MIN_CRUISE_Y = 120;
	private static final double LAND_DISTANCE = 40;

	private final BlockPos target;
	/** Exploration flights: true once what we're looking for is in sight (land there instead of flying on). */
	private final @Nullable BooleanSupplier lookout;
	private Phase phase = Phase.EQUIP;
	private int phaseTicks;
	private int totalTicks;
	private int timeoutTicks = -1;
	private int sinceRocket = ROCKET_EVERY;
	private int restoreSlot = -1;
	private boolean jumpedLastTick;
	/** Takeoff failed: report FAILED after restoring the chestplate so callers back off. */
	private boolean failed;

	public ElytraTravel(BlockPos target, @Nullable BooleanSupplier lookout) {
		this.target = target.immutable();
		this.lookout = lookout;
	}

	// ---- pre-flight checks ----------------------------------------------------------------------

	/** Elytra + enough plain rockets and durability for this distance, open sky, Overworld. */
	public static boolean worthIt(Bot bot, BlockPos target) {
		LocalPlayer p = bot.player();
		if (bot.dimension() != Requirement.Dim.OVERWORLD || !p.onGround()) return false;
		double distance = horizontalDistance(p, target);
		if (distance < MIN_DISTANCE) return false;
		int elytra = InvUtil.find(p, ElytraTravel::isElytra);
		ItemStack worn = p.getItemBySlot(EquipmentSlot.CHEST);
		ItemStack wings = isElytra(worn) ? worn : elytra >= 0 ? p.getInventory().getItem(elytra) : ItemStack.EMPTY;
		if (wings.isEmpty()) return false;
		int seconds = (int) (distance / BLOCKS_PER_SECOND) + 30;
		if (wings.getMaxDamage() - wings.getDamageValue() < seconds + DURABILITY_RESERVE) return false;
		if (InvUtil.count(p, ElytraTravel::isPlainRocket) < rocketsFor(distance)) return false;
		BlockPos feet = Navigator.feet(p);
		return bot.mc().level.getHeight(Heightmap.Types.MOTION_BLOCKING, feet.getX(), feet.getZ()) <= feet.getY();
	}

	/** Carrying what a flight needs at all (for deciding to plan long legs). */
	public static boolean canFly(Player p) {
		return (InvUtil.find(p, ElytraTravel::isElytra) >= 0 || isElytra(p.getItemBySlot(EquipmentSlot.CHEST)))
				&& InvUtil.count(p, ElytraTravel::isPlainRocket) >= rocketsFor(MIN_DISTANCE);
	}

	public static boolean isElytra(ItemStack s) {
		return s.has(DataComponents.GLIDER);
	}

	/** Rockets without stars: boosting with a star-loaded rocket damages the player. */
	public static boolean isPlainRocket(ItemStack s) {
		Fireworks f = s.get(DataComponents.FIREWORKS);
		return f != null && f.explosions().isEmpty();
	}

	private static int rocketsFor(double distance) {
		return 4 + (int) (distance / 60);
	}

	private static double horizontalDistance(Player p, BlockPos target) {
		double dx = target.getX() + 0.5 - p.getX(), dz = target.getZ() + 0.5 - p.getZ();
		return Math.sqrt(dx * dx + dz * dz);
	}

	// ---- flight ---------------------------------------------------------------------------------

	public Status tick(Bot bot) {
		LocalPlayer p = bot.player();
		Controls c = bot.controls();
		phaseTicks++;
		sinceRocket++;
		bot.setStatus("elytra " + phase.name().toLowerCase() + " to " + target.toShortString());
		if (timeoutTicks < 0) timeoutTicks = (int) (horizontalDistance(p, target) / BLOCKS_PER_SECOND * 20) + 20 * 90;
		if (++totalTicks > timeoutTicks && phase != Phase.LAND && phase != Phase.RESTORE) enter(Phase.LAND);
		switch (phase) {
			case EQUIP -> {
				if (!InvUtil.inventoryMenuActive(p)) p.closeContainer();
				if (isElytra(p.getItemBySlot(EquipmentSlot.CHEST))) return enter(Phase.TAKEOFF);
				if (!equipElytra(p) || phaseTicks > 20) return Status.FAILED;
			}
			case TAKEOFF -> {
				if (p.isFallFlying()) {
					fireRocket(bot, p);
					return enter(Phase.CLIMB);
				}
				face(c, p, -20);
				if (p.onGround()) {
					if (phaseTicks > 40) { // never got airborne
						failed = true;
						return enter(Phase.RESTORE);
					}
					c.jump();
					jumpedLastTick = true;
				} else if (jumpedLastTick) {
					jumpedLastTick = false; // release so the next press is a fresh one
				} else {
					c.jump(); // a fresh jump press in the air opens the wings
					jumpedLastTick = true;
				}
			}
			case CLIMB -> {
				if (!p.isFallFlying()) return grounded(p);
				face(c, p, -35);
				if (sinceRocket >= ROCKET_EVERY) fireRocket(bot, p);
				if (p.getY() >= cruiseAltitude(bot, p)) enter(Phase.CRUISE);
			}
			case CRUISE -> {
				if (!p.isFallFlying()) return grounded(p);
				if (horizontalDistance(p, target) < LAND_DISTANCE || lowOnSupplies(p)) {
					if (lowOnSupplies(p)) Chat.warn("Elytra or rockets running low, landing early.");
					return enter(Phase.LAND);
				}
				if (lookout != null && totalTicks % 20 == 0 && lookout.getAsBoolean()) {
					Chat.info("Spotted what we're looking for below, landing.");
					return enter(Phase.LAND);
				}
				double want = cruiseAltitude(bot, p);
				double speed = p.getDeltaMovement().horizontalDistance();
				if (p.getY() < want - 5) {
					face(c, p, -25);
					if (sinceRocket >= ROCKET_EVERY) fireRocket(bot, p);
				} else if (p.getY() > want + 15) {
					face(c, p, 10);
				} else {
					face(c, p, -3);
					if (speed < 0.8 && sinceRocket >= ROCKET_EVERY) fireRocket(bot, p);
				}
			}
			case LAND -> {
				if (p.onGround() || p.isInWater()) return enter(Phase.RESTORE);
				if (!p.isFallFlying()) return grounded(p); // wings closed early: plain fall, the fall reflex takes over
				double height = p.getY() - groundBelow(bot.mc().level, p);
				face(c, p, height > 12 ? 25 : height > 4 ? 10 : -15); // dive, then flare to bleed speed
			}
			case RESTORE -> {
				if (!p.onGround() && !p.isInWater()) return Status.RUNNING;
				restoreChestplate(p);
				return failed ? Status.FAILED : Status.DONE;
			}
		}
		return Status.RUNNING;
	}

	private Status enter(Phase next) {
		phase = next;
		phaseTicks = 0;
		return Status.RUNNING;
	}

	/** Wings closed mid-air (broken or blocked): stop flying; the fall reflex takes it from here. */
	private Status grounded(LocalPlayer p) {
		if (p.onGround() || p.isInWater()) return enter(Phase.RESTORE);
		Chat.warn("Elytra flight interrupted in the air.");
		return Status.FAILED; // chestplate gets re-equipped by the gear behavior once on the ground
	}

	private void face(Controls c, LocalPlayer p, float pitch) {
		double dx = target.getX() + 0.5 - p.getX(), dz = target.getZ() + 0.5 - p.getZ();
		c.face((float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F, pitch);
	}

	private void fireRocket(Bot bot, LocalPlayer p) {
		if (!InvUtil.select(p, ElytraTravel::isPlainRocket)) return;
		bot.controls().useItem(InteractionHand.MAIN_HAND);
		sinceRocket = 0;
	}

	private static boolean lowOnSupplies(LocalPlayer p) {
		ItemStack wings = p.getItemBySlot(EquipmentSlot.CHEST);
		return wings.getMaxDamage() - wings.getDamageValue() < DURABILITY_RESERVE || InvUtil.count(p, ElytraTravel::isPlainRocket) == 0;
	}

	/** Fly at least {@link #CLEARANCE} above the highest terrain sampled ahead (loaded chunks only). */
	private double cruiseAltitude(Bot bot, LocalPlayer p) {
		ClientLevel level = bot.mc().level;
		double dx = target.getX() + 0.5 - p.getX(), dz = target.getZ() + 0.5 - p.getZ();
		double len = Math.max(1, Math.sqrt(dx * dx + dz * dz));
		int highest = level.getMinY();
		for (int d = 0; d <= 96; d += 16) {
			int x = Mth.floor(p.getX() + dx / len * d), z = Mth.floor(p.getZ() + dz / len * d);
			if (level.hasChunk(x >> 4, z >> 4)) highest = Math.max(highest, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z));
		}
		return Math.max(MIN_CRUISE_Y, highest + CLEARANCE);
	}

	private static double groundBelow(ClientLevel level, LocalPlayer p) {
		return level.getHeight(Heightmap.Types.MOTION_BLOCKING, p.getBlockX(), p.getBlockZ());
	}

	/** Chestplate (if any) goes where the elytra was; remembers that slot for the way back. */
	private boolean equipElytra(LocalPlayer p) {
		int wings = InvUtil.find(p, ElytraTravel::isElytra);
		if (wings < 0) return false;
		int from = InvUtil.menuSlot(p.inventoryMenu, p, wings);
		if (p.getItemBySlot(EquipmentSlot.CHEST).isEmpty()) {
			InvUtil.quickMove(p, from);
		} else {
			InvUtil.click(p, from, 0, ContainerInput.PICKUP);
			InvUtil.click(p, CHEST_MENU_SLOT, 0, ContainerInput.PICKUP);
			InvUtil.click(p, from, 0, ContainerInput.PICKUP);
			restoreSlot = wings;
		}
		return true;
	}

	private void restoreChestplate(LocalPlayer p) {
		if (restoreSlot < 0 || !InvUtil.inventoryMenuActive(p)) return;
		int from = InvUtil.menuSlot(p.inventoryMenu, p, restoreSlot);
		InvUtil.click(p, from, 0, ContainerInput.PICKUP);
		InvUtil.click(p, CHEST_MENU_SLOT, 0, ContainerInput.PICKUP);
		InvUtil.click(p, from, 0, ContainerInput.PICKUP);
		restoreSlot = -1;
	}
}
