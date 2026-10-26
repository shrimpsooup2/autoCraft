package com.autocraft.mixin;

import com.autocraft.cinema.Film;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** While a film plays, the camera flies the shot's path instead of following the player. */
@Mixin(Camera.class)
abstract class CameraMixin {
	@Shadow
	protected abstract void setPosition(double x, double y, double z);

	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Inject(method = "alignWithEntity", at = @At("TAIL"))
	private void autocraft$filmCamera(float partialTicks, CallbackInfo ci) {
		Film.CameraPose pose = Film.camera(partialTicks);
		if (pose != null) {
			setPosition(pose.x(), pose.y(), pose.z());
			setRotation(pose.yaw(), pose.pitch());
		}
	}

	@Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
	private void autocraft$filmFov(float partialTicks, CallbackInfoReturnable<Float> cir) {
		Film.CameraPose pose = Film.camera(partialTicks);
		if (pose != null) cir.setReturnValue(pose.fov());
	}
}
