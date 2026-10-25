package com.autocraft;

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
 * Named storage bases per world (e.g. "home", "nether", "mine"), saved to {@code config/autocraft/bases.json}.
 * The bot uses the nearest base in its dimension for deposits, stations and fetching; the first base is home
 * when none is in this dimension.
 */
public final class Bases {
	/** A base within this distance of a new one is moved instead of adding another. */
	private static final int SAME_BASE = 16;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public record Base(String name, int x, int y, int z, Dim dim) {
		public BlockPos pos() {
			return new BlockPos(x, y, z);
		}
	}

	private static @Nullable Map<String, List<Base>> worlds;

	private Bases() {}

	public static List<Base> all() {
		if (worlds == null) worlds = load();
		return worlds.computeIfAbsent(WorldKey.current(), k -> new ArrayList<>());
	}

	/**
	 * Add a base at {@code pos} (or move the one with that name, or the one right there). A null name picks
	 * "home" for the first base and "base N" after that. Returns the name used.
	 */
	public static String set(@Nullable String name, BlockPos pos, Dim dim) {
		List<Base> list = all();
		Base existing = list.stream().filter(b -> name != null ? b.name.equalsIgnoreCase(name)
				: b.dim == dim && b.pos().distSqr(pos) <= SAME_BASE * SAME_BASE).findFirst().orElse(null);
		String use = existing != null ? existing.name : name != null ? name : list.isEmpty() ? "home" : "base " + (list.size() + 1);
		Base b = new Base(use, pos.getX(), pos.getY(), pos.getZ(), dim);
		if (existing != null) list.set(list.indexOf(existing), b);
		else list.add(b);
		save();
		return use;
	}

	public static boolean remove(String name) {
		boolean removed = all().removeIf(b -> b.name.equalsIgnoreCase(name));
		if (removed) save();
		return removed;
	}

	public static @Nullable Base named(String name) {
		return all().stream().filter(b -> b.name.equalsIgnoreCase(name)).findFirst().orElse(null);
	}

	public static List<Base> inDimension(Dim dim) {
		return all().stream().filter(b -> b.dim == dim).toList();
	}

	/** Nearest base in {@code dim} to {@code from}; else home (the first base); null when there are none. */
	public static @Nullable Base nearest(Dim dim, @Nullable BlockPos from) {
		Base best = from == null ? null
				: inDimension(dim).stream().min(Comparator.comparingDouble(b -> b.pos().distSqr(from))).orElse(null);
		return best != null ? best : all().isEmpty() ? null : all().getFirst();
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("autocraft").resolve("bases.json");
	}

	private static Map<String, List<Base>> load() {
		Map<String, List<Base>> out = new HashMap<>();
		try {
			if (Files.exists(file())) {
				Map<String, List<Base>> loaded = GSON.fromJson(Files.readString(file()), new TypeToken<Map<String, List<Base>>>() {}.getType());
				if (loaded != null) loaded.forEach((k, v) -> out.put(k, new ArrayList<>(v)));
			}
		} catch (IOException | JsonParseException e) {
			Chat.LOG.warn("Could not read bases.json", e);
		}
		return out;
	}

	private static void save() {
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(worlds));
		} catch (IOException e) {
			Chat.LOG.warn("Could not save bases.json", e);
		}
	}
}
