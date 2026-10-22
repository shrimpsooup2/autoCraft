package com.autocraft;

import com.autocraft.command.Commands;
import com.autocraft.schematic.SchematicLibrary;
import com.autocraft.ui.AutoCraftScreen;
import com.autocraft.ui.HudOverlay;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

public final class AutoCraftClient implements ClientModInitializer {
	public static final String MOD_ID = "autocraft";

	@Override
	public void onInitializeClient() {
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		KeyMapping openPanel = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.autocraft.open_panel", InputConstants.KEY_K, category));

		// Start of tick: inputs set here are read by the player tick in the same frame.
		ClientTickEvents.START_CLIENT_TICK.register(mc -> Bot.get().tick());
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (openPanel.consumeClick()) {
				if (mc.player != null && mc.gui.screen() == null) mc.gui.setScreen(new AutoCraftScreen());
			}
		});
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "status"), HudOverlay::extract);
		// "!ac ..." chat lines are commands for us and never reach the server.
		ClientSendMessageEvents.ALLOW_CHAT.register(message -> !Commands.handle(message));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> Bot.get().onDisconnect());
		SchematicLibrary.folder(); // create config/autocraft/schematics so users know where to put files
	}
}
