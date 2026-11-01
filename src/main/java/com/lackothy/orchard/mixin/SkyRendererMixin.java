package com.lackothy.orchard.mixin;

import com.lackothy.orchard.video.VideoOptions;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The World page's sky switches. With the sky off, what shows is the colour the frame is cleared to. */
@Mixin(SkyRenderer.class)
abstract class SkyRendererMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void orchard$sky(GpuBufferSlice fog, SkyRenderState state, CallbackInfo ci) {
        if (!VideoOptions.SKY.on()) {
            ci.cancel();
        }
    }

    @Inject(method = {"renderSun", "renderMoon"}, at = @At("HEAD"), cancellable = true)
    private void orchard$sunAndMoon(CallbackInfo ci) {
        if (!VideoOptions.SUN_AND_MOON.on()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderStars", at = @At("HEAD"), cancellable = true)
    private void orchard$stars(CallbackInfo ci) {
        if (!VideoOptions.STARS.on()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderSunriseAndSunset", at = @At("HEAD"), cancellable = true)
    private void orchard$sunrise(CallbackInfo ci) {
        if (!VideoOptions.SUNRISE.on()) {
            ci.cancel();
        }
    }
}
