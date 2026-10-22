package com.autocraft.schematic;

import java.util.Map;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.jspecify.annotations.Nullable;

/** Block states from the text and NBT forms used by schematic files. Unknown blocks/properties are skipped. */
public final class BlockStates {
	private BlockStates() {}

	/** {@code minecraft:oak_stairs[facing=east,half=bottom]} (properties optional). Null for unknown blocks. */
	public static @Nullable BlockState parse(String text) {
		int bracket = text.indexOf('[');
		String id = bracket < 0 ? text : text.substring(0, bracket);
		BlockState state = block(id);
		if (state == null || bracket < 0) return state;
		String props = text.substring(bracket + 1, text.lastIndexOf(']'));
		for (String pair : props.split(",")) {
			int eq = pair.indexOf('=');
			if (eq > 0) state = with(state, pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
		}
		return state;
	}

	/** Litematica / structure palette entry: {@code {Name: "...", Properties: {...}}}. */
	public static @Nullable BlockState fromTag(CompoundTag tag) {
		BlockState state = block(tag.getStringOr("Name", tag.getStringOr("id", "")));
		if (state == null) return null;
		CompoundTag props = tag.getCompoundOrEmpty("Properties");
		for (String key : props.keySet()) {
			state = with(state, key, props.getStringOr(key, ""));
		}
		return state;
	}

	public static BlockState withProps(BlockState state, Map<String, String> props) {
		for (Map.Entry<String, String> e : props.entrySet()) state = with(state, e.getKey(), e.getValue());
		return state;
	}

	private static @Nullable BlockState block(String id) {
		Identifier rl = Identifier.tryParse(id);
		if (rl == null) return null;
		Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(rl);
		if (block.isEmpty() || block.get() == Blocks.AIR && !id.endsWith("air")) return null;
		return block.get().defaultBlockState();
	}

	private static BlockState with(BlockState state, String name, String value) {
		Property<?> prop = state.getBlock().getStateDefinition().getProperty(name);
		return prop == null ? state : set(state, prop, value);
	}

	private static <T extends Comparable<T>> BlockState set(BlockState state, Property<T> prop, String value) {
		return prop.getValue(value).map(v -> state.setValue(prop, v)).orElse(state);
	}
}
