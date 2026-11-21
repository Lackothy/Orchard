package com.lackothy.russet.rain.mixin;

import net.minecraft.client.gui.components.AbstractWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import com.lackothy.russet.rain.config.gui.AbstractWidgetAccess;

@Mixin(AbstractWidget.class)
public class AbstractWidgetMixin implements AbstractWidgetAccess {

    @Unique public int xOffset = 0;

    @Override
    public int pigcart$getOffset() {
        return xOffset;
    }

    @Override
    public void pigcart$setOffset(int value) {
        xOffset = value;
    }
}
