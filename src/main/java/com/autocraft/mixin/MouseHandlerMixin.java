package com.autocraft.mixin;

import com.autocraft.ui.Placements;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {
	/** Alt + wheel while placing a schematic nudges the placement instead of switching hotbar slots. */
	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void autocraft$nudgePlacement(long handle, double xoffset, double yoffset, CallbackInfo ci) {
		if (Placements.onScroll(yoffset)) ci.cancel();
	}
}
