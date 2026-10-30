package com.autocraft.procedure;

import com.autocraft.Bot;
import com.autocraft.path.Goal;
import com.autocraft.path.Navigator;
import com.autocraft.path.SurfaceClimb;
import com.autocraft.world.BiomeFinder;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import org.jspecify.annotations.Nullable;

/**
 * Walks exploration legs for procedures looking for something (bee nests, sheep, powder snow, surface structures):
 * up out of any cave first ({@link SurfaceClimb}), then toward hinted biomes, else straight on.
 */
final class Explorer {
	private static final int LEG = 64;
	/** Legs failing one after another: nowhere to go from here. */
	private static final int MAX_FAILURES = 8;

	private final Set<ResourceKey<Biome>> biomes;
	private @Nullable Goal leg;
	/** The current leg climbs out to the surface. */
	private boolean climbing;
	/** Straight up the own column failed (water or bedrock overhead): reach the surface level anywhere. */
	private boolean climbAnywhere;
	private @Nullable Direction heading;
	private int legs;
	private int failures;

	Explorer(Set<ResourceKey<Biome>> biomes) {
		this.biomes = biomes;
	}

	/** Legs walked to the end. Failed ones don't count: underground they failed a few seconds each and used up a
	 * powder snow search in eight minutes. */
	int legs() {
		return legs;
	}

	/** {@code maxLegs} walked without finding it, or nowhere left to go. */
	boolean exhausted(int maxLegs) {
		return legs >= maxLegs || failures >= MAX_FAILURES;
	}

	void tick(Bot bot, String what) {
		BlockPos feet = Navigator.feet(bot.player());
		if (heading == null) heading = Direction.fromYRot(bot.player().getYRot());
		if (leg == null) {
			climbing = SurfaceClimb.below(bot.level(), feet);
			if (climbing) {
				leg = SurfaceClimb.goal(bot.player(), bot.level(), feet, climbAnywhere);
			} else {
				climbAnywhere = false;
				BlockPos biome = biomes.isEmpty() || BiomeFinder.in(bot.level(), feet, biomes) ? null
						: BiomeFinder.nearest(bot.level(), feet, biomes, 8, true, c -> false);
				leg = biome != null ? new Goal.XZ(biome.getX(), biome.getZ())
						: new Goal.XZ(feet.getX() + heading.getStepX() * LEG, feet.getZ() + heading.getStepZ() * LEG);
			}
		}
		bot.setStatus((climbing ? "climbing to the surface to look for " : "looking for ") + what);
		if (leg instanceof Goal.XZ(int x, int z) && bot.startFlight(new BlockPos(x, feet.getY(), z))) return;
		bot.nav().setGoal(leg);
		Navigator.Status s = bot.nav().tick();
		if (s == Navigator.Status.ARRIVED || s == Navigator.Status.FAILED) {
			if (s == Navigator.Status.FAILED) {
				failures++;
				if (climbing) climbAnywhere = true;
				else heading = heading.getClockWise();
			} else {
				failures = 0;
				if (!climbing) legs++;
			}
			leg = null;
			bot.nav().stop();
		}
	}
}
