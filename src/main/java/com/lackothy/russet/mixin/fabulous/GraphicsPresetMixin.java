package com.lackothy.russet.mixin.fabulous;

import com.lackothy.russet.fabulous.Fabulous;
import net.minecraft.client.GraphicsPreset;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GraphicsPreset.class)
abstract class GraphicsPresetMixin {

    @Inject(method = "apply", at = @At("HEAD"), cancellable = true)
    private void russet$fabulous(Minecraft minecraft, CallbackInfo ci) {
        GraphicsPreset self = (GraphicsPreset) (Object) this;
        if (self == GraphicsPreset.FABULOUS) {
            GraphicsPreset.FANCY.apply(minecraft);
            Fabulous.presetApplied(GraphicsPreset.FABULOUS);
            ci.cancel();
        } else {
            Fabulous.presetApplied(self);
        }
    }
}
