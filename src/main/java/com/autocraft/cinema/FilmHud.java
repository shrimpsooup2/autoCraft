package com.autocraft.cinema;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** The film's 2D layer: fades, titles, chapter tags, chat lines, hearts, a keycap. GUI coordinates. */
final class FilmHud {
	private static final Identifier HEART = Identifier.withDefaultNamespace("hud/heart/full");
	private static final Identifier HALF_HEART = Identifier.withDefaultNamespace("hud/heart/half");
	private static final Identifier HEART_SLOT = Identifier.withDefaultNamespace("hud/heart/container");
	static final int RED = 0xE5352B;

	final GuiGraphicsExtractor g;
	final Font font;
	final int w;
	final int h;

	FilmHud(GuiGraphicsExtractor g) {
		this.g = g;
		this.font = Minecraft.getInstance().font;
		this.w = g.guiWidth();
		this.h = g.guiHeight();
	}

	static int argb(double alpha, int rgb) {
		return Fx.argb((int) Math.round(Anim.clamp01(alpha) * 255), rgb);
	}

	/** A chat line as autoCraft prints it. */
	static Component ac(String msg) {
		return Component.literal("[autoCraft] ").withStyle(ChatFormatting.AQUA).append(Component.literal(msg).withStyle(ChatFormatting.GRAY));
	}

	/** A server notice ("… joined the game"). */
	static Component notice(String msg) {
		return Component.literal(msg).withStyle(ChatFormatting.YELLOW);
	}

	/** The lines whose time has come (times[i] ≤ t, in order), for a chat that fills up as the shot goes. */
	static List<Component> upTo(double t, double[] times, List<Component> lines) {
		int n = 0;
		while (n < times.length && times[n] <= t) n++;
		return lines.subList(0, n);
	}

	void fade(double alpha) {
		if (alpha > 0.002) g.fill(0, 0, w, h, argb(alpha, 0x000000));
	}

	/** The whole screen washed with a colour (a portal's purple). */
	void tint(int rgb, double alpha) {
		if (alpha > 0.002) g.fill(0, 0, w, h, argb(alpha, rgb));
	}

	/** Text at a scale, its centre at (cx, cy) in GUI units. */
	void text(Component text, double cx, double cy, float scale, int rgb, double alpha, boolean shadow) {
		if (alpha <= 0.01) return;
		g.pose().pushMatrix();
		g.pose().translate((float) cx, (float) cy);
		g.pose().scale(scale, scale);
		g.text(font, text, -font.width(text) / 2, -font.lineHeight / 2, argb(alpha, rgb), shadow);
		g.pose().popMatrix();
	}

	void title(String text, double yFrac, float scale, int rgb, double alpha) {
		text(Component.literal(text), w / 2.0, h * yFrac, scale, rgb, alpha, true);
	}

	/** A chapter tag in the top-left: a red disc with its number, then the label. */
	void tag(int number, String label, double alpha) {
		if (alpha <= 0.01) return;
		int x = 10, y = 10, hgt = 16;
		int lw = font.width(label);
		g.fill(x, y, x + 24 + lw + 8, y + hgt, argb(alpha * 0.66, 0x0C0E12));
		g.fill(x + 2, y + 2, x + 14, y + hgt - 2, argb(alpha, RED));
		g.text(font, String.valueOf(number), x + 8 - font.width(String.valueOf(number)) / 2, y + 4, argb(alpha, 0xFFFFFF), false);
		g.text(font, label, x + 20, y + 4, argb(alpha, 0xFFFFFF), false);
	}

	/** Chat lines in the bottom-left like the game's chat (oldest first). */
	void chat(List<Component> lines, double alpha) {
		if (alpha <= 0.01 || lines.isEmpty()) return;
		int x = 4, lineH = 10;
		int y0 = h - 48 - lines.size() * lineH;
		for (int i = 0; i < lines.size(); i++) {
			Component line = lines.get(i);
			int y = y0 + i * lineH;
			g.fill(x - 2, y - 1, x + Math.max(160, font.width(line) + 4), y + lineH - 1, argb(alpha * 0.5, 0x000000));
			g.text(font, line, x, y, argb(alpha, 0xFFFFFF), true);
		}
	}

	/** The chat input bar with text being typed. */
	void chatInput(String typed, boolean caret, double alpha) {
		if (alpha <= 0.01) return;
		g.fill(2, h - 14, w - 2, h - 2, argb(alpha * 0.5, 0x000000));
		g.text(font, typed + (caret ? "_" : ""), 4, h - 12, argb(alpha, 0xFFFFFF), true);
	}

	/** A row of ten hearts above where the hotbar would be. health in half-hearts (0..20). */
	void hearts(int health, double alpha) {
		if (alpha <= 0.01) return;
		int x0 = w / 2 - 91, y = h - 39;
		for (int i = 0; i < 10; i++) {
			int x = x0 + i * 8;
			g.blitSprite(RenderPipelines.GUI_TEXTURED, HEART_SLOT, x, y, 9, 9, argb(alpha, 0xFFFFFF));
			if (health >= i * 2 + 2) g.blitSprite(RenderPipelines.GUI_TEXTURED, HEART, x, y, 9, 9, argb(alpha, 0xFFFFFF));
			else if (health == i * 2 + 1) g.blitSprite(RenderPipelines.GUI_TEXTURED, HALF_HEART, x, y, 9, 9, argb(alpha, 0xFFFFFF));
		}
	}

	/** A keyboard key with a letter, pressed (0..1) pushes it down and lights the letter. */
	void keycap(String key, double cx, double cy, float scale, double press, double alpha) {
		if (alpha <= 0.01) return;
		g.pose().pushMatrix();
		g.pose().translate((float) cx, (float) cy);
		g.pose().scale(scale, scale);
		int down = (int) Math.round(press * 2);
		g.fill(-10, -8, 10, 12, argb(alpha, 0x15171C));
		g.fill(-10, -10 + down, 10, 9 + down, argb(alpha, 0x2B2F38));
		g.fill(-8, -8 + down, 8, 7 + down, argb(alpha, 0x353A45));
		int glow = press > 0.5 ? 0xFFFFFF : 0xC8D0DC;
		g.text(font, key, -font.width(key) / 2, -4 + down, argb(alpha, glow), false);
		g.pose().popMatrix();
	}
}
