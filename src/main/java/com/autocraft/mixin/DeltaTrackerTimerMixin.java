package com.autocraft.mixin;

import com.autocraft.cinema.Recorder;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** While a film records, the game clock advances by frames (1/fps each), not by the wall clock. */
@Mixin(DeltaTracker.Timer.class)
abstract class DeltaTrackerTimerMixin {
	@Shadow
	private float deltaTicks;

	@Shadow
	private float deltaTickResidual;

	@Shadow
	private long lastMs;

	@Inject(method = "advanceGameTime", at = @At("HEAD"), cancellable = true)
	private void autocraft$filmClock(long currentMs, CallbackInfoReturnable<Integer> cir) {
		Recorder.Step step;
		try {
			step = Recorder.step();
		} catch (RuntimeException e) {
			Recorder.broke(e);
			return;
		}
		if (step == null) return;
		deltaTicks = step.delta();
		deltaTickResidual = step.residual();
		lastMs = currentMs;
		cir.setReturnValue(step.ticks());
	}
}
