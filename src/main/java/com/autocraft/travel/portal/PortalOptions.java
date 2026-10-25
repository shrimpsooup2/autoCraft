package com.autocraft.travel.portal;

import com.autocraft.Bot;
import com.autocraft.control.InvUtil;
import com.autocraft.path.Navigator;
import com.autocraft.world.BlockUtil;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;

/**
 * Rough time costs, in seconds, of the ways through to the Nether: walk to a portal we know or see, cast a
 * bucket portal at a lava pool (no diamond pickaxe; buckets cost iron), or mine/cast 10 obsidian and build
 * (needs a diamond pickaxe, and obsidian mines slowly). The estimates only need to rank the options sensibly.
 */
public final class PortalOptions {
	public enum Kind { EXISTING, BUCKET_PORTAL, OBSIDIAN }

	public record Option(Kind kind, double seconds) {
		@Override
		public String toString() {
			return switch (kind) {
				case EXISTING -> "walk to the portal";
				case BUCKET_PORTAL -> "bucket portal";
				case OBSIDIAN -> "mined obsidian";
			} + " ~" + Math.round(seconds) + "s";
		}
	}

	private static final double WALK_SPEED = 4.3;
	private static final double OBSIDIAN_MINE_SECONDS = 9.4;
	private static final double FIND_DIAMONDS_SECONDS = 900;
	private static final double IRON_INGOT_SECONDS = 30;
	private static final double SEARCH_SECONDS = 600;
	private static final double CAST_SECONDS = 4;
	private static final double REFILL_TRIP_SECONDS = 20;
	private static final double BUILD_SECONDS = 30;
	private static final int OBSIDIAN = 10;
	private static final int BUCKETS = CastPortalBuilder.LAVA_BUCKETS + 1;

	private PortalOptions() {}

	/** Every option with its estimate, cheapest first; {@code portal} is a known/visible portal or null. */
	public static List<Option> evaluate(Bot bot, @Nullable BlockPos portal) {
		LocalPlayer p = bot.player();
		BlockPos feet = Navigator.feet(p);
		List<Option> out = new ArrayList<>();
		if (portal != null) out.add(new Option(Kind.EXISTING, Math.sqrt(portal.distSqr(feet)) / WALK_SPEED + 5));

		List<BlockPos> lava = bot.scanner().findBlocks(s -> BlockUtil.isLava(s) && s.getFluidState().isSource(), 5, 16);
		double toLava = lava.isEmpty() ? SEARCH_SECONDS : Math.sqrt(nearest(lava, feet).distSqr(feet)) / WALK_SPEED;

		// bucket portal: 7 buckets (6 lava + 1 water) at 3 iron each, a lava pool, ten casts and a couple of refills
		int buckets = InvUtil.count(p, s -> s.is(Items.BUCKET) || s.is(Items.LAVA_BUCKET) || s.is(Items.WATER_BUCKET));
		int ironNeeded = Math.max(0, BUCKETS - buckets) * 3 - InvUtil.count(p, s -> s.is(Items.IRON_INGOT) || s.is(Items.RAW_IRON));
		double bucketSeconds = Math.max(0, ironNeeded) * IRON_INGOT_SECONDS + (buckets < BUCKETS ? 10 : 0);
		double refills = lava.size() >= OBSIDIAN ? 1 : 3; // a small pool runs dry sooner
		out.add(new Option(Kind.BUCKET_PORTAL, bucketSeconds + toLava + OBSIDIAN * CAST_SECONDS + refills * REFILL_TRIP_SECONDS + BUILD_SECONDS));

		// mined obsidian: diamond pickaxe, then natural obsidian (or cast it at a lava pool) mined block by block
		boolean pickaxe = InvUtil.find(p, s -> s.is(Items.DIAMOND_PICKAXE) || s.is(Items.NETHERITE_PICKAXE)) >= 0;
		double pickSeconds = pickaxe ? 0 : InvUtil.count(p, s -> s.is(Items.DIAMOND)) >= 3 ? 15 : FIND_DIAMONDS_SECONDS;
		int need = Math.max(0, OBSIDIAN - InvUtil.count(p, s -> s.is(Items.OBSIDIAN)));
		List<BlockPos> natural = bot.scanner().findBlocks(s -> s.is(Blocks.OBSIDIAN), 5, OBSIDIAN);
		double source = need == 0 ? 0
				: natural.size() >= need ? Math.sqrt(nearest(natural, feet).distSqr(feet)) / WALK_SPEED
				: !lava.isEmpty() ? toLava + need * CAST_SECONDS : SEARCH_SECONDS;
		out.add(new Option(Kind.OBSIDIAN, pickSeconds + source + need * OBSIDIAN_MINE_SECONDS + BUILD_SECONDS));

		out.sort(Comparator.comparingDouble(Option::seconds));
		return out;
	}

	public static String describe(List<Option> options) {
		return options.stream().map(Option::toString).collect(Collectors.joining(" · "));
	}

	private static BlockPos nearest(List<BlockPos> list, BlockPos from) {
		return list.stream().min(Comparator.comparingDouble(b -> b.distSqr(from))).orElseThrow();
	}
}
