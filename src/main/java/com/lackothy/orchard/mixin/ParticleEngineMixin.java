package com.lackothy.orchard.mixin;

import com.lackothy.orchard.video.VideoOptions;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Particles turned off on the Particles page are never made, so they cost nothing at all. */
@Mixin(ParticleEngine.class)
abstract class ParticleEngineMixin {

    @Inject(method = "makeParticle", at = @At("HEAD"), cancellable = true)
    private void orchard$shown(ParticleOptions options, double x, double y, double z, double dx, double dy, double dz,
                               CallbackInfoReturnable<Particle> cir) {
        if (!VideoOptions.shows(options.getType())) {
            cir.setReturnValue(null);
        }
    }

    /** Particles made directly, such as the pieces of a broken block, come in here instead. */
    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void orchard$any(Particle particle, CallbackInfo ci) {
        if (!VideoOptions.PARTICLES.on()) {
            ci.cancel();
        }
    }
}
