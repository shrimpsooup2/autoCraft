package com.autocraft.travel.portal;

import com.autocraft.Chat;
import com.autocraft.goal.Requirement.Dim;
import com.autocraft.world.WorldKey;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/**
 * Nether portals the bot has come through, per world, saved to {@code config/autocraft/portals.json}: after a relog
 * or a death the way back is still known, even when the portal is out of view (a portal 130 blocks off, at the far
 * end of a cave, had the bot planning a new bucket portal).
 */
public final class KnownPortals {
	/** A portal within this distance of a known one is the same portal. */
	private static final int SAME_PORTAL = 4;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Bottom portal block of a portal. */
	record Portal(int x, int y, int z, Dim dim) {
		BlockPos pos() {
			return new BlockPos(x, y, z);
		}
	}

	private static @Nullable Map<String, List<Portal>> worlds;

	private KnownPortals() {}

	public static void remember(Dim dim, BlockPos bottom) {
		List<Portal> list = portals();
		if (list.stream().anyMatch(p -> p.dim == dim && p.pos().distSqr(bottom) <= SAME_PORTAL * SAME_PORTAL)) return;
		list.add(new Portal(bottom.getX(), bottom.getY(), bottom.getZ(), dim));
		save();
	}

	/** It's gone (broken, or out of reach). */
	public static void forget(Dim dim, BlockPos bottom) {
		if (portals().removeIf(p -> p.dim == dim && p.pos().distSqr(bottom) <= SAME_PORTAL * SAME_PORTAL)) save();
	}

	/** The known portal in {@code dim} nearest to {@code from}, or null. */
	public static @Nullable BlockPos nearest(Dim dim, BlockPos from) {
		return portals().stream().filter(p -> p.dim == dim).map(Portal::pos).min(Comparator.comparingDouble(p -> p.distSqr(from))).orElse(null);
	}

	private static List<Portal> portals() {
		if (worlds == null) worlds = load();
		return worlds.computeIfAbsent(WorldKey.current(), k -> new ArrayList<>());
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("autocraft").resolve("portals.json");
	}

	private static Map<String, List<Portal>> load() {
		Map<String, List<Portal>> out = new HashMap<>();
		try {
			if (Files.exists(file())) {
				Map<String, List<Portal>> loaded = GSON.fromJson(Files.readString(file()), new TypeToken<Map<String, List<Portal>>>() {}.getType());
				if (loaded != null) loaded.forEach((k, v) -> out.put(k, new ArrayList<>(v)));
			}
		} catch (IOException | JsonParseException e) {
			Chat.LOG.warn("Could not read portals.json", e);
		}
		return out;
	}

	private static void save() {
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(worlds));
		} catch (IOException e) {
			Chat.LOG.warn("Could not save portals.json", e);
		}
	}
}
