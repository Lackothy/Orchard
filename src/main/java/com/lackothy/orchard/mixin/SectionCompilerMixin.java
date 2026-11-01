package com.lackothy.orchard.mixin;

import com.lackothy.orchard.video.BakedBlockEntities;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * BakedBlockEntities, where Minecraft's own compile runs: OpenGL and Vulkan, and Metal until its
 * terrain is up (OwnMesher does the same for it). In each block's turn the compile handles its
 * block entity first, then asks for its model, so a baked one is decided at the first and handed
 * over at the second.
 */
@Mixin(SectionCompiler.class)
abstract class SectionCompilerMixin {

    @WrapOperation(method = "compile", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/chunk/SectionCompiler;handleBlockEntity(Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;Lnet/minecraft/world/level/block/entity/BlockEntity;)V"))
    private void orchard$handle(SectionCompiler compiler, SectionCompiler.Results results, BlockEntity blockEntity, Operation<Void> original,
        @Local BlockState state, @Share("baked") LocalRef<BlockStateModel> shared) {
        BlockStateModel baked = BakedBlockEntities.model(state, blockEntity);
        shared.set(baked);
        if (baked == null) {
            original.call(compiler, results, blockEntity);
        }
    }

    @WrapOperation(method = "compile", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/block/BlockStateModelSet;get(Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;"))
    private BlockStateModel orchard$model(BlockStateModelSet models, BlockState state, Operation<BlockStateModel> original,
        @Share("baked") LocalRef<BlockStateModel> shared) {
        BlockStateModel baked = shared.get();
        if (baked != null) {
            shared.set(null);
            return baked;
        }
        return original.call(models, state);
    }
}
