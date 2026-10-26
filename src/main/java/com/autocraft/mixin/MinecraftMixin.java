package com.autocraft.mixin;

import com.autocraft.Bot;
import com.autocraft.cinema.Recorder;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class MinecraftMixin {
	/**
	 * Vanilla calls continueAttack(false) every tick the attack key is not physically held, which aborts
	 * block breaking. While the bot is mining it drives MultiPlayerGameMode itself, so skip vanilla's reset.
	 */
	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void autocraft$keepBotMining(boolean down, CallbackInfo ci) {
		if (Bot.get().controls().isMining()) {
			ci.cancel();
		}
	}

	/** A recording film captures each frame once it's fully drawn, before it goes to the screen. */
	@Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render()V", shift = At.Shift.AFTER))
	private void autocraft$captureFilmFrame(boolean advanceGameTime, CallbackInfo ci) {
		Recorder.afterRender();
	}
}
