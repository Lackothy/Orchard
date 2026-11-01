package com.lackothy.orchard.mixin;

import com.lackothy.orchard.video.DroppedItemLimit;
import com.lackothy.orchard.video.RenderScale;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class GameRendererMixin {

    @Shadow
    @Final
    @Mutable
    private RenderTarget mainRenderTarget;

    @Shadow
    @Final
    @Mutable
    private RenderTarget hud3DTarget;

    @Unique
    private RenderTarget orchard$fullMain;

    @Unique
    private RenderTarget orchard$fullHand;

    @Inject(method = "extract", at = @At("HEAD"))
    private void orchard$nextFrame(CallbackInfo ci) {
        DroppedItemLimit.nextFrame();
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel()V"))
    private void orchard$drawWorldSmaller(CallbackInfo ci) {
        RenderScale.Targets smaller = RenderScale.begin(this.mainRenderTarget);
        if (smaller != null) {
            this.orchard$fullMain = this.mainRenderTarget;
            this.orchard$fullHand = this.hud3DTarget;
            this.mainRenderTarget = smaller.main();
            this.hud3DTarget = smaller.hand();
        }
    }

    @Inject(method = "render", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/GameRenderer;applyPostEffects()V", shift = At.Shift.AFTER))
    private void orchard$scaleWorldUp(CallbackInfo ci) {
        if (this.orchard$fullMain != null) {
            RenderScale.end(this.mainRenderTarget, this.orchard$fullMain);
            this.mainRenderTarget = this.orchard$fullMain;
            this.hud3DTarget = this.orchard$fullHand;
            this.orchard$fullMain = null;
            this.orchard$fullHand = null;
        }
    }
}
