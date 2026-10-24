package com.autocraft.schematic;

import com.autocraft.Chat;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

/**
 * Built-in structures plus every {@code .litematic}, {@code .schem} and {@code .nbt} file the user drops into
 * {@code config/autocraft/schematics/}. Files are (re)loaded when they change on disk.
 */
public final class SchematicLibrary {
	private static final List<String> EXTENSIONS = List.of(".litematic", ".schem", ".nbt");

	private record Loaded(long modified, Schematic schematic) {}

	private static @Nullable List<Schematic> builtIns;
	private static final Map<Path, Loaded> cache = new HashMap<>();

	private SchematicLibrary() {}

	/** The user's schematic folder (created on first use). */
	public static Path folder() {
		Path dir = FabricLoader.getInstance().getConfigDir().resolve("autocraft").resolve("schematics");
		try {
			Files.createDirectories(dir);
		} catch (IOException e) {
			Chat.LOG.warn("Could not create {}", dir, e);
		}
		return dir;
	}

	private static List<Schematic> builtIns() {
		if (builtIns == null) builtIns = BuiltinSchematics.all();
		return builtIns;
	}

	/** Names usable with {@code !ac build}: built-ins first, then files (without extension). */
	public static List<String> names() {
		List<String> out = new ArrayList<>();
		for (Schematic s : builtIns()) out.add(s.name());
		for (Path f : files()) out.add(stem(f));
		return out;
	}

	public static @Nullable Schematic get(String name) {
		String wanted = name.toLowerCase(Locale.ROOT);
		for (Schematic s : builtIns()) {
			if (s.name().equals(wanted)) return s;
		}
		for (Path f : files()) {
			if (stem(f).toLowerCase(Locale.ROOT).equals(wanted)) return load(f);
		}
		return null;
	}

	/** Every design (built-in or user {@code farm-<resource>-*} file) that produces {@code resource}. */
	public static List<Schematic> farmsFor(String resource) {
		List<Schematic> out = new ArrayList<>();
		for (Path f : files()) {
			if (!stem(f).toLowerCase(Locale.ROOT).startsWith("farm-" + resource)) continue;
			Schematic s = load(f);
			if (s != null && s.farm() != null && s.farm().provides().equals(resource)) out.add(s); // user designs first
		}
		for (Schematic s : builtIns()) {
			if (s.farm() != null && s.farm().provides().equals(resource)) out.add(s);
		}
		return out;
	}

	/** The user file behind a schematic name, or null for built-ins / unknown names. */
	public static @Nullable Path fileOf(String name) {
		String wanted = name.toLowerCase(Locale.ROOT);
		for (Path f : files()) {
			if (stem(f).toLowerCase(Locale.ROOT).equals(wanted)) return f;
		}
		return null;
	}

	/** Load a schematic file outside the folder (sent by a team leader); cached like folder files. */
	public static @Nullable Schematic load(Path file) {
		try {
			long modified = Files.getLastModifiedTime(file).toMillis();
			Loaded cached = cache.get(file);
			if (cached != null && cached.modified == modified) return cached.schematic;
			Schematic s = SchematicLoader.load(file);
			cache.put(file, new Loaded(modified, s));
			Chat.LOG.info("Loaded schematic {}: {}x{}x{}, {} blocks", s.name(), s.size().getX(), s.size().getY(), s.size().getZ(), s.nonAirCount());
			return s;
		} catch (IOException | RuntimeException e) {
			Chat.warn("Could not read schematic " + file.getFileName() + ": " + e.getMessage());
			Chat.LOG.warn("Schematic load failed", e);
			return null;
		}
	}

	private static List<Path> files() {
		try (Stream<Path> s = Files.list(folder())) {
			return s.filter(p -> EXTENSIONS.stream().anyMatch(ext -> p.toString().toLowerCase(Locale.ROOT).endsWith(ext))).sorted().toList();
		} catch (IOException e) {
			return List.of();
		}
	}

	private static String stem(Path f) {
		String n = f.getFileName().toString();
		return n.substring(0, n.lastIndexOf('.'));
	}
}
