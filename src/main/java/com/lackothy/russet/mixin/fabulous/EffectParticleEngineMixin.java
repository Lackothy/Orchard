package com.lackothy.russet.mixin.fabulous;

import com.lackothy.russet.fabulous.EffectParticles;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ParticleEngine.class)
abstract class EffectParticleEngineMixin {

    @Shadow
    protected ClientLevel level;

    @Shadow
    public abstract void add(Particle particle);

    @Inject(method = "createParticle", at = @At("HEAD"), cancellable = true)
    private void russet$effectParticle(ParticleOptions options, double x, double y, double z, double xd, double yd, double zd,
                                       CallbackInfoReturnable<Particle> cir) {
        Particle ours = EffectParticles.create(level, x, y, z);
        if (ours != null) {
            add(ours);
            cir.setReturnValue(ours);
        }
    }
}
