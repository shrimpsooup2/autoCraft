package com.autocraft.build;

import com.autocraft.Bot;
import com.autocraft.control.InvUtil;
import com.autocraft.schematic.Schematic;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Turns the user's {@link MaterialSettings} into a concrete {@link Substitution} for one schematic, looking at what
 * grows nearby: in a birch forest an oak design is built from birch; in a desert with no trees around, structural
 * wood becomes sandstone (doors, chests and other functional wood stay wood) unless the user said otherwise.
 */
public final class MaterialAdvisor {
	private static final int TREE_SCAN_CHUNKS = 6;
	private static final int TREE_SCAN_MAX = 256;

	private MaterialAdvisor() {}

	public static Substitution resolve(MaterialSettings settings, Schematic schematic) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		ClientLevel level = mc.level;
		List<BlockState> states = new ArrayList<>(schematic.cells().size());
		for (Schematic.Cell c : schematic.cells()) states.add(c.state());
		List<String> used = Substitution.woodsUsed(states);

		Map<String, String> woods = new LinkedHashMap<>();
		Substitution.StandIn standIn = null;
		List<String> why = new ArrayList<>();
		if (!used.isEmpty() && !settings.wood.equals(MaterialSettings.EXACT)) {
			if (settings.wood.equals(MaterialSettings.AUTO)) {
				Map<String, Integer> local = p == null || level == null ? Map.of() : localWoods(p, level);
				String dominant = local.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
				if (dominant == null) {
					if (settings.standIn.equals(MaterialSettings.AUTO) && p != null && level != null) {
						standIn = biomeStandIn(level, p.blockPosition());
						why.add("no trees nearby: " + standIn.label().toLowerCase() + " for planks, logs, stairs, slabs and fences");
					}
				} else {
					for (String w : used) {
						if (!local.containsKey(w)) woods.put(w, dominant);
					}
					if (!woods.isEmpty()) why.add(String.join(", ", woods.keySet()) + " → " + dominant + " (" + dominant + " grows here)");
				}
			} else {
				for (String w : used) {
					if (!w.equals(settings.wood)) woods.put(w, settings.wood);
				}
				if (!woods.isEmpty()) why.add("all wood → " + settings.wood);
			}
		}
		if (!settings.standIn.equals(MaterialSettings.AUTO) && !settings.standIn.equals(MaterialSettings.OFF)) {
			standIn = Substitution.StandIn.valueOf(settings.standIn);
			why.add(standIn.label().toLowerCase() + " instead of structural wood");
		}
		Block filler = settings.fillInterior ? pickFiller(settings.fillers, p) : null;
		if (filler != null) why.add("hidden blocks: " + filler.getName().getString().toLowerCase());
		if (!settings.overrides.isEmpty()) why.add(settings.overrides.size() + " replacement" + (settings.overrides.size() == 1 ? "" : "s"));
		String reason = why.isEmpty() ? "as designed" : String.join("; ", why);
		return new Substitution(woods, standIn, new LinkedHashMap<>(settings.overrides), filler, reason);
	}

	/** Logs growing within a few chunks plus wood carried, by wood type. */
	private static Map<String, Integer> localWoods(LocalPlayer p, ClientLevel level) {
		Map<String, Integer> counts = new HashMap<>();
		List<BlockPos> logs = Bot.get().scanner().findBlocks(s -> s.is(BlockTags.LOGS), TREE_SCAN_CHUNKS, TREE_SCAN_MAX);
		for (BlockPos pos : logs) {
			Substitution.WoodPart part = Substitution.woodPart(level.getBlockState(pos).getBlock());
			if (part != null && (level.dimension() == Level.NETHER) == isNether(part.wood())) counts.merge(part.wood(), 1, Integer::sum);
		}
		for (int i = 0; i < InvUtil.MAIN_SIZE; i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (!(s.is(ItemTags.LOGS) || s.is(ItemTags.PLANKS)) || !(s.getItem() instanceof BlockItem bi)) continue;
			Substitution.WoodPart part = Substitution.woodPart(bi.getBlock());
			if (part != null) counts.merge(part.wood(), s.getCount(), Integer::sum);
		}
		return counts;
	}

	private static boolean isNether(String wood) {
		return wood.equals("crimson") || wood.equals("warped");
	}

	/** Sandstone in deserts and on beaches, red sandstone in badlands, blackstone in the Nether, else cobblestone. */
	private static Substitution.StandIn biomeStandIn(ClientLevel level, BlockPos pos) {
		if (level.dimension() == Level.NETHER) return Substitution.StandIn.BLACKSTONE;
		Holder<Biome> biome = level.getBiome(pos);
		if (biome.is(BiomeTags.IS_BADLANDS)) return Substitution.StandIn.RED_SANDSTONE;
		if (biome.is(Biomes.DESERT) || biome.is(BiomeTags.IS_BEACH)) return Substitution.StandIn.SANDSTONE;
		return Substitution.StandIn.COBBLESTONE;
	}

	/** The filler the player carries most of, else the first one listed. */
	private static @Nullable Block pickFiller(List<Block> fillers, @Nullable LocalPlayer p) {
		if (fillers.isEmpty()) return null;
		Block best = fillers.getFirst();
		int bestCount = 0;
		for (Block b : fillers) {
			int n = p == null ? 0 : InvUtil.count(p, s -> s.is(b.asItem()));
			if (n > bestCount) {
				best = b;
				bestCount = n;
			}
		}
		return best;
	}
}
