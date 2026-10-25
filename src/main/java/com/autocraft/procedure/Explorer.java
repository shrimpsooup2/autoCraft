package com.autocraft.procedure;

import com.autocraft.Bot;
import com.autocraft.path.Goal;
import com.autocraft.path.Navigator;
import com.autocraft.world.BiomeFinder;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import org.jspecify.annotations.Nullable;

/** Walks exploration legs for procedures looking for something (bee nests, sheep): toward hinted biomes, else straight on. */
final class Explorer {
	private static final int LEG = 64;

	private final Set<ResourceKey<Biome>> biomes;
	private @Nullable Goal leg;
	private @Nullable Direction heading;
	private int legs;

	Explorer(Set<ResourceKey<Biome>> biomes) {
		this.biomes = biomes;
	}

	int legs() {
		return legs;
	}

	void tick(Bot bot, String what) {
		BlockPos feet = Navigator.feet(bot.player());
		if (heading == null) heading = Direction.fromYRot(bot.player().getYRot());
		if (leg == null) {
			BlockPos biome = biomes.isEmpty() || BiomeFinder.in(bot.level(), feet, biomes) ? null
					: BiomeFinder.nearest(bot.level(), feet, biomes, 8, true, c -> false);
			leg = biome != null ? new Goal.XZ(biome.getX(), biome.getZ())
					: new Goal.XZ(feet.getX() + heading.getStepX() * LEG, feet.getZ() + heading.getStepZ() * LEG);
		}
		bot.setStatus("looking for " + what);
		if (leg instanceof Goal.XZ(int x, int z) && bot.startFlight(new BlockPos(x, feet.getY(), z))) return;
		bot.nav().setGoal(leg);
		Navigator.Status s = bot.nav().tick();
		if (s == Navigator.Status.ARRIVED || s == Navigator.Status.FAILED) {
			if (s == Navigator.Status.FAILED) heading = heading.getClockWise();
			leg = null;
			legs++;
			bot.nav().stop();
		}
	}
}
