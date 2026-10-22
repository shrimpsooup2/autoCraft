package com.autocraft.behavior;

import com.autocraft.Bot;
import com.autocraft.Chat;
import com.autocraft.control.InvUtil;
import com.autocraft.craft.Planner;
import com.autocraft.craft.Workshop;
import com.autocraft.goal.Job;
import com.autocraft.goal.Requirement;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;

/**
 * Job requirements that are crafted or smelted (torches, glass, pistons, iron ingots...): the recipe planner
 * picks the next craft or smelt; raw materials it lacks are posted as a {@link Bot.NeedOwner#MAKE} need for the
 * collector (which may mean a Nether trip, e.g. quartz for comparators).
 */
public final class MakeBehavior implements Behavior {
	/** While the collector gathers, re-plan this often (counts change as items come in). */
	private static final int REPLAN_TICKS = 20;

	private final Workshop workshop = new Workshop();
	private int sincePlan = REPLAN_TICKS;

	@Override
	public String name() {
		return "make";
	}

	@Override
	public void reset() {
		workshop.reset();
		sincePlan = REPLAN_TICKS;
	}

	@Override
	public boolean tick(Bot bot) {
		Job job = bot.job();
		if (job == null || bot.buildOrder() != null) return false;
		LocalPlayer p = bot.player();
		switch (workshop.tick(bot)) {
			case RUNNING -> {
				return true;
			}
			case DONE -> sincePlan = REPLAN_TICKS;
			case FAILED -> {
				Chat.warn("Crafting failed; retrying.");
				sincePlan = REPLAN_TICKS;
			}
			case IDLE -> {
			}
		}
		Requirement target = job.nextUnmet(p);
		if (target == null || !target.isCrafted()) {
			bot.setNeed(Bot.NeedOwner.MAKE, null);
			return workshop.packUp(bot);
		}
		if (bot.need(Bot.NeedOwner.GEAR) != null) return false; // tools first
		if (bot.need(Bot.NeedOwner.MAKE) != null && ++sincePlan < REPLAN_TICKS) return false;
		sincePlan = 0;

		Item item = target.product();
		int total = InvUtil.count(p, s -> s.is(item)) + job.missing(p, target);
		boolean table = !bot.scanner().findBlocks(s -> s.is(Blocks.CRAFTING_TABLE), 1, 1).isEmpty();
		boolean furnace = !bot.scanner().findBlocks(s -> s.is(Blocks.FURNACE), 1, 1).isEmpty();
		Planner planner = new Planner(p, bot.dimension(), table, furnace, bot::isJobItem);
		switch (planner.next(item, total)) {
			case Planner.Craft c -> {
				bot.setNeed(Bot.NeedOwner.MAKE, null);
				workshop.start(c);
				return true;
			}
			case Planner.Smelt s -> {
				bot.setNeed(Bot.NeedOwner.MAKE, null);
				workshop.start(s);
				return true;
			}
			case Planner.Gather g -> {
				if (workshop.packUp(bot)) return true;
				Requirement before = bot.need(Bot.NeedOwner.MAKE);
				if (before == null || !before.name().equals(g.requirement().name())) {
					Chat.info("Gathering " + g.requirement() + " to make " + target.name() + ".");
				}
				bot.setNeed(Bot.NeedOwner.MAKE, g.requirement());
				return false;
			}
			case Planner.Unavailable u -> {
				Chat.warn("Can't make " + target.name() + " (needs " + u.what() + "); skipping it. Put some in your inventory to include it.");
				job.skip(target);
				bot.setNeed(Bot.NeedOwner.MAKE, null);
				return true;
			}
			case Planner.Done d -> {
				bot.setNeed(Bot.NeedOwner.MAKE, null);
				return false;
			}
		}
	}
}
