package com.autocraft.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;

/** Drawing helpers shared by the screen and the HUD. */
final class Widgets {
	static final int WHITE = 0xFFFFFFFF;
	static final int GRAY = 0xFFA0A0A0;
	static final int DARK = 0xFF2B2B2B;
	static final int GREEN = 0xFF55DD55;
	static final int AMBER = 0xFFFFAA00;
	private static final int BAR_WIDTH = 46;

	private Widgets() {}

	/** {@code icon label .... have/total [bar]} on one 12px row. */
	static void progressRow(GuiGraphicsExtractor g, Font font, int x, int y, int width, @Nullable Item icon, String label, int have, int total,
			boolean skipped) {
		int textX = x;
		if (icon != null) {
			smallItem(g, icon, x, y);
			textX += 12;
		}
		String count = have + "/" + total;
		int countX = x + width - BAR_WIDTH - 4 - font.width(count);
		g.text(font, font.plainSubstrByWidth(skipped ? label + " (skipped)" : label, countX - textX - 4), textX, y + 1, skipped ? GRAY : WHITE);
		g.text(font, count, countX, y + 1, GRAY);
		bar(g, x + width - BAR_WIDTH, y + 3, BAR_WIDTH, 4, total == 0 ? 0 : (double) have / total);
	}

	static void bar(GuiGraphicsExtractor g, int x, int y, int width, int height, double fraction) {
		double f = Math.max(0, Math.min(1, fraction));
		g.fill(x, y, x + width, y + height, DARK);
		g.fill(x, y, x + (int) Math.round(width * f), y + height, f >= 1 ? GREEN : AMBER);
	}

	/** A 10px item icon (16px scaled down) so it fits a text row. */
	static void smallItem(GuiGraphicsExtractor g, Item item, int x, int y) {
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(0.625f, 0.625f);
		g.item(item.getDefaultInstance(), 0, 0);
		g.pose().popMatrix();
	}

	/** Inventory-style slot background. */
	static void slot(GuiGraphicsExtractor g, int x, int y, boolean hovered) {
		g.fill(x, y, x + 18, y + 18, 0xFF373737);
		g.fill(x + 1, y + 1, x + 18, y + 18, 0xFFFFFFFF);
		g.fill(x + 1, y + 1, x + 17, y + 17, hovered ? 0xFFC5C5C5 : 0xFF8B8B8B);
	}
}
