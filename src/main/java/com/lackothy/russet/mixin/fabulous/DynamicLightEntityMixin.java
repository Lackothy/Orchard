package com.lackothy.russet.mixin.fabulous;

import com.lackothy.russet.fabulous.DynamicLights;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(EntityRenderer.class)
abstract class DynamicLightEntityMixin {

    @ModifyReturnValue(method = "getPackedLightCoords", at = @At("RETURN"))
    private int russet$dynamicLight(int packed, @Local(argsOnly = true) Entity entity, @Local(argsOnly = true) float partialTick) {
        return DynamicLights.raise(packed, entity, partialTick);
    }
}
