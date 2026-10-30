package com.autocraft.procedure;

import com.autocraft.Bot;
import com.autocraft.loot.LootSites.Site;
import com.autocraft.path.Goal;
import com.autocraft.path.Navigator;
import com.autocraft.world.BiomeFinder;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.component.MapDecorations;
import org.jspecify.annotations.Nullable;

/**
 * Looking for a structure the way a player does: its tell blocks anywhere in loaded chunks (structures are seen from
 * afar), its tell mobs, the marker of an explorer map carried; else exploring — surface legs toward its biomes, or
 * tunnelling at its depth — for at most {@link #MAX_LEGS} legs. Structure loot ({@link ChestLootProcedure}) and go-to
 * tasks search with it.
 */
public final class StructureSearch {
	/** Exploration legs before a search gives up. */
	public static final int MAX_LEGS = 60;
	private static final int SCAN_CHUNKS = 4;
	private static final int TUNNEL_LEG = 32;
	/** The view-wide look for a site's tell blocks, refreshed this often (it runs every tick while exploring). */
	private static final int SITE_SCAN_TICKS = 10;
	private static final int MOB_RANGE = 96;

	private final Site site;
	/** Structures to pass over (already looted), by any of their tell blocks. */
	private final Predicate<BlockPos> done;
	private @Nullable Direction heading;
	private @Nullable Goal leg;
	private int legs;
	private @Nullable Explorer explorer;

	public StructureSearch(Site site, Predicate<BlockPos> done) {
		this.site = site;
		this.done = done;
	}

	public Site site() {
		return site;
	}

	/** The way the last legs went (tunnelling), or null before the first one. */
	public @Nullable Direction heading() {
		return heading;
	}

	/**
	 * The nearest tell block of one not done yet, anywhere loaded, else a tell mob in sight; null when none. A 4-chunk
	 * look tunnelled straight past a bastion 100 blocks off, toward nothing. Done ones are left out in the scan itself:
	 * dropped from the 64 nearest afterwards, the one tell block of a bastion's unfinished wing was among them from one
	 * step and not the next, and the bot swung between heading there and exploring away every second.
	 */
	public @Nullable BlockPos find(Bot bot) {
		BlockPos feet = Navigator.feet(bot.player());
		int chunks = Math.max(SCAN_CHUNKS, bot.mc().options.getEffectiveRenderDistance());
		List<BlockPos> tells = bot.scanner().findBlocksCached(scanKey(), site.tell(), (x, y, z) -> !done.test(new BlockPos(x, y, z)), chunks, 64,
				SITE_SCAN_TICKS);
		BlockPos best = tells.isEmpty() ? null : tells.getFirst(); // nearest first
		if (best == null && !site.tellMobs().isEmpty()) {
			double bestDist = Double.MAX_VALUE;
			for (Entity e : bot.scanner().entities(MOB_RANGE, e -> site.tellMobs().contains(e.getType()))) {
				if (!done.test(e.blockPosition()) && e.blockPosition().distSqr(feet) < bestDist) {
					bestDist = e.blockPosition().distSqr(feet);
					best = e.blockPosition();
				}
			}
		}
		return best;
	}

	/** A site was just finished with: look again (the cached scan still had it — its "done" message came 11 times). */
	public void forget(Bot bot) {
		bot.scanner().invalidate(scanKey());
	}

	private String scanKey() {
		return "site " + site.name();
	}

	/** Where an explorer map in the inventory says such a structure is (not one done), or null. */
	public @Nullable BlockPos mapTarget(Bot bot) {
		if (site.maps().isEmpty()) return null;
		LocalPlayer p = bot.player();
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
			MapDecorations marks = p.getInventory().getItem(i).get(DataComponents.MAP_DECORATIONS);
			if (marks == null) continue;
			for (MapDecorations.Entry e : marks.decorations().values()) {
				if (site.maps().stream().noneMatch(h -> h.value() == e.type().value())) continue;
				BlockPos at = BlockPos.containing(e.x(), p.getY(), e.z());
				if (!done.test(at)) return at;
			}
		}
		return null;
	}

	/**
	 * One tick of looking: toward {@code mapped} (an explorer map's marker) when given, else surface legs toward the
	 * site's biomes or a tunnel at its depth. False once {@link #MAX_LEGS} legs found nothing (nothing done this tick).
	 */
	public boolean explore(Bot bot, @Nullable BlockPos mapped) {
		if (legs > MAX_LEGS) return false;
		BlockPos feet = Navigator.feet(bot.player());
		if (mapped != null && leg == null) {
			bot.setStatus("following the map to a " + site.name());
			if (site.y() == null && bot.startFlight(mapped)) return true;
			leg = site.y() == null ? new Goal.XZ(mapped.getX(), mapped.getZ()) : new Goal.XYZ(mapped.getX(), site.y(), mapped.getZ());
		}
		if (mapped == null && site.y() == null) {
			if (explorer == null) explorer = new Explorer(site.biomes());
			if (explorer.exhausted(MAX_LEGS + 1)) return false;
			explorer.tick(bot, "a " + site.name());
			legs = explorer.legs();
			return true;
		}
		if (heading == null) heading = Direction.fromYRot(bot.player().getYRot());
		if (leg == null) {
			int y = site.y();
			if (Math.abs(feet.getY() - y) > 3) {
				leg = new Goal.YLevel(y);
			} else {
				ClientLevel level = bot.level();
				BlockPos biome = site.biomes().isEmpty() || BiomeFinder.in(level, feet, site.biomes()) ? null
						: BiomeFinder.nearest(level, feet, site.biomes(), 8, false, c -> false);
				leg = biome != null ? new Goal.XYZ(biome.getX(), y, biome.getZ())
						: new Goal.XYZ(feet.getX() + heading.getStepX() * TUNNEL_LEG, y, feet.getZ() + heading.getStepZ() * TUNNEL_LEG);
			}
		}
		bot.setStatus("searching for a " + site.name());
		bot.nav().setGoal(leg);
		Navigator.Status s = bot.nav().tick();
		if (s == Navigator.Status.ARRIVED || s == Navigator.Status.FAILED) {
			if (s == Navigator.Status.FAILED) turn(bot);
			leg = null;
			legs++;
			bot.nav().stop();
		}
		return true;
	}

	/** Head another way (a leg that couldn't be walked, or a structure finished with). */
	public void turn(Bot bot) {
		heading = heading == null ? Direction.fromYRot(bot.player().getYRot()) : heading.getClockWise();
		leg = null;
	}
}
