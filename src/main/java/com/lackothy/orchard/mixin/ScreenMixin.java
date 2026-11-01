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

/**
 * Puts the orchard in place of the panorama: behind the title screen, and behind every other menu
 * outside a world (worlds, servers, options), blurred there by Minecraft's own menu blur.
 */
@Mixin(Screen.class)
public abstract class ScreenMixin {

    @Shadow
    public int width;

    @Shadow
    public int height;

    @Inject(method = "extractPanorama", at = @At("HEAD"), cancellable = true)
    private void orchard$scene(GuiGraphicsExtractor graphics, float delta, CallbackInfo ci) {
        OrchardScene.draw(new Canvas(graphics), width, height);
        // The panorama is drawn before the GUI; the scene is GUI, so it needs a stratum of its own
        // for the blur that follows to fall between it and the menu.
        graphics.nextStratum();
        ci.cancel();
    }
}
