package com.autocraft.behavior;

import com.autocraft.Bot;
import com.autocraft.Chat;
import com.autocraft.Settings;
import com.autocraft.build.BaseWithdraw;
import com.autocraft.control.InvUtil;
import com.autocraft.craft.Gatherable;
import com.autocraft.goal.Requirement;
import com.autocraft.goal.Requirement.Dim;
import com.autocraft.path.Navigator;
import com.autocraft.world.ChestMemory;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/**
 * Materials the base chests hold (as last seen, or "materials are at base" for chests not looked into yet):
 * fetch them when the round trip is quicker than gathering them. One trip per need.
 */
final class BaseFetch {
	private static final double WALK_SPEED = 4.3;
	private static final double CHEST_SECONDS = 10;
	/** Planner effort units to seconds per item, roughly (cobblestone ~2 s, a hunted drop ~8 s). */
	private static final double SECONDS_PER_EFFORT = 0.25;

	private @Nullable BaseWithdraw withdraw;
	private final Set<String> tried = new HashSet<>();

	void reset() {
		withdraw = null;
		tried.clear();
	}

	/** True while fetching {@code need} from the base. */
	boolean tick(Bot bot, Requirement need) {
		if (withdraw != null) {
			if (withdraw.tick(bot) == BaseWithdraw.Status.RUNNING) return true;
			withdraw = null;
			return false;
		}
		BlockPos base = bot.base();
		if (base == null || bot.baseDimension() != bot.dimension() || tried.contains(need.name())) return false;
		LocalPlayer p = bot.player();
		int missing = need.amount() - InvUtil.count(p, need.counts());
		if (missing <= 0) return false;
		Dim dim = bot.dimension();
		int stock = ChestMemory.count(base, BaseWithdraw.BASE_RADIUS, dim, need.counts());
		boolean unseen = Settings.get().materialsAtBase && !ChestMemory.surveyed(base, BaseWithdraw.BASE_RADIUS, dim);
		if (stock <= 0 && !unseen) return false;
		int fetchable = unseen ? missing : Math.min(missing, stock);
		double trip = 2 * Math.sqrt(base.distSqr(Navigator.feet(p))) / WALK_SPEED + CHEST_SECONDS;
		double gather = fetchable * Gatherable.cost(need, dim) * SECONDS_PER_EFFORT;
		if (trip >= gather) return false;
		tried.add(need.name());
		Chat.info("Fetching " + need.name() + " from the base chests (~" + Math.round(trip) + "s) instead of gathering it (~" + Math.round(gather) + "s).");
		withdraw = new BaseWithdraw(base, need.counts(), missing);
		return true;
	}
}
