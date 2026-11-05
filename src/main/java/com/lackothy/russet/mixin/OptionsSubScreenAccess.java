package com.lackothy.russet.mixin;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The screen an options page goes back to. */
@Mixin(OptionsSubScreen.class)
public interface OptionsSubScreenAccess {

    @Accessor("lastScreen")
    Screen russet$lastScreen();
}
