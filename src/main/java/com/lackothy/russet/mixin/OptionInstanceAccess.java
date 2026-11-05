package com.lackothy.russet.mixin;

import net.minecraft.client.OptionInstance;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.function.Function;

/** An option's name, how it words a value, its tooltip and what it starts as, for the options menu to draw it itself. */
@Mixin(OptionInstance.class)
public interface OptionInstanceAccess {

    @Accessor("initialValue")
    Object russet$initialValue();

    @Accessor("caption")
    Component russet$caption();

    @Accessor("toString")
    Function<Object, Component> russet$toString();

    @Accessor("tooltip")
    OptionInstance.TooltipSupplier<Object> russet$tooltip();
}
