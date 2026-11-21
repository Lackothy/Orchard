package com.lackothy.russet.mixin.fabulous;

import com.lackothy.russet.fabulous.EffectParticles;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LivingEntity.class)
abstract class EffectParticleMixin {

    @WrapOperation(method = "tickEffects", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/Level;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"))
    private void russet$effectParticle(Level level, ParticleOptions options, double x, double y, double z,
                                       double xd, double yd, double zd, Operation<Void> original) {
        EffectParticles.claim(options);
        try {
            original.call(level, options, x, y, z, xd, yd, zd);
        } finally {
            EffectParticles.release();
        }
    }
}
