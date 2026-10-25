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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/** Where the bot died (newest last, the last few kept), per world, saved to {@code config/autocraft/deaths.json}. */
public final class DeathLog {
	private static final int KEEP = 10;
	private static @Nullable Map<String, List<Death>> worlds;

	/** One death: position, dimension, what killed us, wall-clock time. */
	public record Death(int x, int y, int z, Dim dim, String cause, long time) {
		public BlockPos pos() {
			return new BlockPos(x, y, z);
		}
	}

	private DeathLog() {}

	public static void add(BlockPos pos, Dim dim, String cause) {
		List<Death> all = all();
		all.add(new Death(pos.getX(), pos.getY(), pos.getZ(), dim, cause, System.currentTimeMillis()));
		while (all.size() > KEEP) all.removeFirst();
		save();
	}

	/** The last death in this world (another world's death spot means nothing here). */
	public static @Nullable Death last() {
		List<Death> all = all();
		return all.isEmpty() ? null : all.getLast();
	}

	private static List<Death> all() {
		if (worlds == null) worlds = load();
		return worlds.computeIfAbsent(WorldKey.current(), k -> new ArrayList<>());
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("autocraft").resolve("deaths.json");
	}

	private static Map<String, List<Death>> load() {
		Map<String, List<Death>> out = new HashMap<>();
		try {
			if (Files.exists(file())) {
				Map<String, List<Death>> loaded = new Gson().fromJson(Files.readString(file()), new TypeToken<Map<String, List<Death>>>() {}.getType());
				if (loaded != null) loaded.forEach((k, v) -> out.put(k, new ArrayList<>(v)));
			}
		} catch (IOException | JsonParseException e) {
			Chat.LOG.warn("Could not read deaths.json (a file from before per-world saving starts over)", e);
		}
		return out;
	}

	private static void save() {
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), new GsonBuilder().setPrettyPrinting().create().toJson(worlds));
		} catch (IOException e) {
			Chat.LOG.warn("Could not save deaths.json", e);
		}
	}
}
