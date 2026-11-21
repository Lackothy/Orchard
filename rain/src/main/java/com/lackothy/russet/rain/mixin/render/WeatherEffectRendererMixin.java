package com.lackothy.russet.rain.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.lackothy.russet.rain.ParticleRain;
import net.minecraft.client.renderer.state.level.WeatherRenderState;

import java.util.List;

import static com.lackothy.russet.rain.config.ConfigManager.getConfig;

@Mixin(WeatherEffectRenderer.class)
public abstract class WeatherEffectRendererMixin {

        @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    public void render(ClientLevel level, float partialTicks, Vec3 cameraPos, WeatherRenderState renderState, CallbackInfo ci) {

        if (ParticleRain.on() && !getConfig().compat.renderDefaultRain && !getConfig().compat.renderDefaultSnow) { // Russet: on()
            ci.cancel();
        }
    }

    @WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z", ordinal = 0))
    public <E> boolean cancelAddRainColumn(List<WeatherEffectRenderer.ColumnInstance> instance, E columnInstance, Operation<Boolean> original) {
        if (ParticleRain.on() && !getConfig().compat.renderDefaultRain) return false; // Russet: on()
        return original.call(instance, columnInstance);
    }

    @WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z", ordinal = 1))
    public <E> boolean cancelAddSnowColumn(List<WeatherEffectRenderer.ColumnInstance> instance, E columnInstance, Operation<Boolean> original) {
        if (ParticleRain.on() && !getConfig().compat.renderDefaultSnow) return false; // Russet: on()
        return original.call(instance, columnInstance);
    }
}
