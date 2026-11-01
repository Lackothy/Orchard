package com.lackothy.orchard.mixin;

import com.lackothy.orchard.video.Animations;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Tells each moving texture's state which Animations switch it answers to. */
@Mixin(SpriteContents.class)
abstract class SpriteContentsMixin {

    @Shadow
    public abstract Identifier name();

    @Inject(method = "createAnimationState", at = @At("RETURN"))
    private void orchard$kind(GpuBufferSlice ubo, int spriteUboSize, CallbackInfoReturnable<SpriteContents.AnimationState> cir) {
        if (cir.getReturnValue() != null) {
            ((Animations.Kind) cir.getReturnValue()).orchard$setKind(Animations.of(name()));
        }
    }
}
