package com.lackothy.orchard.mixin;

import net.minecraft.client.renderer.blockentity.ChestRenderer;
import net.minecraft.client.renderer.blockentity.state.ChestRenderState;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChestRenderer.class)
public interface ChestRendererAccess {

    @Invoker("getChestMaterial")
    static ChestRenderState.ChestMaterialType orchard$material(BlockEntity blockEntity, boolean christmas) {
        throw new AssertionError();
    }
}
