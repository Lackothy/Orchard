package com.lackothy.russet.rain.mixin.tint;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.DripParticle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.SimpleParticleType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.lackothy.russet.rain.ParticleRain;
import com.lackothy.russet.rain.TextureUtil;
import com.lackothy.russet.rain.config.ConfigManager;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.util.RandomSource;

@Mixin({
    DripParticle.WaterFallProvider.class,
        DripParticle.WaterHangProvider.class,
        DripParticle.DripstoneWaterFallProvider.class,
        DripParticle.DripstoneWaterHangProvider.class
})
public abstract class DripParticleMixin {
    @Inject(method = "createParticle(Lnet/minecraft/core/particles/SimpleParticleType;Lnet/minecraft/client/multiplayer/ClientLevel;DDDDDDLnet/minecraft/util/RandomSource;)Lnet/minecraft/client/particle/Particle;", at = @At("TAIL"))
    public void createParticle(SimpleParticleType particleType, ClientLevel level, double x, double y, double z, double xSpeed, double ySpeed, double zSpeed, RandomSource random, CallbackInfoReturnable<Particle> cir) {
        if (ParticleRain.on() && ConfigManager.getConfig().compat.waterTint) TextureUtil.copyRainTint((SingleQuadParticle) cir.getReturnValue(), level, BlockPos.containing(x, y, z));
    }
}
