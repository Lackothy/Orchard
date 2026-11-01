package com.lackothy.orchard.mixin;

import com.lackothy.orchard.video.FrameOverlay;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The frame rate and coordinates, over the rest of the HUD. */
@Mixin(Hud.class)
abstract class HudMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void orchard$overlay(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo ci) {
        FrameOverlay.draw(graphics);
    }
}
