package com.lackothy.orchard.mixin;

import com.lackothy.orchard.video.VideoOptions;
import net.minecraft.client.gui.components.toasts.AdvancementToast;
import net.minecraft.client.gui.components.toasts.RecipeToast;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.gui.components.toasts.TutorialToast;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The Overlay page's notification switches: a pop-up that is off is never queued. */
@Mixin(ToastManager.class)
abstract class ToastManagerMixin {

    @Inject(method = "addToast", at = @At("HEAD"), cancellable = true)
    private void orchard$shown(Toast toast, CallbackInfo ci) {
        if (toast instanceof AdvancementToast && !VideoOptions.ADVANCEMENT_TOASTS.on()
            || toast instanceof RecipeToast && !VideoOptions.RECIPE_TOASTS.on()
            || toast instanceof SystemToast && !VideoOptions.SYSTEM_TOASTS.on()
            || toast instanceof TutorialToast && !VideoOptions.TUTORIAL_TOASTS.on()) {
            ci.cancel();
        }
    }
}
