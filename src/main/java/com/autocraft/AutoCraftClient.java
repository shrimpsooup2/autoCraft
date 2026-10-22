package com.autocraft;

import com.autocraft.command.Commands;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

public final class AutoCraftClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// Start of tick: inputs set here are read by the player tick in the same frame.
		ClientTickEvents.START_CLIENT_TICK.register(mc -> Bot.get().tick());
		// "!ac ..." chat lines are commands for us and never reach the server.
		ClientSendMessageEvents.ALLOW_CHAT.register(message -> !Commands.handle(message));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> Bot.get().onDisconnect());
	}
}
