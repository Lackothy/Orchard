package com.lackothy.russet.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SkyRenderer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SkyRenderer.class)
abstract class RenderScaleSkyMixin {

    @ModifyExpressionValue(method = "render", at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
        target = "Lnet/minecraft/client/renderer/SkyRenderer;renderTarget:Lcom/mojang/blaze3d/pipeline/RenderTarget;"), require = 2)
    private RenderTarget russet$thisFramesTarget(RenderTarget made) {
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        return main != null ? main : made;
    }
}
