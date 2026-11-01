package com.lackothy.orchard.mixin;

import com.lackothy.orchard.video.Animations;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A moving texture whose switch is off stops on the frame it is on: its state never ticks, so it
 * is never marked for drawing, by Minecraft's renderer or the Metal one, and costs nothing.
 */
@Mixin(SpriteContents.AnimationState.class)
abstract class AnimationStateMixin implements Animations.Kind {

    @Unique
    private Animations orchard$kind = Animations.OTHER;

    @Override
    public void orchard$setKind(Animations kind) {
        orchard$kind = kind;
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void orchard$still(CallbackInfo ci) {
        if (!orchard$kind.moves()) {
            ci.cancel();
        }
    }
}
