package com.lackothy.russet.mixin;

import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A tooltip's text, for the video settings to show it over a setting's name too. */
@Mixin(Tooltip.class)
public interface TooltipAccess {

    @Accessor("message")
    Component russet$message();
}
