package com.autocraft.mixin;

import com.autocraft.Bot;
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
}
