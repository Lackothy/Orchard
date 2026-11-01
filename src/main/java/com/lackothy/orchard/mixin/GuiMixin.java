package com.lackothy.orchard.mixin;

import com.lackothy.orchard.menu.VideoOptionsScreen;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Opens Orchard's video settings wherever Minecraft would open its own Video Settings, as Reese's
 * Sodium Options takes the place of Sodium's: the Options menu's button, and any mod's way in,
 * lead there, and the rest of the Options menu stays Minecraft's. Shift+P in Orchard's opens
 * Minecraft's, with {@code openingVanilla} set.
 */
@Mixin(Gui.class)
abstract class GuiMixin {

    @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
    private Screen orchard$videoSettings(Screen screen) {
        if (screen != null && !VideoOptionsScreen.openingVanilla && screen.getClass() == VideoSettingsScreen.class) {
            return new VideoOptionsScreen(((OptionsSubScreenAccess) screen).orchard$lastScreen());
        }
        return screen;
    }
}
