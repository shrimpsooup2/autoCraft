package com.autocraft.procedure;

import com.autocraft.Bases;
import com.autocraft.Bot;
import com.autocraft.Chat;
import com.autocraft.build.BaseWithdraw;
import com.autocraft.control.ContainerOpener;
import com.autocraft.control.InvUtil;
import com.autocraft.craft.RecipeBook;
import com.autocraft.goal.Requirement.Dim;
import com.autocraft.loot.LootBook;
import com.autocraft.loot.LootSites;
import com.autocraft.loot.LootSites.Holds;
import com.autocraft.loot.LootSites.Site;
import com.autocraft.path.Goal;
import com.autocraft.path.Navigator;
import com.autocraft.world.WorldScanner;
import com.autocraft.world.WorldKey;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Structure loot: items that come from where they generate — chests and barrels (dungeons, villages, temples,
 * ancient cities, fortresses, bastions...), chest minecarts in mineshafts, suspicious sand/gravel brushed in desert
 * wells, pyramids, ocean and trail ruins, and the chest buried at a treasure map's X. The cheapest structure for the
 * item is searched for (an explorer map pointing at one is followed first), its containers are opened one by one
 * and the item taken; looted containers and finished structures are remembered per world. Ancient cities are done
 * quietly ({@link SculkGuard}): sneaking, no mining or placing but wool, never stepping on sculk, wool between each
 * chest and the sensors that would hear it, and out of there on the third shriek or at the sight of the warden.
 */
final class ChestLootProcedure implements Procedure {
	/** Containers are looked for this many chunks around (the structure itself is looked for in view, see StructureSearch). */
	private static final int SCAN_CHUNKS = 4;
	private static final int SITE_RADIUS = 48;
	/** A structure is only written off as looted from this close to it (its far chests are within scan range). */
	private static final int LOOK_CLOSER = 16;
	/** The structure's containers are looked for this often (the view-wide scan runs every tick while looting). */
	private static final int CONTAINER_SCAN_TICKS = 10;
	/** A chunk with this many of a site's tell blocks is part of the structure (see {@link Run#extent}). */
	private static final int EXTENT_MIN_TELLS = 8;
	/** How often the structure's extent is worked out again (more of it loads as we walk in). */
	private static final int EXTENT_TICKS = 100;
	/** Containers this close to a mob combat ran from as unbeatable are left alone ({@link Bot#dreaded}). */
	private static final double GUARD_RADIUS = 16;
	/** A site whose remaining containers are all guarded is left this long, then tried again. */
	private static final long GUARDED_TICKS = 20 * 60 * 10;
	private static final int MAX_WOOL_TRIES = 6;
	private static final int BRUSH_TIMEOUT = 20 * 15;
	private static final int OPEN_TIMEOUT = 60;
	/** Quiet mode once sculk is this close (sensors hear 8 blocks), off again beyond {@link #QUIET_OFF}. */
	private static final int QUIET_ON = 12;
	private static final int QUIET_OFF = 20;
	/** The warning level drops by one every ten minutes. */
	private static final long CALM_TICKS = 20 * 60 * 10;
	private static final int CITY_WOOL = 24;
	private static final Gson GSON = new Gson();

	private record Source(Site site, double chance) {}

	/** Per world: containers already opened, structures already looted. */
	private static final class Memory {
		Set<Long> looted = new HashSet<>();
		/** Where a structure was written off: everything within {@link #SITE_RADIUS} of it. */
		Set<Long> sites = new HashSet<>();
		/** The chunks of structures written off ({@link WorldScanner#chunkKey}): all of a bastion, not one wing of it. */
		Set<Long> doneChunks = new HashSet<>();
	}

	private static @Nullable Map<String, Memory> worlds;

	@Override
	public boolean makes(Item item) {
		return !madeByRecipe(item) && !sources(item).isEmpty();
	}

	/**
	 * A recipe makes it from other things (a block of iron from ingots, scrap from debris): never planned from chests.
	 * Spread over the recipe's output a bastion search looked cheaper than a trip home for raw iron, so the smithing
	 * table's two ingots were to come from a block of iron in a bastion chest — a long shot for what a furnace makes
	 * for sure. Recipes that need the item itself (template duplication) don't count.
	 */
	private static boolean madeByRecipe(Item item) {
		RecipeBook book = RecipeBook.get();
		for (RecipeBook.Recipe r : book.crafting(item)) {
			if (r.key().values().stream().noneMatch(ing -> ing.options().contains(item))) return true;
		}
		return !book.smelting(item).isEmpty() || !book.cutting(item).isEmpty();
	}

	@Override
	public Map<Item, Integer> needs(Item item, int total) {
		List<Source> sources = sources(item);
		if (sources.isEmpty()) return Map.of();
		Site site = sources.getFirst().site();
		if (site.quiet()) return Map.of(wool(), CITY_WOOL);
		if (site.holds() == Holds.SUSPICIOUS_BLOCKS) return Map.of(Items.BRUSH, 1);
		if (site.holds() == Holds.BURIED_AT_MAP_X) return Map.of(Items.BURIED_TREASURE_MAP, 1);
		return Map.of();
	}

	@Override
	public double cost(Item item) {
		List<Source> sources = sources(item);
		return sources.isEmpty() ? 1e9 : cost(sources.getFirst());
	}

	@Override
	public Task start(Item item, int total) {
		return new Run(item, total, sources(item));
	}

	@Override
	public String label() {
		return "Structure loot (chests, chest minecarts, suspicious sand/gravel)";
	}

	@Override
	public @Nullable Dim dimension(Item item) {
		List<Source> sources = sources(item);
		return sources.isEmpty() ? null : sources.getFirst().site().dim();
	}

	@Override
	public @Nullable Site dangerousSite(Item item) {
		List<Source> sources = sources(item);
		return sources.isEmpty() || !LootSites.guarded(sources.getFirst().site()) ? null : sources.getFirst().site();
	}

	/** Structures whose loot can hold {@code item}, cheapest first. */
	private static List<Source> sources(Item item) {
		Map<Site, Double> best = new LinkedHashMap<>();
		for (LootBook.ChestDrop d : LootBook.get().chestSources(item)) {
			Site s = LootSites.forTable(d.table());
			if (s != null) best.merge(s, d.chance(), Math::max);
		}
		return best.entrySet().stream().map(e -> new Source(e.getKey(), e.getValue())).sorted(Comparator.comparingDouble(ChestLootProcedure::cost)).toList();
	}

	/** Effort to find one such structure, divided by the chance that one has the item. */
	private static double cost(Source s) {
		double perSite = 1 - Math.pow(1 - Math.min(1, s.chance()), s.site().containers());
		return s.site().effort() / Math.max(0.02, perSite);
	}

	private static Item wool() {
		return BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace("white_wool"));
	}

	/** What one container is: a block (chest, barrel, suspicious block) or a chest minecart. */
	private record Target(@Nullable BlockPos pos, @Nullable Entity cart) {
		BlockPos where() {
			return cart != null ? cart.blockPosition() : pos;
		}

		long key() {
			return cart != null ? cart.getUUID().getMostSignificantBits() ^ cart.getUUID().getLeastSignificantBits() : pos.asLong();
		}
	}

	private final class Run implements Task {
		private final Item item;
		private final int total;
		private final List<Source> sources;
		private int index;
		private @Nullable Dim wanted;
		/** Looking for the current source's structure (a new search per source). */
		private @Nullable StructureSearch search;
		private @Nullable Target target;
		private @Nullable ContainerOpener opener;
		private int openTicks;
		private int woolTries;
		private int brushTicks;
		private final BlockPos[] chase = new BlockPos[1];
		private final SculkGuard guard = new SculkGuard();
		private @Nullable BlockPos entry;
		private long calmUntil = -1;
		private int wait;
		/** The structure's chunks ({@link #extent}), worked out for the tell block {@link #extentFor} at {@link #extentAt}. */
		private Set<Long> extent = Set.of();
		private @Nullable BlockPos extentFor;
		private long extentAt;
		/** Sites left because every container still to open is guarded (their chunks), and until when. */
		private final Map<Long, Long> guardedChunks = new HashMap<>();
		/** The last {@link #nextContainer} left containers out for a guard near them. */
		private boolean guardedLeft;

		Run(Item item, int total, List<Source> sources) {
			this.item = item;
			this.total = total;
			this.sources = sources;
		}

		@Override
		public @Nullable Dim wantsDimension() {
			return wanted;
		}

		@Override
		public Status tick(Bot bot) {
			Status s = step(bot);
			if (s != Status.RUNNING) bot.setQuiet(false);
			return s;
		}

		private Status step(Bot bot) {
			LocalPlayer p = bot.player();
			if (InvUtil.count(p, x -> x.is(item)) >= total) return Status.DONE;
			if (index >= sources.size()) {
				Chat.warn("Structure loot: gave up on " + item.getDefaultInstance().getHoverName().getString() + ".");
				return Status.FAILED;
			}
			Site site = sources.get(index).site();
			if (site.dim() != bot.dimension()) {
				wanted = site.dim(); // the portal behavior takes us (the job holds this task meanwhile)
				return Status.RUNNING;
			}
			wanted = null;
			if (wait > 0) {
				wait--;
				return Status.RUNNING;
			}
			if (site.quiet()) {
				long now = bot.level().getGameTime();
				if (calmUntil > 0 && now >= calmUntil) {
					calmUntil = -1;
					guard.calmDown();
					Chat.info("The ancient city should have calmed down; going back in.");
				}
				if (calmUntil > now || guard.danger(bot)) return retreat(bot, now);
				updateQuiet(bot);
			}
			if (Actions.pickUp(bot, x -> x.is(item), 8)) return Status.RUNNING;
			if (target != null && opener == null && bot.dreaded(target.where(), GUARD_RADIUS)) {
				target = null; // something we ran from as too strong stands by it: another one, or another site
				bot.nav().stop();
			}
			if (target != null) return loot(bot, site);
			StructureSearch search = search(site);
			BlockPos mapped = search.mapTarget(bot);
			if (site.holds() == Holds.BURIED_AT_MAP_X) return treasure(bot, mapped);
			BlockPos tell = search.find(bot);
			if (tell == null) return explore(bot, search, mapped);
			target = nextContainer(bot, site, tell);
			if (target == null && guardedLeft) {
				// what's left of it is guarded by something we can't beat: another one, and back here later
				long until = bot.level().getGameTime() + GUARDED_TICKS;
				for (long c : extent(bot, site, tell)) guardedChunks.put(c, until);
				guardedChunks.put(WorldScanner.chunkKey(tell.getX() >> 4, tell.getZ() >> 4), until);
				search.forget(bot);
				Chat.info("The rest of this " + site.name() + " is guarded by something too strong for us; looking for another.");
				bot.setQuiet(false);
				entry = null;
				search.turn(bot);
				return Status.RUNNING;
			}
			if (target == null) {
				// seen from afar (a bastion's edge at the scan limit), its chests may be out of scan range yet: go and
				// look before writing it off — two bastions were "done" without a step taken toward them
				if (Navigator.feet(bot.player()).distSqr(tell) > (double) LOOK_CLOSER * LOOK_CLOSER) {
					bot.setStatus("heading to the " + site.name() + " at " + tell.toShortString());
					bot.nav().setGoal(new Goal.Near(tell, LOOK_CLOSER / 2));
					if (bot.nav().tick() != Navigator.Status.FAILED) return Status.RUNNING;
					bot.nav().stop();
				}
				memory().sites.add(tell.asLong());
				memory().doneChunks.addAll(extent(bot, site, tell));
				save();
				search.forget(bot);
				Chat.info("Done with this " + site.name() + "; looking for another.");
				bot.setQuiet(false);
				entry = null;
				search.turn(bot);
			}
			return Status.RUNNING;
		}

		// ---- finding the structure ---------------------------------------------------------------

		/** The search for this kind of site, passing over sites finished with or guarded for now. */
		private StructureSearch search(Site site) {
			if (search == null || search.site() != site) search = new StructureSearch(site, this::passedOver);
			return search;
		}

		/**
		 * The structure around a tell block: the chunks holding its tell blocks, flooded out from the tell's chunk
		 * across neighbours that hold some too. A bastion is wider than {@link #SITE_RADIUS}: chests and done-marks
		 * that only reached 48 blocks from one tell block left its far wings to be found as another bastion.
		 */
		private Set<Long> extent(Bot bot, Site site, BlockPos tell) {
			long now = bot.level().getGameTime();
			if (tell.equals(extentFor) && now - extentAt < EXTENT_TICKS) return extent;
			int chunks = Math.max(SCAN_CHUNKS, bot.mc().options.getEffectiveRenderDistance());
			Set<Long> holding = bot.scanner().chunksWith(site.tell(), chunks, EXTENT_MIN_TELLS);
			Set<Long> out = new HashSet<>();
			java.util.ArrayDeque<int[]> todo = new java.util.ArrayDeque<>();
			out.add(WorldScanner.chunkKey(tell.getX() >> 4, tell.getZ() >> 4));
			todo.add(new int[] {tell.getX() >> 4, tell.getZ() >> 4});
			while (!todo.isEmpty()) {
				int[] c = todo.poll();
				for (int dx = -1; dx <= 1; dx++) {
					for (int dz = -1; dz <= 1; dz++) {
						long k = WorldScanner.chunkKey(c[0] + dx, c[1] + dz);
						if (holding.contains(k) && out.add(k)) todo.add(new int[] {c[0] + dx, c[1] + dz});
					}
				}
			}
			extent = out;
			extentFor = tell.immutable();
			extentAt = now;
			return out;
		}

		/** In the structure around {@code tell}: within {@link #SITE_RADIUS} of it, or in (or next to) one of its chunks. */
		private boolean inSite(Set<Long> chunks, BlockPos tell, BlockPos pos) {
			if (pos.distSqr(tell) <= (double) SITE_RADIUS * SITE_RADIUS) return true;
			int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					if (chunks.contains(WorldScanner.chunkKey(cx + dx, cz + dz))) return true;
				}
			}
			return false;
		}

		/** Finished with, or guarded for now ({@link #guardedChunks}): the search passes it over. */
		private boolean passedOver(BlockPos pos) {
			if (finished(pos.getX(), pos.getY(), pos.getZ())) return true;
			Long until = guardedChunks.get(WorldScanner.chunkKey(pos.getX() >> 4, pos.getZ() >> 4));
			return until != null && until > Bot.get().level().getGameTime();
		}

		private boolean finished(int x, int y, int z) {
			Memory m = memory();
			if (m.doneChunks.contains(WorldScanner.chunkKey(x >> 4, z >> 4))) return true;
			for (long s : m.sites) {
				BlockPos at = BlockPos.of(s);
				long dx = at.getX() - x, dy = at.getY() - y, dz = at.getZ() - z;
				if (dx * dx + dy * dy + dz * dz <= (long) SITE_RADIUS * SITE_RADIUS) return true;
			}
			return false;
		}

		/** Explore for the structure; its legs spent, try the next kind of structure that has the item. */
		private Status explore(Bot bot, StructureSearch search, @Nullable BlockPos mapped) {
			if (search.explore(bot, mapped)) return Status.RUNNING;
			index++;
			this.search = null;
			if (index < sources.size()) Chat.info("No " + search.site().name() + " found; trying a " + sources.get(index).site().name() + " instead.");
			return Status.RUNNING;
		}

		// ---- containers ----------------------------------------------------------------------------

		private @Nullable Target nextContainer(Bot bot, Site site, BlockPos tell) {
			BlockPos feet = Navigator.feet(bot.player());
			Set<Long> looted = memory().looted;
			if (site.holds() == Holds.MINECARTS) {
				return bot.scanner().entities(SITE_RADIUS + 16, e -> e instanceof MinecartChest && e.isAlive()).stream()
						.map(e -> new Target(null, e)).filter(t -> t.where().distSqr(tell) <= (double) SITE_RADIUS * SITE_RADIUS && !looted.contains(t.key()))
						.min(Comparator.comparingDouble(t -> t.where().distSqr(feet))).orElse(null);
			}
			boolean brush = site.holds() == Holds.SUSPICIOUS_BLOCKS;
			Set<Long> chunks = extent(bot, site, tell);
			int radius = Math.max(SCAN_CHUNKS, bot.mc().options.getEffectiveRenderDistance()); // the far wings' chests too
			List<BlockPos> found = bot.scanner().findBlocksCached("containers " + site.name(), s -> brush ? suspicious(s) : container(s), radius, 256,
					CONTAINER_SCAN_TICKS);
			List<BlockPos> open = found.stream().filter(pos -> inSite(chunks, tell, pos) && !looted.contains(pos.asLong()) && !atBase(bot, pos)).toList();
			List<BlockPos> unguarded = open.stream().filter(pos -> !bot.dreaded(pos, GUARD_RADIUS)).toList();
			guardedLeft = unguarded.size() < open.size() && unguarded.isEmpty();
			return unguarded.stream().min(Comparator.comparingDouble(pos -> pos.distSqr(feet))).map(pos -> new Target(pos, null)).orElse(null);
		}

		private static boolean container(BlockState s) {
			return s.getBlock() instanceof ChestBlock || s.getBlock() instanceof BarrelBlock;
		}

		private static boolean suspicious(BlockState s) {
			return s.is(Blocks.SUSPICIOUS_SAND) || s.is(Blocks.SUSPICIOUS_GRAVEL);
		}

		/** Never someone's (our) storage: chests at a registered base are left alone. */
		private static boolean atBase(Bot bot, BlockPos pos) {
			for (Bases.Base b : Bases.inDimension(bot.dimension())) {
				if (b.pos().distSqr(pos) <= BaseWithdraw.BASE_RADIUS * BaseWithdraw.BASE_RADIUS) return true;
			}
			return false;
		}

		private Status loot(Bot bot, Site site) {
			LocalPlayer p = bot.player();
			Target t = target;
			ClientLevel level = bot.level();
			if (t.cart() != null ? !t.cart().isAlive()
					: site.holds() == Holds.SUSPICIOUS_BLOCKS ? !suspicious(level.getBlockState(t.pos())) : !container(level.getBlockState(t.pos()))) {
				return done(bot, t); // brushed (it turned into plain sand), broken, or gone
			}
			Actions.Result r = t.cart() != null ? Actions.reach(bot, t.cart(), chase) : Actions.reach(bot, t.pos());
			if (r == Actions.Result.WORKING) {
				bot.setStatus("going to a " + (site.holds() == Holds.SUSPICIOUS_BLOCKS ? "suspicious block" : "chest") + " in the " + site.name());
				return Status.RUNNING;
			}
			if (r == Actions.Result.FAILED) return done(bot, t);
			if (site.holds() == Holds.SUSPICIOUS_BLOCKS) return brush(bot, t.pos());
			if (bot.quiet() && t.pos() != null && !silence(bot, t.pos())) return Status.RUNNING;
			if (!(p.containerMenu instanceof ChestMenu menu)) {
				if (++openTicks > OPEN_TIMEOUT) return done(bot, t);
				if (t.cart() != null) {
					if (openTicks % 10 == 1) {
						Vec3 c = t.cart().getBoundingBox().getCenter();
						bot.controls().lookAt(c);
						bot.mc().gameMode.interact(p, t.cart(), new EntityHitResult(t.cart(), c), InteractionHand.MAIN_HAND);
					}
				} else {
					if (opener == null) opener = new ContainerOpener(t.pos(), ChestMenu.class);
					if (opener.tick(bot) == ContainerOpener.Status.FAILED) return done(bot, t);
				}
				bot.setStatus("opening a chest in the " + site.name());
				return Status.RUNNING;
			}
			int size = menu.getRowCount() * 9;
			for (int slot = 0; slot < size; slot++) {
				ItemStack s = menu.getSlot(slot).getItem();
				if (!s.isEmpty() && s.is(item)) InvUtil.quickMove(p, slot);
			}
			p.closeContainer();
			return done(bot, t);
		}

		/** Put wool between the chest and each sculk sensor that would hear it open; false while that's going on. */
		private boolean silence(Bot bot, BlockPos chest) {
			List<BlockPos> ears = SculkGuard.listeners(bot, chest);
			if (ears.isEmpty()) return true;
			LocalPlayer p = bot.player();
			if (woolTries++ < MAX_WOOL_TRIES && InvUtil.count(p, s -> s.is(ItemTags.WOOL)) > 0) {
				for (BlockPos spot : SculkGuard.woolSpots(bot.level(), chest, ears.getFirst())) {
					if (!p.isWithinBlockInteractionRange(spot, 0) || new net.minecraft.world.phys.AABB(spot).intersects(p.getBoundingBox())) continue;
					if (bot.controls().place(spot, s -> s.is(ItemTags.WOOL))) {
						bot.setStatus("putting wool between the chest and a sculk sensor");
						wait = 4;
						return false;
					}
				}
				return false; // nothing placeable from here this tick: try again (counts against the tries)
			}
			Chat.info("Leaving a chest at " + chest.toShortString() + ": a sculk sensor would hear it open.");
			done(bot, target);
			return false;
		}

		/** Hold the brush on a suspicious block until it gives up its item. */
		private Status brush(Bot bot, BlockPos pos) {
			LocalPlayer p = bot.player();
			if (!InvUtil.select(p, s -> s.is(Items.BRUSH))) return Status.FAILED; // the planner brings one first
			if (++brushTicks > BRUSH_TIMEOUT) return done(bot, target);
			Direction side = bot.controls().faceToward(pos);
			Vec3 hit = Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(side.getUnitVec3i()).scale(0.5));
			bot.controls().lookAt(hit);
			if (!p.isUsingItem() && bot.controls().aimed()) {
				bot.mc().gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(hit, side, pos, false));
			}
			bot.controls().holdUse();
			bot.setStatus("brushing a suspicious block at " + pos.toShortString());
			return Status.RUNNING;
		}

		private Status done(Bot bot, @Nullable Target t) {
			if (t != null) {
				Set<Long> looted = memory().looted;
				looted.add(t.key());
				BlockState s = t.pos() == null ? null : bot.level().getBlockState(t.pos());
				if (s != null && s.getBlock() instanceof ChestBlock && s.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
					looted.add(t.pos().relative(ChestBlock.getConnectedDirection(s)).asLong()); // the other half too
				}
				save();
			}
			if (!InvUtil.inventoryMenuActive(bot.player())) bot.player().closeContainer();
			target = null;
			opener = null;
			openTicks = 0;
			woolTries = 0;
			brushTicks = 0;
			chase[0] = null;
			return Status.RUNNING;
		}

		// ---- buried treasure -----------------------------------------------------------------------

		private Status treasure(Bot bot, @Nullable BlockPos mapped) {
			if (mapped == null) {
				Chat.warn("Buried treasure: no treasure map with an X to follow.");
				return Status.FAILED;
			}
			LocalPlayer p = bot.player();
			BlockPos feet = Navigator.feet(p);
			BlockPos chest = bot.scanner().findBlocks(s -> s.getBlock() instanceof ChestBlock, 1, 16).stream()
					.filter(c -> Math.abs(c.getX() - mapped.getX()) <= 3 && Math.abs(c.getZ() - mapped.getZ()) <= 3 && !memory().looted.contains(c.asLong()))
					.findFirst().orElse(null);
			if (chest != null) {
				target = new Target(chest, null);
				return Status.RUNNING;
			}
			if (Math.abs(feet.getX() - mapped.getX()) > 1 || Math.abs(feet.getZ() - mapped.getZ()) > 1) {
				bot.setStatus("following the treasure map to " + mapped.getX() + ", " + mapped.getZ());
				if (bot.startFlight(mapped)) return Status.RUNNING;
				bot.nav().setGoal(new Goal.XZ(mapped.getX(), mapped.getZ()));
				if (bot.nav().tick() == Navigator.Status.FAILED) bot.nav().stop();
				return Status.RUNNING;
			}
			bot.nav().stop();
			if (feet.getY() < bot.level().getSeaLevel() - 25) {
				memory().sites.add(mapped.asLong());
				save();
				Chat.warn("No treasure under the X at " + mapped.getX() + ", " + mapped.getZ() + ".");
				return Status.FAILED;
			}
			bot.setStatus("digging for buried treasure");
			bot.controls().mine(feet.below());
			return Status.RUNNING;
		}

		// ---- ancient city --------------------------------------------------------------------------

		/** Quiet once sculk is near enough to hear us; normal again well away from it (digging there is fine). */
		private void updateQuiet(Bot bot) {
			BlockPos feet = Navigator.feet(bot.player());
			double nearest = bot.scanner().findBlocksCached("sculk-ears", s -> s.is(Blocks.SCULK_SENSOR) || s.is(Blocks.CALIBRATED_SCULK_SENSOR)
					|| s.is(Blocks.SCULK_SHRIEKER), 2, 64, 20).stream().mapToDouble(s -> Math.sqrt(s.distSqr(feet))).min().orElse(Double.MAX_VALUE);
			if (!bot.quiet() && nearest <= QUIET_ON) {
				entry = feet;
				bot.setQuiet(true);
				Chat.info("In the ancient city: sneaking, no mining, no stepping on sculk.");
			} else if (bot.quiet() && nearest > QUIET_OFF) {
				bot.setQuiet(false);
			}
		}

		/** Too many shrieks or the warden: back out to where we came in and wait for the city to calm down. */
		private Status retreat(Bot bot, long now) {
			if (calmUntil <= now) {
				boolean warden = SculkGuard.wardenNear(bot);
				calmUntil = now + (warden ? CALM_TICKS * 2 : CALM_TICKS);
				Chat.warn(warden ? "The warden is here: leaving the ancient city; back in about 20 minutes."
						: guard.shrieks() + " shrieks: leaving before the warden comes; back in about 10 minutes.");
				done(bot, null);
			}
			bot.setQuiet(!SculkGuard.wardenNear(bot)); // running beats sneaking once it's after us
			BlockPos feet = Navigator.feet(bot.player());
			Direction heading = search == null ? null : search.heading();
			BlockPos away = entry != null ? entry : feet.relative(heading == null ? Direction.NORTH : heading.getOpposite(), 48);
			if (feet.distSqr(away) > 9) {
				bot.setStatus("leaving the ancient city quietly");
				bot.nav().setGoal(new Goal.Near(away, 2));
				if (bot.nav().tick() == Navigator.Status.FAILED) bot.nav().stop();
				return Status.RUNNING;
			}
			bot.nav().stop();
			bot.setStatus("waiting for the ancient city to calm down (" + Math.max(1, (calmUntil - now) / 1200) + " min)");
			return Status.RUNNING;
		}
	}

	// ---- memory ---------------------------------------------------------------------------------

	private static Memory memory() {
		if (worlds == null) worlds = load();
		return worlds.computeIfAbsent(WorldKey.current(), k -> new Memory());
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("autocraft").resolve("structure_loot.json");
	}

	private static Map<String, Memory> load() {
		try {
			if (Files.exists(file())) {
				Map<String, Memory> loaded = GSON.fromJson(Files.readString(file()), new TypeToken<Map<String, Memory>>() {}.getType());
				if (loaded != null) return new HashMap<>(loaded);
			}
		} catch (IOException | JsonParseException e) {
			Chat.LOG.warn("Could not read structure_loot.json", e);
		}
		return new HashMap<>();
	}

	private static void save() {
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(worlds));
		} catch (IOException e) {
			Chat.LOG.warn("Could not save structure_loot.json", e);
		}
	}
}
