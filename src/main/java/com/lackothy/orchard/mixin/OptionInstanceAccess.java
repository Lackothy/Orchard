package com.lackothy.orchard.mixin;

import net.minecraft.client.OptionInstance;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.function.Function;

@Mixin(OptionInstance.class)
public interface OptionInstanceAccess {

    @Accessor("initialValue")
    Object orchard$initialValue();

    @Accessor("caption")
    Component orchard$caption();

    @Accessor("toString")
    Function<Object, Component> orchard$toString();

    @Accessor("tooltip")
    OptionInstance.TooltipSupplier<Object> orchard$tooltip();
}
