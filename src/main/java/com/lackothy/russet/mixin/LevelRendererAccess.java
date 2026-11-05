package com.lackothy.russet.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LevelRenderer.class)
public interface LevelRendererAccess {

    @Accessor("entityOutlineTarget")
    RenderTarget russet$entityOutlineTarget();
}
