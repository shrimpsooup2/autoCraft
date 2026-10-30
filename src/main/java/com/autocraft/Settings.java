package com.autocraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

/** User options that outlive the session, saved to {@code config/autocraft/settings.json}. Change a field, then {@link #save()}. */
public final class Settings {
	private static @Nullable Settings instance;

	/** Disconnect once the job is done (and the loot is stored). */
	public boolean logoffWhenDone = true;
	/** Small status overlay at the top left while the bot runs. */
	public boolean statusOverlay = true;
	/** Turn the view along curved, eased paths like a hand on a mouse instead of snapping. */
	public boolean smoothAim = true;
	/** Disconnect as soon as another player comes within {@link #playerAlertRadius} blocks. */
	public boolean logoffNearPlayers = false;
	public int playerAlertRadius = 48;
	/** After dying: go back for the dropped items, then carry on with the job (otherwise stop). */
	public boolean recoverAfterDeath = true;
	/** The base chests hold materials: look there before gathering even when we haven't seen inside yet. */
	public boolean materialsAtBase = false;
	/** Pick the job/build back up after a relog or crash. */
	public boolean resumeAfterRelog = true;
	/**
	 * Working far from the spawn point where dying looks likely (see {@code RespawnBedBehavior}): put a bed down near
	 * the work and set the spawn point with it, so a death means a short walk back to the dropped items.
	 */
	public boolean respawnBeds = false;
	/** "Far" for respawn beds: the spawn point is more than this many blocks away (horizontally), or in another dimension. */
	public int respawnBedDistance = 256;

	public static Settings get() {
		if (instance == null) instance = load();
		return instance;
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("autocraft").resolve("settings.json");
	}

	private static Settings load() {
		try {
			if (Files.exists(file())) {
				Settings s = new Gson().fromJson(Files.readString(file()), Settings.class);
				if (s != null) return s;
			}
		} catch (IOException | JsonParseException e) {
			Chat.LOG.warn("Could not read settings.json; using defaults", e);
		}
		return new Settings();
	}

	public void save() {
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), new GsonBuilder().setPrettyPrinting().create().toJson(this));
		} catch (IOException e) {
			Chat.LOG.warn("Could not save settings.json", e);
		}
	}
}
