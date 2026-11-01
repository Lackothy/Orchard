package com.lackothy.orchard.mixin;

import com.lackothy.orchard.video.BakedBlockEntities;
import com.lackothy.orchard.video.VideoOptions;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ChestLidController;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Faster chests (BakedBlockEntities): a chest changes over as its lid starts or stops moving, in the
 * client's tick of the lid (the server's chests don't tick theirs). The tick after a lid is told
 * to open is the first time a compile could tell, so the mark is made then and not when told.
 */
@Mixin({ChestBlockEntity.class, EnderChestBlockEntity.class})
abstract class ChestLidMixin {

    @WrapOperation(method = "lidAnimateTick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/block/entity/ChestLidController;tickLid()V"))
    private static void orchard$lid(ChestLidController lid, Operation<Void> original, @Local(argsOnly = true) BlockPos pos) {
        boolean closed = lid.getOpenness(0f) == 0f && lid.getOpenness(1f) == 0f;
        original.call(lid);
        if (closed != (lid.getOpenness(0f) == 0f && lid.getOpenness(1f) == 0f)) {
            BakedBlockEntities.moved(VideoOptions.BAKED_CHESTS, pos);
        }
    }
}
