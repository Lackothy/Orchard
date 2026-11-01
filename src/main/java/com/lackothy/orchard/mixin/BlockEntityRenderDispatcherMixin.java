package com.lackothy.orchard.mixin;

import com.lackothy.orchard.video.VideoOptions;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.EnchantingTableBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Beacon beams and enchanting table books off: those blocks give no render state, as a block
 * with no renderer gives none, and both Minecraft's renderer and the Metal one skip them.
 */
@Mixin(BlockEntityRenderDispatcher.class)
abstract class BlockEntityRenderDispatcherMixin {

    @Inject(method = "tryExtractRenderState", at = @At("HEAD"), cancellable = true)
    private void orchard$shown(BlockEntity blockEntity, float delta, ModelFeatureRenderer.CrumblingOverlay crumbling,
                               boolean global, CallbackInfoReturnable<BlockEntityRenderState> cir) {
        if (blockEntity instanceof BeaconBlockEntity && !VideoOptions.BEACON_BEAMS.on()
            || blockEntity instanceof EnchantingTableBlockEntity && !VideoOptions.ENCHANTING_BOOKS.on()) {
            cir.setReturnValue(null);
        }
    }
}
