package com.lackothy.orchard.mixin;

import com.lackothy.orchard.video.VideoOptions;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Rain and snow off: no columns to draw. The weather itself, its sound and darker sky, carry on. */
@Mixin(WeatherEffectRenderer.class)
abstract class WeatherEffectRendererMixin {

    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void orchard$weather(ClientLevel level, float delta, Vec3 camera, WeatherRenderState state, CallbackInfo ci) {
        if (!VideoOptions.WEATHER.on()) {
            state.rainColumns.clear();
            state.snowColumns.clear();
        }
    }
}
