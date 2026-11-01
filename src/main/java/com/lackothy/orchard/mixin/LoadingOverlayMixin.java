package com.lackothy.orchard.mixin;

import com.lackothy.Orchard;
import com.lackothy.orchard.loading.LoadingScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws Orchard's loading screen in place of Mojang's. Minecraft's {@code tick} still finishes
 * the load; this only draws, and takes the overlay away when the ending is over, where Minecraft
 * would two seconds after the load. If drawing ever fails, Mojang's screen draws from then on.
 */
@Mixin(LoadingOverlay.class)
public abstract class LoadingOverlayMixin {

    @Unique
    private static boolean orchard$failed;

    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    @Final
    private ReloadInstance reload;

    @Shadow
    @Final
    private boolean fadeIn;

    @Shadow
    private long fadeOutStart;

    @Shadow
    private long fadeInStart;

    @Unique
    private LoadingScreen orchard$screen;

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void orchard$draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (orchard$failed) {
            return;
        }
        // Minecraft's own drawing starts the fade-in clock, which its tick waits on.
        if (fadeIn && fadeInStart == -1L) {
            fadeInStart = Util.getMillis();
        }
        try {
            if (orchard$screen == null) {
                orchard$screen = new LoadingScreen(fadeIn);
            }
            if (orchard$screen.draw(graphics, minecraft, mouseX, mouseY, delta, reload.getActualProgress(), fadeOutStart != -1L)) {
                orchard$screen.close(minecraft);
                minecraft.gui.setOverlay(null);
            }
        } catch (RuntimeException e) {
            Orchard.LOG.error("Orchard's loading screen failed; showing Minecraft's", e);
            orchard$failed = true;
            if (orchard$screen != null) {
                orchard$screen.close(minecraft);
            }
            return;
        }
        ci.cancel();
    }
}
