package com.autocraft;

import com.autocraft.world.WorldKey;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jspecify.annotations.Nullable;

/**
 * Where the player respawns, per world, and the beds the bot put down, saved to {@code config/autocraft/spawn_points.json}.
 * The client is never told its respawn point, so it is tracked: set by the user ({@code !ac spawn here}, the panel), by a
 * bed or respawn anchor the game confirms ("Respawn point set" — the bed the bot just clicked, else the one under the
 * crosshair), and corrected after every death by where the player actually came back (a broken or blocked bed sends us
 * to the world spawn). Nothing known: the world spawn the client was sent.
 */
public final class SpawnPoint {
	/** A respawn this close (horizontally) to the recorded point confirms it: vanilla scatters world respawns over 10 blocks. */
	private static final int RESPAWN_SLACK = 16;
	/** A new player instance ticks this long before its position counts (the position packet follows the respawn). */
	private static final int SETTLE_TICKS = 20;
	/** A "Respawn point set" this long after the bot clicked a bed is that bed's. */
	private static final int CLICK_TICKS = 20 * 5;
	private static final String OVERWORLD = Level.OVERWORLD.identifier().toString();
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public enum Kind {
		/** A bed or respawn anchor (ours or the user's). */
		BED,
		/** The world spawn. */
		WORLD,
		/** Given by the user ({@code !ac spawn}, the panel). */
		SET
	}

	/** The respawn point: block, dimension id ({@code minecraft:overworld}), what it is. */
	public record Spot(int x, int y, int z, String dim, Kind kind) {
		public BlockPos pos() {
			return new BlockPos(x, y, z);
		}

		/** Horizontal distance to {@code pos} in dimension {@code dimension}; infinite in another dimension. */
		public double distance(BlockPos pos, String dimension) {
			if (!dim.equals(dimension)) return Double.POSITIVE_INFINITY;
			double dx = x - pos.getX(), dz = z - pos.getZ();
			return Math.sqrt(dx * dx + dz * dz);
		}

		public String describe() {
			String where = pos().toShortString() + (dim.equals(OVERWORLD) ? "" : " in " + dim.substring(dim.indexOf(':') + 1).replace('_', ' '));
			return where + switch (kind) {
				case BED -> " (bed)";
				case WORLD -> " (world spawn)";
				case SET -> " (set by you)";
			};
		}
	}

	/** A bed the bot placed. */
	public record Bed(int x, int y, int z) {
		public BlockPos pos() {
			return new BlockPos(x, y, z);
		}
	}

	/** What the server answered to the bot's last bed click. */
	public enum Reply {
		NONE,
		/** "Respawn point set": it's this bed now. */
		SET,
		/** Daytime or monsters nearby without "Respawn point set": the spawn already was this bed (vanilla only says so on a change). */
		ALREADY,
		TOO_FAR,
		OBSTRUCTED,
		OCCUPIED
	}

	/** Per world: the respawn point as far as we know, our beds. */
	private static final class Memory {
		@Nullable Spot spawn;
		List<Bed> beds = new ArrayList<>();
	}

	private static @Nullable Map<String, Memory> worlds;
	private static @Nullable BlockPos clickedBed;
	private static long clickedAt;
	private static Reply reply = Reply.NONE;
	/** The player that died, until its replacement has respawned and settled. */
	private static @Nullable LocalPlayer dead;
	/** The game said the bed or anchor was missing or blocked on this respawn. */
	private static boolean bedLost;

	private SpawnPoint() {}

	/** The respawn point: the recorded one, else the world spawn (known in the Overworld only); null when unknown. */
	public static @Nullable Spot current() {
		Memory m = memory();
		if (m.spawn != null) return m.spawn;
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.level.dimension() != Level.OVERWORLD) return null;
		LevelData.RespawnData world = mc.level.getLevelData().getRespawnData();
		BlockPos p = world.pos();
		return new Spot(p.getX(), p.getY(), p.getZ(), world.dimension().identifier().toString(), Kind.WORLD);
	}

	/** Record a new respawn point and say so ({@code why}: how we know); the user's own settings are always confirmed. */
	public static void set(BlockPos pos, String dim, Kind kind, String why) {
		Memory m = memory();
		Spot before = current();
		m.spawn = new Spot(pos.getX(), pos.getY(), pos.getZ(), dim, kind);
		save();
		boolean moved = before == null || !before.pos().equals(pos) || !before.dim().equals(dim);
		String msg = "Spawn point now " + m.spawn.describe() + ": " + why + (before == null || !moved ? "." : " (was " + before.describe() + ").");
		if (kind == Kind.SET) Chat.info(msg);
		else if (moved) announce(msg);
	}

	/** Dimension id of the level the player is in ({@code minecraft:overworld}). */
	public static String dimensionOf(Level level) {
		return level.dimension().identifier().toString();
	}

	public static List<Bed> beds() {
		return List.copyOf(memory().beds);
	}

	public static void addBed(BlockPos foot) {
		Memory m = memory();
		m.beds.removeIf(b -> b.pos().equals(foot));
		m.beds.add(new Bed(foot.getX(), foot.getY(), foot.getZ()));
		save();
	}

	/** Forget our bed at {@code pos} (either half). */
	public static void removeBed(BlockPos pos) {
		if (memory().beds.removeIf(b -> b.pos().distManhattan(pos) <= 1)) save();
	}

	// ---- the bot's bed clicks -------------------------------------------------------------------

	/** The bot is about to click the bed at {@code pos}: the next confirmation is about it. */
	public static void bedClicked(BlockPos pos, long gameTime) {
		clickedBed = pos.immutable();
		clickedAt = gameTime;
		reply = Reply.NONE;
	}

	/** What the server said to the last click so far ({@link Reply#NONE} while waiting). */
	public static Reply reply() {
		return reply;
	}

	// ---- what the game tells us ----------------------------------------------------------------

	/** A system message (chat or action bar) from the game: bed and respawn-anchor answers. */
	public static void onGameMessage(Component message, boolean overlay) {
		if (!(message.getContents() instanceof TranslatableContents t)) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) return;
		switch (t.getKey()) {
			case "block.minecraft.set_spawn" -> {
				BlockPos bed = recentClick(mc);
				boolean ours = bed != null;
				if (bed == null) bed = lookedAtRespawnBlock(mc);
				if (ours) reply = Reply.SET;
				set(bed != null ? bed : mc.player.blockPosition(), dimensionOf(mc.level), Kind.BED,
						ours ? "the bed took" : bed != null ? "the game confirmed it" : "the game confirmed a respawn point here");
			}
			// set before the daytime and monster checks: no "Respawn point set" with these means it already was this bed
			case "block.minecraft.bed.no_sleep", "block.minecraft.bed.not_safe" -> {
				BlockPos bed = recentClick(mc);
				if (bed != null && reply == Reply.NONE) {
					reply = Reply.ALREADY;
					set(bed, dimensionOf(mc.level), Kind.BED, "the bed already was our spawn point");
				}
			}
			case "block.minecraft.bed.too_far_away" -> {
				if (recentClick(mc) != null) reply = Reply.TOO_FAR;
			}
			case "block.minecraft.bed.obstructed" -> {
				if (recentClick(mc) != null) reply = Reply.OBSTRUCTED;
			}
			case "block.minecraft.bed.occupied" -> {
				if (recentClick(mc) != null) reply = Reply.OCCUPIED;
			}
			case "block.minecraft.spawn.not_valid" -> bedLost = true;
			default -> {
			}
		}
	}

	private static @Nullable BlockPos recentClick(Minecraft mc) {
		return clickedBed != null && mc.level.getGameTime() - clickedAt <= CLICK_TICKS ? clickedBed : null;
	}

	/** The bed or respawn anchor under the crosshair (the user just used it), or null. */
	private static @Nullable BlockPos lookedAtRespawnBlock(Minecraft mc) {
		if (mc.hitResult == null || mc.hitResult.getType() != HitResult.Type.BLOCK) return null;
		BlockPos pos = ((BlockHitResult) mc.hitResult).getBlockPos();
		var state = mc.level.getBlockState(pos);
		return state.is(BlockTags.BEDS) || state.is(Blocks.RESPAWN_ANCHOR) ? pos : null;
	}

	/** Every client tick: after a death, check the respawn point against where the player came back. */
	public static void tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null) return;
		if (!p.isAlive()) {
			dead = p;
			return;
		}
		if (dead == null) return;
		if (p == dead) { // revived without a respawn: nothing learned
			dead = null;
			return;
		}
		if (p.tickCount < SETTLE_TICKS) return;
		dead = null;
		respawned(p.blockPosition(), dimensionOf(mc.level), mc.level);
	}

	/**
	 * Came back at {@code pos}: near the recorded point confirms it. Elsewhere, the recorded bed is gone or blocked (the
	 * game says "You have no home bed...") and this is the world spawn — or a respawn point we never heard about.
	 */
	private static void respawned(BlockPos pos, String dim, Level level) {
		boolean lost = bedLost;
		bedLost = false;
		Spot known = current();
		if (lost && known != null && known.kind() == Kind.BED) removeBed(known.pos());
		if (!lost && known != null && known.distance(pos, dim) <= RESPAWN_SLACK) return;
		LevelData.RespawnData world = level.getLevelData().getRespawnData();
		boolean atWorldSpawn = lost || dim.equals(world.dimension().identifier().toString())
				&& Math.abs(world.pos().getX() - pos.getX()) <= RESPAWN_SLACK && Math.abs(world.pos().getZ() - pos.getZ()) <= RESPAWN_SLACK;
		set(pos, dim, atWorldSpawn ? Kind.WORLD : Kind.BED, lost && known != null ? "the respawn point at " + known.pos().toShortString()
				+ " is gone or blocked" : "that's where we respawned");
	}

	/** Left the world: a death screen left behind there means nothing in the next one. */
	public static void onDisconnect() {
		dead = null;
		bedLost = false;
		clickedBed = null;
		reply = Reply.NONE;
	}

	/** Spawn changes are news when the bot makes beds; otherwise they only go to the log. */
	private static void announce(String msg) {
		if (Settings.get().respawnBeds) Chat.info(msg);
		else Chat.LOG.info(msg);
	}

	private static Memory memory() {
		if (worlds == null) worlds = load();
		return worlds.computeIfAbsent(WorldKey.current(), k -> new Memory());
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("autocraft").resolve("spawn_points.json");
	}

	private static Map<String, Memory> load() {
		Map<String, Memory> out = new HashMap<>();
		try {
			if (Files.exists(file())) {
				Map<String, Memory> loaded = GSON.fromJson(Files.readString(file()), new TypeToken<Map<String, Memory>>() {}.getType());
				if (loaded != null) loaded.forEach((k, v) -> {
					if (v.beds == null) v.beds = new ArrayList<>();
					out.put(k, v);
				});
			}
		} catch (IOException | JsonParseException e) {
			Chat.LOG.warn("Could not read spawn_points.json", e);
		}
		return out;
	}

	private static void save() {
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(worlds));
		} catch (IOException e) {
			Chat.LOG.warn("Could not save spawn_points.json", e);
		}
	}
}
