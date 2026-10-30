package com.autocraft.goal;

import com.autocraft.goal.Requirement.Dim;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/**
 * Where a go-to task heads: coordinates (no {@code y}: anywhere on that column the bot can stand), a base (its
 * position when the task started), or the nearest structure of a kind ({@code name}: a {@link com.autocraft.loot.LootSites}
 * site, looked for when the task runs). The dimension is fixed when the task starts.
 */
public record Destination(Kind kind, int x, @Nullable Integer y, int z, Dim dim, String name) {
	public enum Kind { COORDS, BASE, STRUCTURE }

	public static Destination coords(int x, @Nullable Integer y, int z, Dim dim) {
		return new Destination(Kind.COORDS, x, y, z, dim, "");
	}

	public static Destination base(String name, BlockPos pos, Dim dim) {
		return new Destination(Kind.BASE, pos.getX(), pos.getY(), pos.getZ(), dim, name);
	}

	public static Destination structure(String site, Dim dim) {
		return new Destination(Kind.STRUCTURE, 0, null, 0, dim, site);
	}

	/** "120 64 -300 (nether)", "base "home"", "the nearest village". */
	public String describe() {
		return switch (kind) {
			case COORDS -> x + (y == null ? "" : " " + y) + " " + z + " (" + dim.name().toLowerCase() + ")";
			case BASE -> "base \"" + name + "\"";
			case STRUCTURE -> "the nearest " + name;
		};
	}
}
