package com.lackothy.russet.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the render state so Russet can queue its own shapes alongside vanilla's. */
@Mixin(GuiGraphicsExtractor.class)
public interface GuiGraphicsAccess {

    @Accessor("guiRenderState")
    GuiRenderState russet$renderState();
}
