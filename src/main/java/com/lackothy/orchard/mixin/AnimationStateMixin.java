package com.lackothy.orchard.mixin;

import com.lackothy.orchard.video.Animations;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
