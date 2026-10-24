package com.autocraft.behavior;

import com.autocraft.Bot;
import com.autocraft.Chat;
import com.autocraft.craft.Stations;
import com.autocraft.goal.Job;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jspecify.annotations.Nullable;

/**
 * Job done: store everything at the base (if one is set), report, and log off. If the base is in the
 * other dimension, step aside so the portal behavior brings the bot home first.
 */
public final class FinishBehavior implements Behavior {
	private final Stations.Cleanup cleanup = new Stations.Cleanup();
	private @Nullable BaseDeposit deposit;
	private boolean deposited;

	@Override
	public String name() {
		return "finish";
	}

	@Override
	public void reset() {
		deposit = null;
		deposited = false;
		cleanup.reset();
	}

	@Override
	public boolean tick(Bot bot) {
		Job job = bot.job();
		if (job == null || !job.isComplete(bot.player())) return false;
		if (cleanup.tick(bot)) return true; // don't leave tables and furnaces behind
		if (bot.base() != null && !deposited) {
			if (bot.baseDimension() != bot.dimension()) return false;
			if (deposit == null) deposit = new BaseDeposit(bot.base());
			if (deposit.tick(bot) == BaseDeposit.Status.RUNNING) return true;
			deposited = true;
			bot.nav().stop();
		}
		Chat.info("Job complete: " + job.progress(bot.player()));
		bot.stop("job complete");
		if (bot.logoffWhenDone()) {
			Minecraft mc = bot.mc();
			Chat.info("Logging off.");
			mc.schedule(() -> mc.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE)); // outside the tick
		}
		return true;
	}
}
