package com.autocraft.ui;

import com.autocraft.Bot;
import com.autocraft.Settings;
import com.autocraft.build.BuildOrder;
import com.autocraft.goal.Job;
import com.autocraft.goal.Requirement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;

/** Small top-left panel while the bot works: activity, job progress bars, build progress. */
public final class HudOverlay {
	private static final int MAX_ROWS = 5;
	private static final int WIDTH = 170;

	private HudOverlay() {}

	public static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		Bot bot = Bot.get();
		LocalPlayer p = mc.player;
		if (!Settings.get().statusOverlay || !bot.isRunning() || p == null || mc.getDebugOverlay().showDebugScreen()
				|| mc.gui.screen() instanceof AutoCraftScreen) {
			return;
		}
		Font font = mc.font;
		Job job = bot.job();
		BuildOrder build = bot.buildOrder();
		int rows = 1 + (job == null ? 0 : Math.min(MAX_ROWS, job.requirements().size())) + (build == null ? 0 : 1);
		int x = 4;
		int y = 4;
		g.fill(x - 2, y - 2, x + WIDTH + 2, y + rows * 12 + 1, 0x90000000);
		String head = (bot.isPaused() ? "[paused] " : "") + bot.activeBehavior() + ": " + bot.status();
		g.text(font, font.plainSubstrByWidth(head, WIDTH), x, y, 0xFFFFFFFF);
		y += 12;
		if (job != null) {
			int shown = 0;
			for (Requirement r : job.requirements()) {
				if (shown++ == MAX_ROWS) break;
				Widgets.progressRow(g, font, x, y, WIDTH, ItemCatalog.iconOf(r), r.name(), Math.min(job.have(p, r), r.amount()), r.amount(),
						job.isSkipped(r));
				y += 12;
			}
		}
		if (build != null) {
			Widgets.progressRow(g, font, x, y, WIDTH, null, "build " + build.schematic().name(), bot.buildPlaced(), Math.max(1, bot.buildTotal()), false);
		}
	}
}
