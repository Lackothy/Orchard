package com.lackothy.russet.menu.option;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

public final class MenuSlider<V> extends MenuSetting<V> {

    private final int steps;
    private final ToIntFunction<V> position;
    private final IntFunction<V> at;
    private final Function<V, Component> label;

    public MenuSlider(Identifier id, Component name, @Nullable Component tooltip, BooleanSupplier enabled,
                      Supplier<V> applied, Consumer<V> write, @Nullable V fallback,
                      int steps, ToIntFunction<V> position, IntFunction<V> at, Function<V, Component> label) {
        super(id, name, tooltip, enabled, applied, write, fallback);
        this.steps = steps;
        this.position = position;
        this.at = at;
        this.label = label;
    }

    public int steps() {
        return steps;
    }

    public int position(V value) {
        return Math.clamp(position.applyAsInt(value), 0, steps);
    }

    public V at(int step) {
        return at.apply(Math.clamp(step, 0, steps));
    }

    public V step(V value, int direction) {
        for (int step = position(value) + direction; step >= 0 && step <= steps; step += direction) {
            V next = at(step);
            if (!next.equals(value)) {
                return next;
            }
        }
        return value;
    }

    public Component formatValue(V value) {
        return label.apply(value);
    }
}
