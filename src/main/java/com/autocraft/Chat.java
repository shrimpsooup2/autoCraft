package com.autocraft;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Client-only chat output (never sent to the server) mirrored to the game log. */
public final class Chat {
	public static final Logger LOG = LoggerFactory.getLogger("autoCraft");

	private Chat() {}

	public static void info(String msg) {
		send(msg, ChatFormatting.GRAY);
		LOG.info(msg);
	}

	public static void warn(String msg) {
		send(msg, ChatFormatting.GOLD);
		LOG.warn(msg);
	}

	public static void error(String msg) {
		send(msg, ChatFormatting.RED);
		LOG.error(msg);
	}

	private static void send(String msg, ChatFormatting color) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		mc.player.sendSystemMessage(Component.literal("[autoCraft] ").withStyle(ChatFormatting.AQUA)
				.append(Component.literal(msg).withStyle(color)));
	}
}
