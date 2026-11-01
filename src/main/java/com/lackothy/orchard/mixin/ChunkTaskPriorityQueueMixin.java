package com.lackothy.orchard.mixin;

import com.lackothy.orchard.video.VideoOptions;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.server.level.ChunkTaskPriorityQueue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ChunkTaskPriorityQueue.class)
abstract class ChunkTaskPriorityQueueMixin {

    @ModifyExpressionValue(method = "<clinit>", at = @At(value = "FIELD", target = "Lnet/minecraft/server/level/ChunkLevel;MAX_LEVEL:I"))
    private static int orchard$roomForDistance(int level) {
        return Math.max(level, VideoOptions.RENDER_DISTANCE_LIMIT + 1);
    }
}
