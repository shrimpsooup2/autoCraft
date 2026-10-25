package com.autocraft;

import com.autocraft.goal.Requirement.Dim;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/** Where the bot died (newest last, the last few kept), saved to {@code config/autocraft/deaths.json}. */
public final class DeathLog {
	private static final int KEEP = 10;
	private static @Nullable List<Death> deaths;

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

	public static @Nullable Death last() {
		List<Death> all = all();
		return all.isEmpty() ? null : all.getLast();
	}

	private static List<Death> all() {
		if (deaths == null) deaths = load();
		return deaths;
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("autocraft").resolve("deaths.json");
	}

	private static List<Death> load() {
		try {
			if (Files.exists(file())) {
				List<Death> loaded = new Gson().fromJson(Files.readString(file()), new TypeToken<List<Death>>() {}.getType());
				if (loaded != null) return new ArrayList<>(loaded);
			}
		} catch (IOException | JsonParseException e) {
			Chat.LOG.warn("Could not read deaths.json", e);
		}
		return new ArrayList<>();
	}

	private static void save() {
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), new GsonBuilder().setPrettyPrinting().create().toJson(all()));
		} catch (IOException e) {
			Chat.LOG.warn("Could not save deaths.json", e);
		}
	}
}
