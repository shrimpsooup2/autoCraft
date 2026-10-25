package com.autocraft.procedure;

import com.autocraft.Bases;
import com.autocraft.Bot;
import com.autocraft.Chat;
import com.autocraft.build.BaseWithdraw;
import com.autocraft.control.ContainerOpener;
import com.autocraft.control.InvUtil;
import com.autocraft.goal.Requirement.Dim;
import com.autocraft.loot.LootBook;
import com.autocraft.loot.LootSites;
import com.autocraft.loot.LootSites.Holds;
import com.autocraft.loot.LootSites.Site;
import com.autocraft.path.Goal;
import com.autocraft.path.Navigator;
import com.autocraft.world.BiomeFinder;
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
import net.minecraft.core.component.DataComponents;
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
import net.minecraft.world.item.component.MapDecorations;
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
	private static final int SCAN_CHUNKS = 4;
	private static final int SITE_RADIUS = 48;
	private static final int TUNNEL_LEG = 32;
	private static final int MAX_LEGS = 60;
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
		Set<Long> sites = new HashSet<>();
	}

	private static @Nullable Map<String, Memory> worlds;

	@Override
	public boolean makes(Item item) {
		return !sources(item).isEmpty();
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
		private @Nullable Direction heading;
		private @Nullable Goal leg;
		private int legs;
		private @Nullable Explorer explorer;
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
			if (target != null) return loot(bot, site);
			BlockPos mapped = mapTarget(bot, site);
			if (site.holds() == Holds.BURIED_AT_MAP_X) return treasure(bot, mapped);
			BlockPos tell = findSite(bot, site);
			if (tell == null) return explore(bot, site, mapped);
			target = nextContainer(bot, site, tell);
			if (target == null) {
				memory().sites.add(tell.asLong());
				save();
				Chat.info("Done with this " + site.name() + "; looking for another.");
				bot.setQuiet(false);
				entry = null;
				turn(bot);
			}
			return Status.RUNNING;
		}

		// ---- finding the structure ---------------------------------------------------------------

		private @Nullable BlockPos findSite(Bot bot, Site site) {
			BlockPos feet = Navigator.feet(bot.player());
			BlockPos best = null;
			double bestDist = Double.MAX_VALUE;
			for (BlockPos t : bot.scanner().findBlocks(site.tell(), SCAN_CHUNKS, 64)) {
				if (finished(t)) continue;
				double d = t.distSqr(feet);
				if (d < bestDist) {
					bestDist = d;
					best = t;
				}
			}
			if (best == null && !site.tellMobs().isEmpty()) {
				for (Entity e : bot.scanner().entities(96, e -> site.tellMobs().contains(e.getType()))) {
					if (!finished(e.blockPosition()) && e.blockPosition().distSqr(feet) < bestDist) {
						bestDist = e.blockPosition().distSqr(feet);
						best = e.blockPosition();
					}
				}
			}
			return best;
		}

		private boolean finished(BlockPos pos) {
			for (long s : memory().sites) {
				if (BlockPos.of(s).distSqr(pos) <= (double) SITE_RADIUS * SITE_RADIUS) return true;
			}
			return false;
		}

		/** Where an explorer map in the inventory says such a structure is (not one we've finished), or null. */
		private @Nullable BlockPos mapTarget(Bot bot, Site site) {
			if (site.maps().isEmpty()) return null;
			LocalPlayer p = bot.player();
			for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
				MapDecorations marks = p.getInventory().getItem(i).get(DataComponents.MAP_DECORATIONS);
				if (marks == null) continue;
				for (MapDecorations.Entry e : marks.decorations().values()) {
					if (site.maps().stream().noneMatch(h -> h.value() == e.type().value())) continue;
					BlockPos at = BlockPos.containing(e.x(), p.getY(), e.z());
					if (!finished(at)) return at;
				}
			}
			return null;
		}

		private Status explore(Bot bot, Site site, @Nullable BlockPos mapped) {
			if (legs > MAX_LEGS) {
				index++;
				legs = 0;
				leg = null;
				explorer = null;
				if (index < sources.size()) Chat.info("No " + site.name() + " found; trying a " + sources.get(index).site().name() + " instead.");
				return Status.RUNNING;
			}
			BlockPos feet = Navigator.feet(bot.player());
			if (mapped != null && leg == null) {
				bot.setStatus("following the map to a " + site.name());
				if (site.y() == null && bot.startFlight(mapped)) return Status.RUNNING;
				leg = site.y() == null ? new Goal.XZ(mapped.getX(), mapped.getZ()) : new Goal.XYZ(mapped.getX(), site.y(), mapped.getZ());
			}
			if (mapped == null && site.y() == null) {
				if (explorer == null) explorer = new Explorer(site.biomes());
				explorer.tick(bot, "a " + site.name());
				legs = explorer.legs();
				return Status.RUNNING;
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
			return Status.RUNNING;
		}

		private void turn(Bot bot) {
			heading = heading == null ? Direction.fromYRot(bot.player().getYRot()) : heading.getClockWise();
			leg = null;
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
			List<BlockPos> found = bot.scanner().findBlocks(s -> brush ? suspicious(s) : container(s), SCAN_CHUNKS, 128);
			return found.stream().filter(pos -> pos.distSqr(tell) <= (double) SITE_RADIUS * SITE_RADIUS && !looted.contains(pos.asLong()) && !atBase(bot, pos))
					.min(Comparator.comparingDouble(pos -> pos.distSqr(feet))).map(pos -> new Target(pos, null)).orElse(null);
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
