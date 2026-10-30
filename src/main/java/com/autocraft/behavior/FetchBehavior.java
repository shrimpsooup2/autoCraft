package com.autocraft.behavior;

import com.autocraft.Bot;
import com.autocraft.Chat;
import com.autocraft.build.BaseWithdraw;
import com.autocraft.control.InvUtil;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A task-tree "get from base" step: walk to the base and take the items out of its chests. Another dimension is
 * reached by the portal behavior first. Whatever the chests had, the step then counts as done (a short count is
 * reported; a later collect task gathers the rest) and ends like any task ({@link FinishBehavior}).
 */
public final class FetchBehavior implements Behavior {
	private @Nullable BaseWithdraw withdraw;

	@Override
	public String name() {
		return "fetch";
	}

	@Override
	public void reset() {
		withdraw = null;
	}

	@Override
	public boolean tick(Bot bot) {
		Bot.FetchOrder o = bot.fetchOrder();
		if (o == null || o.dim() != bot.dimension() || bot.finishing() != null) return false;
		if (bot.tripPickaxe() != null) return false; // gear makes a pickaxe first (Bot.considerTripPickaxe)
		int have = InvUtil.count(bot.player(), s -> s.is(o.item()));
		if (withdraw == null) {
			if (have >= o.amount()) return done(bot, o, have);
			withdraw = new BaseWithdraw(o.base(), Map.of(o.item(), o.amount() - have));
		}
		if (withdraw.tick(bot) == BaseWithdraw.Status.RUNNING) return true;
		withdraw = null;
		return done(bot, o, InvUtil.count(bot.player(), s -> s.is(o.item())));
	}

	private boolean done(Bot bot, Bot.FetchOrder o, int have) {
		if (have < o.amount()) {
			Chat.warn("The base only had " + have + "/" + o.amount() + " " + o.item().getDefaultInstance().getHoverName().getString() + ".");
		}
		bot.goalMet(Bot.FETCH_COMPLETE);
		return true;
	}
}
