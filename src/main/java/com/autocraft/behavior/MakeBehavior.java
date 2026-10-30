package com.autocraft.behavior;

import com.autocraft.Bot;
import com.autocraft.Chat;
import com.autocraft.control.InvUtil;
import com.autocraft.craft.Planner;
import com.autocraft.craft.Stations;
import com.autocraft.craft.Workshop;
import com.autocraft.goal.Job;
import com.autocraft.goal.Requirement;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;

/**
 * Job requirements that are crafted, smelted, hunted or made by a special procedure (torches, glass, iron ingots,
 * ender pearls, honey, oxidized copper...): the recipe planner picks the next step; raw materials it lacks are
 * posted as a {@link Bot.NeedOwner#MAKE} need for the collector (which may mean a Nether trip or a hunt).
 */
public final class MakeBehavior implements Behavior {
	/** While the collector gathers, re-plan this often (counts change as items come in). */
	private static final int REPLAN_TICKS = 20;

	private final Workshop workshop = new Workshop();
	private int sincePlan = REPLAN_TICKS;
	private @Nullable Requirement working;

	@Override
	public String name() {
		return "make";
	}

	@Override
	public void reset() {
		workshop.reset();
		sincePlan = REPLAN_TICKS;
		working = null;
	}

	@Override
	public boolean tick(Bot bot) {
		Job job = bot.job();
		if (job == null || bot.buildOrder() != null) return false;
		// tools first, then food (it may mean a trip home from the Nether) and a respawn bed's wool: a running procedure —
		// a bastion search, a trial chamber — waits for them. Gear waited only before a new plan: its stone pickaxe worn
		// out, a trial chamber search tunnelled on through deepslate by hand while gear waited for the logs for sticks
		if (bot.need(Bot.NeedOwner.GEAR) != null || bot.need(Bot.NeedOwner.FOOD) != null || bot.need(Bot.NeedOwner.BED) != null) return false;
		LocalPlayer p = bot.player();
		switch (workshop.tick(bot)) {
			case RUNNING -> {
				return true;
			}
			case TRAVEL -> {
				return false; // a procedure needs the other dimension: the portal behavior takes us
			}
			case DONE -> sincePlan = REPLAN_TICKS;
			case FAILED -> {
				Chat.warn("Making failed; retrying.");
				sincePlan = REPLAN_TICKS;
			}
			case WAITING -> {
				if (working != null) job.defer(working); // the world needs time: do the rest of the job first
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
		if (bot.need(Bot.NeedOwner.MAKE) != null && ++sincePlan < REPLAN_TICKS) return false;
		sincePlan = 0;

		Item item = target.product();
		int total = InvUtil.count(p, s -> s.is(item)) + job.missing(p, target);
		Planner planner = Stations.planner(bot, s -> s.is(item)); // only the product itself is off limits
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
			case Planner.Cut c -> {
				bot.setNeed(Bot.NeedOwner.MAKE, null);
				workshop.start(c);
				return true;
			}
			case Planner.Smith s -> {
				bot.setNeed(Bot.NeedOwner.MAKE, null);
				workshop.start(s);
				return true;
			}
			case Planner.Special sp -> {
				if (workshop.packUp(bot)) return true;
				bot.setNeed(Bot.NeedOwner.MAKE, null);
				workshop.start(sp);
				working = target;
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
