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

/**
 * A value along a track of {@link #steps()} steps. It holds the value itself, not the step, as
 * not every value of Minecraft's falls on one (a percentage, a frame rate in tens).
 */
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

    /** The step {@code value} is at, from 0 to {@link #steps()}. */
    public int position(V value) {
        return Math.clamp(position.applyAsInt(value), 0, steps);
    }

    public V at(int step) {
        return at.apply(Math.clamp(step, 0, steps));
    }

    /** The next value along, {@code direction} steps at a time, or {@code value} at the end of the track. */
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
