package com.lackothy.orchard.mixin;

import com.lackothy.orchard.video.VideoOptions;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** A local world's server follows Render Distance past Minecraft's 32 (OptionsMixin). */
@Mixin(ChunkMap.class)
abstract class ChunkMapMixin {

    @ModifyConstant(method = "setServerViewDistance", constant = @Constant(intValue = 32))
    private int orchard$viewDistanceLimit(int limit) {
        return VideoOptions.RENDER_DISTANCE_LIMIT;
    }
}
