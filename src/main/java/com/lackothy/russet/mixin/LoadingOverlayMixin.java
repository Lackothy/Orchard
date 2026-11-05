package com.lackothy.russet.mixin;

import com.lackothy.Russet;
import com.lackothy.russet.loading.LoadingScreen;
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
 * Draws Russet's loading screen in place of Mojang's. Minecraft's {@code tick} still finishes
 * the load; this only draws, and takes the overlay away when the ending is over, where Minecraft
 * would two seconds after the load. If drawing ever fails, Mojang's screen draws from then on.
 */
@Mixin(LoadingOverlay.class)
public abstract class LoadingOverlayMixin {

    @Unique
    private static boolean russet$failed;

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
    private LoadingScreen russet$screen;

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void russet$draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (russet$failed) {
            return;
        }
        // Minecraft's own drawing starts the fade-in clock, which its tick waits on.
        if (fadeIn && fadeInStart == -1L) {
            fadeInStart = Util.getMillis();
        }
        try {
            if (russet$screen == null) {
                russet$screen = new LoadingScreen(fadeIn);
            }
            if (russet$screen.draw(graphics, minecraft, mouseX, mouseY, delta, reload.getActualProgress(), fadeOutStart != -1L)) {
                russet$screen.close(minecraft);
                minecraft.gui.setOverlay(null);
            }
        } catch (RuntimeException e) {
            Russet.LOG.error("Russet's loading screen failed; showing Minecraft's", e);
            russet$failed = true;
            if (russet$screen != null) {
                russet$screen.close(minecraft);
            }
            return;
        }
        ci.cancel();
    }
}
