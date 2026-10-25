package com.autocraft.procedure;

import com.autocraft.Bot;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.ClipBlockStateContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SculkShriekerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Staying unheard in an ancient city. What the game counts: sneaking hides footsteps, landings and swimming, wool
 * hides itself (placing/breaking wool makes no vibration) and blocks a vibration's line to a sensor; opening a chest
 * is always a vibration, and stepping on a sensor or shrieker always sets it off. So: sneak, never step on sculk,
 * and before opening a chest put wool between it and every sculk sensor that could hear it — or leave that chest.
 * Shrieks are counted (the fourth summons the warden; the count drops by one every ten minutes), and a warden in
 * sight means leave now.
 */
final class SculkGuard {
	/** Sculk sensors hear 8 blocks (calibrated ones 16). */
	private static final int SENSOR_RANGE = 8;
	private static final int CALIBRATED_RANGE = 16;
	/** Leave after this many shrieks (the fourth would summon the warden). */
	private static final int MAX_SHRIEKS = 3;
	private static final double WARDEN_RANGE = 48;

	private final Set<Long> shrieking = new HashSet<>();
	private int shrieks;

	/** Sensors that would hear a chest opening at {@code source}: in range, nothing woollen in between. */
	static List<BlockPos> listeners(Bot bot, BlockPos source) {
		List<BlockPos> out = new ArrayList<>();
		ClientLevel level = bot.level();
		for (BlockPos s : bot.scanner().findBlocks(st -> st.is(Blocks.SCULK_SENSOR) || st.is(Blocks.CALIBRATED_SCULK_SENSOR), 2, 256)) {
			int range = level.getBlockState(s).is(Blocks.CALIBRATED_SCULK_SENSOR) ? CALIBRATED_RANGE : SENSOR_RANGE;
			if (s.distSqr(source) > (double) range * range) continue;
			if (!occluded(level, source, s)) out.add(s.immutable());
		}
		return out;
	}

	/** True when a wool block sits on the line between the two blocks (as the game checks it). */
	static boolean occluded(ClientLevel level, BlockPos from, BlockPos to) {
		Vec3 a = Vec3.atCenterOf(from), b = Vec3.atCenterOf(to);
		return level.isBlockInLine(new ClipBlockStateContext(a, b, s -> s.is(BlockTags.OCCLUDES_VIBRATION_SIGNALS))).getType() == HitResult.Type.BLOCK;
	}

	/** Empty cells on the line from {@code source} to {@code sensor} (nearest the source first) where wool would block the vibration. */
	static List<BlockPos> woolSpots(ClientLevel level, BlockPos source, BlockPos sensor) {
		List<BlockPos> out = new ArrayList<>();
		Vec3 a = Vec3.atCenterOf(source), b = Vec3.atCenterOf(sensor);
		for (double t = 0.05; t < 1; t += 0.05) {
			BlockPos cell = BlockPos.containing(a.lerp(b, t));
			if (cell.equals(source) || cell.equals(sensor) || out.contains(cell)) continue;
			BlockState st = level.getBlockState(cell);
			if (st.canBeReplaced() && st.getFluidState().isEmpty()) out.add(cell);
		}
		return out;
	}

	/** Watch the shriekers around; true when it's time to leave (too many shrieks, or the warden is here). */
	boolean danger(Bot bot) {
		for (BlockPos s : bot.scanner().findBlocksCached("shriekers", st -> st.is(Blocks.SCULK_SHRIEKER), 2, 64, 40)) {
			boolean now = bot.level().getBlockState(s).getValue(SculkShriekerBlock.SHRIEKING);
			if (now && shrieking.add(s.asLong())) shrieks++;
			else if (!now) shrieking.remove(s.asLong());
		}
		return shrieks >= MAX_SHRIEKS || wardenNear(bot);
	}

	static boolean wardenNear(Bot bot) {
		return !bot.scanner().entities(WARDEN_RANGE, e -> e.getType() == EntityTypes.WARDEN && e.isAlive()).isEmpty();
	}

	int shrieks() {
		return shrieks;
	}

	/** After waiting it out: the warning level has dropped. */
	void calmDown() {
		shrieks = 0;
		shrieking.clear();
	}
}
