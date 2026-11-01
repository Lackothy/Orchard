package com.lackothy.orchard.mixin;

import com.lackothy.orchard.ui.Canvas;
import com.lackothy.orchard.ui.OrchardScene;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
public abstract class ScreenMixin {

    @Shadow
    public int width;

    @Shadow
    public int height;

    @Inject(method = "extractPanorama", at = @At("HEAD"), cancellable = true)
    private void orchard$scene(GuiGraphicsExtractor graphics, float delta, CallbackInfo ci) {
        OrchardScene.draw(new Canvas(graphics), width, height);
        graphics.nextStratum();
        ci.cancel();
    }
}
