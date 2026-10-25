package com.autocraft.build;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

/**
 * Everything the bot has built (bases, farms, user structures), per world ({@link WorldKey}), saved to
 * {@code config/autocraft/structures.json} so farms are reused across sessions. Built structures and the one under
 * construction are protected: pathfinding and resource collection never break blocks inside them.
 */
public final class StructureRegistry {
	/**
	 * One built structure; positions are world coordinates ({@code mirror} is a {@link Mirror} ordinal, 0 in old files;
	 * {@code materials} an encoded {@link Substitution}, null in old files).
	 */
	public record Entry(String schematic, int x, int y, int z, int rotation, int mirror, @Nullable String materials, Dim dimension, int minX,
			int minY, int minZ, int maxX, int maxY, int maxZ, @Nullable String provides) {
		public BlockPos origin() {
			return new BlockPos(x, y, z);
		}

		public Rotation rotationValue() {
			return Rotation.values()[rotation & 3];
		}

		public Mirror mirrorValue() {
			return Mirror.values()[Math.floorMod(mirror, Mirror.values().length)];
		}

		public Substitution substitution() {
			return materials == null ? Substitution.NONE : Substitution.decode(materials);
		}

		public boolean contains(BlockPos p) {
			return p.getX() >= minX && p.getX() <= maxX && p.getY() >= minY && p.getY() <= maxY && p.getZ() >= minZ && p.getZ() <= maxZ;
		}

		public BlockPos center() {
			return new BlockPos((minX + maxX) / 2, minY, (minZ + maxZ) / 2);
		}
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static @Nullable Map<String, List<Entry>> worlds;
	private static @Nullable BoundingBox underConstruction;
	private static @Nullable Dim underConstructionDim;

	private StructureRegistry() {}

	/** This world's structures. */
	public static List<Entry> all() {
		if (worlds == null) worlds = load();
		return worlds.computeIfAbsent(WorldKey.current(), k -> new ArrayList<>());
	}

	public static void add(Entry e) {
		all().removeIf(old -> old.origin().equals(e.origin()) && old.dimension() == e.dimension());
		all().add(e);
		save();
	}

	public static void remove(Entry e) {
		all().remove(e);
		save();
	}

	/** Area currently being built (protected from everything but the builder). */
	public static void setUnderConstruction(@Nullable BoundingBox box, @Nullable Dim dim) {
		underConstruction = box;
		underConstructionDim = dim;
	}

	/** Inside something we built (or are building) in this dimension. */
	public static boolean protects(BlockPos pos, Dim dim) {
		if (underConstruction != null && underConstructionDim == dim && underConstruction.isInside(pos)) return true;
		for (Entry e : all()) {
			if (e.dimension() == dim && e.contains(pos)) return true;
		}
		return false;
	}

	public static List<Entry> farmsFor(String resource, Dim dim) {
		List<Entry> out = new ArrayList<>();
		for (Entry e : all()) {
			if (resource.equals(e.provides()) && e.dimension() == dim) out.add(e);
		}
		return out;
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("autocraft").resolve("structures.json");
	}

	private static Map<String, List<Entry>> load() {
		Map<String, List<Entry>> out = new HashMap<>();
		try {
			if (Files.exists(file())) {
				Map<String, List<Entry>> loaded = GSON.fromJson(Files.readString(file()), new TypeToken<Map<String, List<Entry>>>() {}.getType());
				if (loaded != null) loaded.forEach((k, v) -> out.put(k, new ArrayList<>(v)));
			}
		} catch (IOException | JsonParseException e) {
			Chat.LOG.warn("Could not read structures.json (a file from before per-world saving starts over)", e);
		}
		return out;
	}

	private static void save() {
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(worlds));
		} catch (IOException e) {
			Chat.LOG.warn("Could not save structures.json", e);
		}
	}
}
