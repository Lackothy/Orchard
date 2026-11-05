package com.lackothy.russet.menu.option;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A line that sets something. A value picked in the menu stays there until Apply writes it, so
 * Undo can take it back; until one is picked it shows what is set now, so a change made
 * elsewhere meanwhile (full screen from F11, the options a graphics preset sets) is never
 * written back over. {@code fallback} is what it starts as, for Reset, or null when that isn't
 * known.
 */
public abstract class MenuSetting<V> extends MenuOption {

    private final Supplier<V> applied;
    private final Consumer<V> write;
    private final @Nullable V fallback;
    private boolean held;
    private @Nullable V value;

    MenuSetting(Identifier id, Component name, @Nullable Component tooltip, BooleanSupplier enabled,
                Supplier<V> applied, Consumer<V> write, @Nullable V fallback) {
        super(id, name, tooltip, enabled);
        this.applied = applied;
        this.write = write;
        this.fallback = fallback;
    }

    /** What the menu holds, or what it is set to now when nothing is held. */
    public V getValidatedValue() {
        return held ? value : applied.get();
    }

    /** What it is set to now. */
    public V getAppliedValue() {
        return applied.get();
    }

    public void modifyValue(V value) {
        this.value = value;
        this.held = true;
    }

    public @Nullable V getDefaultValue() {
        return fallback;
    }

    public void resetToDefault() {
        if (fallback != null) {
            modifyValue(fallback);
        }
    }

    @Override
    public boolean hasChanged() {
        return held && !Objects.equals(value, applied.get());
    }

    public boolean shouldHideControl() {
        return false;
    }

    /** Writes what the menu holds, if it differs. */
    public void apply() {
        if (hasChanged()) {
            write.accept(value);
        }
    }

    /** Lets go of the held value, back to what it is set to now. */
    public void undo() {
        held = false;
        value = null;
    }
}
