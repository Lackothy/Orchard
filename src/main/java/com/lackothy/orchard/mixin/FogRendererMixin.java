package com.lackothy.orchard.mixin;

import com.lackothy.orchard.video.VideoOptions;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Distance fog off: the fog starts past anything that can be drawn. Only in open air; fog under
 * water, in lava or powder snow, and from blindness or darkness, is how those work, so it stays.
 * Runs after the Metal renderer's far-terrain fog, whose distances it replaces.
 */
@Mixin(FogRenderer.class)
abstract class FogRendererMixin {

    @Unique
    private static final float FAR = 1.0e7f;

    @Inject(method = "setupFog", at = @At("RETURN"))
    private void orchard$noFog(Camera camera, int renderDistance, DeltaTracker delta, float darken, ClientLevel level,
                               CallbackInfoReturnable<FogData> cir) {
        if (VideoOptions.FOG.on() || camera.getFluidInCamera() != FogType.NONE) {
            return;
        }
        if (Minecraft.getInstance().getCameraEntity() instanceof LivingEntity living
            && (living.hasEffect(MobEffects.BLINDNESS) || living.hasEffect(MobEffects.DARKNESS))) {
            return;
        }
        FogData fog = cir.getReturnValue();
        fog.environmentalStart = FAR;
        fog.environmentalEnd = FAR + 1;
        fog.renderDistanceStart = FAR;
        fog.renderDistanceEnd = FAR + 1;
    }
}
