package com.autocraft.world;

import com.autocraft.mixin.BiomeManagerAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/**
 * Which world we're in, for files that remember things per world (bases, task tree, structures, chests, resume
 * state...): the singleplayer save folder or the server address, plus the world's hashed seed (sent to every
 * client). The world's display name isn't enough — every default singleplayer world is called "New World" — and
 * one address can front several worlds.
 */
public final class WorldKey {
	private static @Nullable ClientLevel keyedLevel;
	private static String key = "unknown";

	private WorldKey() {}

	/** The current world's key; between worlds (no level), the last world's. */
	public static String current() {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null || level == keyedLevel) return key;
		MinecraftServer local = mc.getSingleplayerServer();
		ServerData server = mc.getCurrentServer();
		String where = local != null ? "sp:" + local.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName()
				: server != null ? "mp:" + server.ip : "unknown";
		long seed = ((BiomeManagerAccessor) level.getBiomeManager()).autocraft$biomeZoomSeed(); // same in every dimension
		key = where + "#" + Long.toHexString(seed);
		keyedLevel = level;
		return key;
	}
}
