package com.lackothy.russet.mixin;

import com.lackothy.russet.menu.VideoOptionsScreen;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Gui.class)
abstract class GuiMixin {

    @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
    private Screen russet$videoSettings(Screen screen) {
        if (screen != null && !VideoOptionsScreen.openingVanilla && screen.getClass() == VideoSettingsScreen.class) {
            return new VideoOptionsScreen(((OptionsSubScreenAccess) screen).russet$lastScreen());
        }
        return screen;
    }
}
