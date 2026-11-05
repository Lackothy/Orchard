package com.lackothy.russet.menu.option;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** On or off, as a tick box. */
public final class MenuToggle extends MenuSetting<Boolean> {

    public MenuToggle(Identifier id, Component name, @Nullable Component tooltip, BooleanSupplier enabled,
                      Supplier<Boolean> applied, Consumer<Boolean> write, @Nullable Boolean fallback) {
        super(id, name, tooltip, enabled, applied, write, fallback);
    }
}
