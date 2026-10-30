package com.autocraft;

import com.autocraft.cinema.Film;
import com.autocraft.command.Commands;
import com.autocraft.schematic.SchematicLibrary;
import com.autocraft.ui.AutoCraftScreen;
import com.autocraft.ui.GhostRenderer;
import com.autocraft.ui.HudOverlay;
import com.autocraft.ui.Placements;
import com.autocraft.tasks.TaskTree;
import com.autocraft.team.Team;
import com.autocraft.world.ChestMemory;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

public final class AutoCraftClient implements ClientModInitializer {
	public static final String MOD_ID = "autocraft";

	@Override
	public void onInitializeClient() {
		if (Boolean.getBoolean("autocraft.harness")) DevHarness.preload();
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		KeyMapping openPanel = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.autocraft.open_panel", InputConstants.KEY_K, category));
		Placements.registerKeys(category);

		// Start of tick: inputs set here are read by the player tick in the same frame.
		ClientTickEvents.START_CLIENT_TICK.register(mc -> Bot.get().tick());
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (openPanel.consumeClick()) {
				if (mc.player != null && mc.gui.screen() == null) mc.gui.setScreen(new AutoCraftScreen());
			}
			Placements.tick(mc);
			GhostRenderer.tick(mc);
			Team.tick(mc);
			ChestMemory.tick(mc); // remembers what's in every chest screen that opens, ours or yours
			TaskSave.tick(mc);
			TaskTree.tick(mc);
			Film.clientTick(mc);
			SpawnPoint.tick(mc); // after a death: is the respawn point where we think it is?
			if (Boolean.getBoolean("autocraft.harness")) DevHarness.tick(mc);
		});
		LevelRenderEvents.COLLECT_SUBMITS.register(GhostRenderer::collect);
		LevelRenderEvents.COLLECT_SUBMITS.register(Film::collect);
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "status"), HudOverlay::extract);
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "placement"), Placements::extractHud);
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "film"), Film::hud);
		Film.hideVanillaHud();
		// "!ac ..." chat lines are commands for us and never reach the server.
		ClientSendMessageEvents.ALLOW_CHAT.register(message -> !Commands.handle(message));
		// "Respawn point set" and the bed's refusals: the client is never told its respawn point otherwise
		ClientReceiveMessageEvents.GAME.register(SpawnPoint::onGameMessage);
		ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> TaskSave.onJoin());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
			Bot.get().onDisconnect();
			Placements.cancel();
			SpawnPoint.onDisconnect();
		});
		SchematicLibrary.folder(); // create config/autocraft/schematics so users know where to put files
	}
}
