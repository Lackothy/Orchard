package com.lackothy.russet.mixin.fabulous;

import com.lackothy.russet.fabulous.DynamicLights;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LightCoordsUtil.class)
abstract class DynamicLightMixin {

    @ModifyReturnValue(method = "getLightCoords(Lnet/minecraft/util/LightCoordsUtil$BrightnessGetter;"
        + "Lnet/minecraft/world/level/BlockAndLightGetter;Lnet/minecraft/world/level/block/state/BlockState;"
        + "Lnet/minecraft/core/BlockPos;)I", at = @At("RETURN"))
    private static int russet$dynamicLight(int packed, @Local(argsOnly = true) BlockPos pos) {
        return DynamicLights.raise(packed, pos);
    }
}
