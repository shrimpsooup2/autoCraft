package com.autocraft.mixin;

import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.component.ResolvableProfile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The film's puppets wear player skins and hide the "NPC" label (both private setters). */
@Mixin(Mannequin.class)
public interface MannequinAccessor {
	@Invoker("setProfile")
	void autocraft$setProfile(ResolvableProfile profile);

	@Invoker("setHideDescription")
	void autocraft$setHideDescription(boolean hide);
}
