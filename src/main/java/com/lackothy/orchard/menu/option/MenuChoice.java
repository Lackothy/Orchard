package com.lackothy.orchard.menu.option;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** One of a few values, clicked through in order. */
public final class MenuChoice<V> extends MenuSetting<V> {

    private final Supplier<List<V>> values;
    private final Function<V, Component> label;

    public MenuChoice(Identifier id, Component name, @Nullable Component tooltip, BooleanSupplier enabled,
                      Supplier<V> applied, Consumer<V> write, @Nullable V fallback,
                      Supplier<List<V>> values, Function<V, Component> label) {
        super(id, name, tooltip, enabled, applied, write, fallback);
        this.values = values;
        this.label = label;
    }

    /** The values to click through, in order. Minecraft's can change, as they do while Alt is held. */
    public List<V> values() {
        return values.get();
    }

    public Component getElementName(V value) {
        return label.apply(value);
    }

    public boolean isValueAllowed(V value) {
        return values().contains(value);
    }
}
