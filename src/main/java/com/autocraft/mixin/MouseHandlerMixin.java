package com.autocraft.mixin;

import com.autocraft.Bot;
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

	/** Once per frame, before the camera is set up: move the bot's view along its current turn. */
	@Inject(method = "handleAccumulatedMovement", at = @At("HEAD"))
	private void autocraft$smoothAim(CallbackInfo ci) {
		Bot.get().controls().frame();
	}
}
