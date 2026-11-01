package com.lackothy.orchard.mixin;

import com.lackothy.orchard.video.VideoOptions;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.server.level.ChunkTaskPriorityQueue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Room for a player ticket's distance (DistanceManagerMixin). The tracker queues its tasks by
 * distance, up to its reach + 2, and the queue has one list per level, as many as chunk levels
 * need: a distance past the last list would throw. More lists only means more empty ones.
 */
@Mixin(ChunkTaskPriorityQueue.class)
abstract class ChunkTaskPriorityQueueMixin {

    @ModifyExpressionValue(method = "<clinit>", at = @At(value = "FIELD", target = "Lnet/minecraft/server/level/ChunkLevel;MAX_LEVEL:I"))
    private static int orchard$roomForDistance(int level) {
        return Math.max(level, VideoOptions.RENDER_DISTANCE_LIMIT + 1);
    }
}
