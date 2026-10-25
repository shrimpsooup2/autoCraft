package com.autocraft.build;

import com.autocraft.Chat;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;

/**
 * What the user allows the bot to change when building (chosen per placement; defaults saved to
 * {@code config/autocraft/materials.json}).
 */
public final class MaterialSettings {
	public static final String AUTO = "auto";
	public static final String EXACT = "exact";
	public static final String OFF = "off";

	/** {@code auto} (use the wood growing nearby), {@code exact}, or a wood type ("birch"). */
	public String wood = AUTO;
	/** {@code auto} (only when no trees are around), {@code off}, or a {@link Substitution.StandIn} name (always). */
	public String standIn = AUTO;
	/** Fill cells nobody can see (all six neighbors solid) with a filler block. */
	public boolean fillInterior;
	/** Filler materials, in preference order; the one carried most is used. */
	public List<Block> fillers = new ArrayList<>(List.of(Blocks.COBBLESTONE, Blocks.DIRT, Blocks.NETHERRACK));
	/** Explicit replacements: original block → block to build instead. */
	public Map<Block, Block> overrides = new LinkedHashMap<>();

	private static @Nullable MaterialSettings defaults;

	public MaterialSettings copy() {
		MaterialSettings c = new MaterialSettings();
		c.wood = wood;
		c.standIn = standIn;
		c.fillInterior = fillInterior;
		c.fillers = new ArrayList<>(fillers);
		c.overrides = new LinkedHashMap<>(overrides);
		return c;
	}

	/** Wood choices in cycle order: auto, exact, then every wood type. */
	public static List<String> woodChoices() {
		List<String> out = new ArrayList<>(List.of(AUTO, EXACT));
		out.addAll(Substitution.WOODS.reversed());
		return out;
	}

	/** Stand-in choices in cycle order: auto, off, then every family. */
	public static List<String> standInChoices() {
		List<String> out = new ArrayList<>(List.of(AUTO, OFF));
		for (Substitution.StandIn s : Substitution.StandIn.values()) out.add(s.name());
		return out;
	}

	// ---- saved defaults -------------------------------------------------------------------------

	private record Stored(String wood, String standIn, boolean fillInterior, List<String> fillers, Map<String, String> overrides) {}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** A fresh copy of the saved defaults. */
	public static MaterialSettings defaults() {
		if (defaults == null) defaults = load();
		return defaults.copy();
	}

	public void saveAsDefault() {
		defaults = copy();
		List<String> fillerIds = fillers.stream().map(Substitution::id).toList();
		Map<String, String> overrideIds = new LinkedHashMap<>();
		overrides.forEach((a, b) -> overrideIds.put(Substitution.id(a), Substitution.id(b)));
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(new Stored(wood, standIn, fillInterior, fillerIds, overrideIds)));
		} catch (IOException e) {
			Chat.LOG.warn("Could not save material defaults", e);
		}
	}

	private static MaterialSettings load() {
		MaterialSettings s = new MaterialSettings();
		try {
			if (!Files.exists(file())) return s;
			Stored stored = GSON.fromJson(Files.readString(file()), Stored.class);
			if (stored == null) return s;
			if (stored.wood != null) s.wood = stored.wood;
			if (stored.standIn != null) s.standIn = stored.standIn;
			s.fillInterior = stored.fillInterior;
			if (stored.fillers != null) {
				s.fillers = new ArrayList<>();
				for (String id : stored.fillers) {
					Block b = Substitution.block(id);
					if (b != null) s.fillers.add(b);
				}
			}
			if (stored.overrides != null) {
				stored.overrides.forEach((a, b) -> {
					Block from = Substitution.block(a);
					Block to = Substitution.block(b);
					if (from != null && to != null) s.overrides.put(from, to);
				});
			}
		} catch (IOException | JsonParseException e) {
			Chat.LOG.warn("Could not read material defaults", e);
		}
		return s;
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("autocraft").resolve("materials.json");
	}
}
