package com.autocraft.world;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.server.MinecraftServer;

/** Which world we're in, for files that remember things per world: singleplayer save name or server address. */
public final class WorldKey {
	private WorldKey() {}

	public static String current() {
		Minecraft mc = Minecraft.getInstance();
		MinecraftServer local = mc.getSingleplayerServer();
		if (local != null) return "sp:" + local.getWorldData().getLevelName();
		ServerData server = mc.getCurrentServer();
		return server != null ? "mp:" + server.ip : "unknown";
	}
}
