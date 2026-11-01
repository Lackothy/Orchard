package com.lackothy.orchard.mixin;

import com.lackothy.orchard.video.VideoOptions;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Slice;

/**
 * Render Distance goes further than Minecraft's 32. The first 32 after its name in the constructor
 * is its range; earlier ones are key codes, and the next is Simulation Distance's, which stays.
 */
@Mixin(Options.class)
abstract class OptionsMixin {

    @ModifyConstant(method = "<init>", constant = @Constant(intValue = 32, ordinal = 0),
        slice = @Slice(from = @At(value = "CONSTANT", args = "stringValue=options.renderDistance")))
    private int orchard$renderDistanceLimit(int limit) {
        return VideoOptions.RENDER_DISTANCE_LIMIT;
    }
}
