package com.lackothy.russet.rain.mixin.render;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.lackothy.russet.rain.ParticleSpawner;
import com.lackothy.russet.rain.WindManager;
import com.lackothy.russet.rain.particle.BlockDisplayParticle;
import com.lackothy.russet.rain.particle.WeatherParticle;

@Mixin(ParticleEngine.class)
public class ParticleEngineMixin {

    @Inject(method = "clearParticles", at = @At("HEAD"))
    public void clearParticles(CallbackInfo ci) {
        BlockDisplayParticle.clearAll();
        WindManager.clear();
        ParticleSpawner.particleCount = 0;
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/Particle;getParticleLimit()Ljava/util/Optional;"))
    public void onParticleSpawnCanceled(CallbackInfo ci, @Local Particle particle) {
        if (particle instanceof WeatherParticle) ParticleSpawner.particleCount--;
    }
    }
