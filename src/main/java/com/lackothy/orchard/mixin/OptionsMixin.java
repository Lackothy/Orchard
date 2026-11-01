package com.lackothy.orchard.mixin;

import com.lackothy.orchard.video.VideoOptions;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Slice;

@Mixin(Options.class)
abstract class OptionsMixin {

    @ModifyConstant(method = "<init>", constant = @Constant(intValue = 32, ordinal = 0),
        slice = @Slice(from = @At(value = "CONSTANT", args = "stringValue=options.renderDistance")))
    private int orchard$renderDistanceLimit(int limit) {
        return VideoOptions.RENDER_DISTANCE_LIMIT;
    }
}
