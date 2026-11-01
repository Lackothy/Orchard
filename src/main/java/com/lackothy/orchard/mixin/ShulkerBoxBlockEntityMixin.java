package com.lackothy.orchard.mixin;

import com.lackothy.orchard.video.BakedBlockEntities;
import com.lackothy.orchard.video.VideoOptions;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Faster shulker boxes (BakedBlockEntities): a box changes over as its lid leaves or comes back to
 * rest, in the client's tick of the lid (the server's boxes tick theirs too).
 */
@Mixin(ShulkerBoxBlockEntity.class)
abstract class ShulkerBoxBlockEntityMixin {

    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/block/entity/ShulkerBoxBlockEntity;updateAnimation(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V"))
    private static void orchard$lid(ShulkerBoxBlockEntity box, Level level, BlockPos pos, BlockState state, Operation<Void> original) {
        boolean closed = box.getProgress(1f) == 0f;
        original.call(box, level, pos, state);
        if (level.isClientSide() && closed != (box.getProgress(1f) == 0f)) {
            BakedBlockEntities.moved(VideoOptions.BAKED_SHULKER_BOXES, pos);
        }
    }
}
