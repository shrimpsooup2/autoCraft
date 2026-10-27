package com.autocraft.cinema;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.phys.Vec3;

/**
 * The end, at sunset: the finished base, the bot walks to its chest and opens it on 32 diamonds. The camera rises
 * to the logo building itself against the sunset. Back down, the bot pillars up into view, turns to look at you,
 * its light flashes, and it logs off — the job's done. "Press K in game" and where to get it.
 */
final class EndShot extends Shot {
	private static final int GREEN = 0x5EE36A;
	/** On the music: the lid, diamonds, the logo landing on bar 85, a block a beat up the pillar, the logoff on the last hit (bar 88). */
	private final double OPEN = bar(81.5), CLOSE = bar(83), LOGO_T0 = Logo.flyFrom(bar(85)), PILLAR = bar(85.25), JUMP = Score.BEAT, LOOK = bar(87),
			OFF = bar(88);
	private static final int HEIGHT = 6;

	private Blueprint plan;
	private int yard;
	private BlockPos chest, pillar;
	private Actor bot;
	private Anim.Track walk;
	private Logo logo;

	EndShot() {
		super("End", null, 8, 12300);
	}

	@Override
	BlockPos offset() {
		return new BlockPos(-220, 0, 40);   // its own base, well away from everything else
	}

	@Override
	void build(ServerLevel level, BlockPos at) {
		plan = Blueprint.load();
		int r = plan.reach();
		int y = Stage.pad(level, at.getX(), at.getZ(), r + 14);
		origin = new BlockPos(at.getX(), y + 1, at.getZ());
		BlockPos corner = plan.corner(new BlockPos(at.getX(), y, at.getZ()));
		plan.build(level, corner);
		yard = corner.getZ() + plan.size.getZ() - origin.getZ() + 4;
		Stage.set(level, b(1, 0, yard), Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.WEST));
		for (int[] p : new int[][] {{-6, yard - 2}, {5, yard + 3}}) {
			Stage.set(level, b(p[0], 0, p[1]), Blocks.OAK_FENCE.defaultBlockState());
			Stage.set(level, b(p[0], 1, p[1]), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, false));
		}
	}

	@Override
	void start() {
		chest = b(1, 0, yard);
		pillar = b(-3, 0, yard + 2);
		Vec3 stand = v(0.5, 0, yard + 0.5), q = Vec3.atBottomCenterOf(pillar);
		walk = new Anim.Track().at(0, v(-1.5, 0, yard + 1.5)).to(stand, 3).hold(CLOSE + 0.4).to(q, 3);
		bot = add(Actor.me(level, walk.pos(0), -90));
		bot.light = GREEN;
		logo = new Logo(Logo.above(level, new BlockPos(origin.getX() - 70, origin.getY() + 32, origin.getZ() + yard), Direction.EAST, 6), Direction.EAST);
		Vec3 sky = Vec3.atCenterOf(logo.centre);
		double up = sky.y - origin.getY();
		Vec3 c = q.add(6.5, 5.5, 3.5);
		// from up here both the bot on its pillar and the logo behind it are in the picture
		Vec3 aimDir = sky.subtract(c).normalize().scale(0.62).add(q.add(0, HEIGHT + 0.5, 0).subtract(c).normalize().scale(0.38)).normalize();
		Vec3 aim = c.add(aimDir.scale(30));
		cam.key(0, v(4.6, 2.8, yard + 4.8), v(0.8, 1.2, yard + 0.5), 58)
				.key(CLOSE, v(3.4, 2.6, yard + 3.2), v(0.8, 1.6, yard + 0.5), 56)
				.key(bar(84.2), v(4, up - 10, yard + 8), sky.add(0, -6, 0), 64)
				.key(bar(85.1), v(2, up - 7, yard + 6), sky.add(0, -8, 0))
				.key(PILLAR + 0.4, c, aim, 62)
				.key(OFF + 1.0, c.add(-1.0, 0.3, -0.5), aim)
				.key(duration, c.add(-1.6, 0.6, -0.9), aim.add(0, 1, 0));
	}

	@Override
	void tick(double t) {
		double top = PILLAR + HEIGHT * JUMP;
		if (t < PILLAR) {
			bot.follow(walk, t);
			bot.look(t >= OPEN - 0.6 && t < CLOSE + 0.4 ? Vec3.atCenterOf(chest) : null);
		}
		if (at(t, OPEN)) {
			bot.swing();
			level.blockEvent(chest, Blocks.CHEST, 1, 1);
			level.playLocalSound(chest, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.6f, 1, false);
		}
		if (at(t, CLOSE)) {
			level.blockEvent(chest, Blocks.CHEST, 1, 0);
			level.playLocalSound(chest, SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 0.6f, 1, false);
		}
		if (at(t, Logo.landed(LOGO_T0))) logo.place(this);
		// pillar up: jump, put a block under, land on it
		if (at(t, PILLAR - 0.3)) bot.hold(Items.COBBLESTONE);
		bot.free = t >= PILLAR;   // jumping up the pillar, then standing on top of it
		if (t >= PILLAR && t < top + 0.2) {
			int k = Math.min(HEIGHT - 1, (int) ((t - PILLAR) / JUMP));
			double u = Anim.clamp01((t - PILLAR - k * JUMP) / JUMP);
			double y = t >= top ? HEIGHT : k + u + 0.6 * Math.sin(Math.PI * u);
			bot.moveTo(Vec3.atBottomCenterOf(pillar).add(0, y, 0)).look(Vec3.atCenterOf(pillar.above(k - 1)));
		}
		for (int k = 0; k < HEIGHT; k++) {
			if (at(t, PILLAR + k * JUMP + JUMP * 0.45)) {
				bot.swing();
				put(pillar.above(k), Blocks.COBBLESTONE.defaultBlockState());
			}
		}
		if (at(t, top + 0.2)) bot.hold(Items.AIR);
		// look at the camera, the light flashes twice, log off
		if (t >= LOOK && !bot.entity.isRemoved()) bot.look(cam.at(t).pos());
		bot.lightFlash = Math.max(Anim.window(t, LOOK + 0.6, LOOK + 0.7, LOOK + 0.8, LOOK + 1.1), Anim.window(t, LOOK + 1.2, LOOK + 1.3, LOOK + 1.4, LOOK + 1.7));
		if (at(t, OFF)) bot.poof();
	}

	@Override
	void end() {
		if (level != null && pillar != null) {   // the next take starts without the pillar or the logo
			for (int k = 0; k < HEIGHT; k++) place(pillar.above(k), Blocks.AIR.defaultBlockState());
			logo.remove(this);
		}
		super.end();
	}

	@Override
	void draw(Fx fx, double t) {
		// 32 diamonds burst up out of the chest, wheel round over it, sink back
		Vec3 lid = Vec3.atCenterOf(chest).add(0, 0.5, 0);
		if (t >= OPEN + 0.2 && t < CLOSE + 0.7) {
			for (int i = 0; i < 32; i++) {
				double t0 = OPEN + 0.2 + i * 0.025;
				if (t < t0) continue;
				double a = i * Math.PI * 2 / 32 + (t - OPEN) * 0.9;
				Vec3 ring = lid.add(Math.cos(a) * 1.1, 1.3 + 0.12 * Math.sin(t * 3 + i), Math.sin(a) * 1.1);
				double rise = Anim.ramp(t, t0, t0 + 0.6), sink = Anim.ramp(t, CLOSE - 0.2, CLOSE + 0.5);
				Vec3 p = Anim.lerp(Anim.lerp(lid, ring, rise), lid, sink);
				fx.item(p, new ItemStack(Items.DIAMOND), 0.2 * (1 - sink * 0.6), (float) (t * 90 + i * 11), false);
			}
			double label = Anim.window(t, OPEN + 1.2, OPEN + 1.6, CLOSE - 0.3, CLOSE);
			if (label > 0) {
				fx.text(lid.add(0, 2.35, 0), Component.literal("32 / 32 Diamond ✔"), 0.012f, Fx.argb((int) (255 * label), GREEN), 0, true, 0, true);
				fx.ring(lid.add(0, 1.0, 0), 1.25, 0.04, Fx.argb((int) (200 * label), GREEN), 1);
			}
		}
		logo.draw(fx, t, LOGO_T0);
		double landed = Logo.landed(LOGO_T0);
		logo.title(fx, t, "autoCraft", landed + 0.2, "Tell it what you want.", landed + 0.9);
		// where the bot stood: a glow, fading
		double gone = Anim.window(t, OFF, OFF + 0.1, OFF + 0.4, OFF + 1.4);
		if (gone > 0) fx.spark(Vec3.atBottomCenterOf(pillar).add(0, HEIGHT + 1.0, 0), 2.4, Fx.argb((int) (160 * gone), GREEN));
	}

	@Override
	void hud(FilmHud hud, double t) {
		String name = mc().player != null ? mc().player.getGameProfile().name() : "you";
		List<Component> lines = List.of(FilmHud.ac("Stopped: job complete"), FilmHud.ac("Logging off."), FilmHud.notice(name + " left the game"));
		double[] times = {OPEN + 1.2, OFF - 0.2, OFF + 0.4};
		hud.chat(FilmHud.upTo(t, times, lines), Anim.window(t, OPEN + 1.0, OPEN + 1.2, duration - 4, duration - 3));
		hud.title("Press K in game", 0.76, 2.0f, 0xFFFFFF, Anim.ramp(t, OFF + 1.6, OFF + 2.6));
		hud.title("modrinth.com/mod/autoCraft_", 0.84, 1.2f, 0xD8DEE6, Anim.ramp(t, OFF + 2.2, OFF + 3.2));
		hud.title(Score.CREDIT, 0.92, 0.8f, 0xAAB2BC, Anim.ramp(t, OFF + 2.8, OFF + 3.8));
		hud.fade(Anim.ramp(t, duration - 2.2, duration));
	}
}
