package com.autocraft.mixin;

import net.minecraft.world.level.biome.BiomeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The world's hashed seed, which the server sends every client: tells apart worlds behind the same address. */
@Mixin(BiomeManager.class)
public interface BiomeManagerAccessor {
	@Accessor("biomeZoomSeed")
	long autocraft$biomeZoomSeed();
}
