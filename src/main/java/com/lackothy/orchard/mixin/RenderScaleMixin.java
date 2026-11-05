package com.lackothy.orchard.mixin;

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

/**
 * Render scale: from the world's drawing to its post effects, Minecraft's main target and the
 * hand's depth are RenderScale's smaller ones; after them the world is scaled up into the real main
 * target, which the HUD and menus are then drawn on. Apart from GameRendererMixin, as Orchard Metal
 * has this and not that.
 */
@Mixin(GameRenderer.class)
abstract class RenderScaleMixin {

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
