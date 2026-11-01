package com.lackothy.orchard.mixin;

import com.lackothy.orchard.mac.PowerLimit;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The Battery page's limits: the frame rate Minecraft paces itself to, after its own limit and
 * its inactivity limits, is lowered to the cap while one applies (260, unlimited, included).
 */
@Mixin(FramerateLimitTracker.class)
abstract class FramerateLimitTrackerMixin {

    @Inject(method = "getFramerateLimit", at = @At("RETURN"), cancellable = true)
    private void orchard$powerLimit(CallbackInfoReturnable<Integer> cir) {
        int cap = PowerLimit.now();
        if (cap > 0) {
            cir.setReturnValue(PowerLimit.apply(cir.getReturnValueI(), cap));
        }
    }
}
