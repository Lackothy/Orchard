package com.lackothy.russet.mixin.fix;

import com.lackothy.russet.fix.HiddenWindow;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FramerateLimitTracker.class)
abstract class HiddenWindowMixin {

    @Inject(method = "getFramerateLimit", at = @At("RETURN"), cancellable = true)
    private void russet$hiddenWindow(CallbackInfoReturnable<Integer> cir) {
        int limit = cir.getReturnValueI();
        int lowered = HiddenWindow.limit(limit);
        if (lowered != limit) {
            cir.setReturnValue(lowered);
        }
    }
}
