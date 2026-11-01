package com.lackothy.orchard.mixin;

import com.lackothy.orchard.video.VideoOptions;
import net.minecraft.client.renderer.entity.state.ItemClusterRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Stacks as one item: a stack on the ground is drawn once, where Minecraft draws it up to five times by its size. */
@Mixin(ItemClusterRenderState.class)
abstract class ItemClusterRenderStateMixin {

    @Inject(method = "getRenderedAmount", at = @At("HEAD"), cancellable = true)
    private static void orchard$once(int count, CallbackInfoReturnable<Integer> cir) {
        if (VideoOptions.ONE_ITEM_PER_STACK.on() && count > 0) {
            cir.setReturnValue(1);
        }
    }
}
