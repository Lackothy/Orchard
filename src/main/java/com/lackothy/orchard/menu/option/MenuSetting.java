package com.lackothy.orchard.menu.option;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

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

    public V getValidatedValue() {
        return held ? value : applied.get();
    }

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

    public void apply() {
        if (hasChanged()) {
            write.accept(value);
        }
    }

    public void undo() {
        held = false;
        value = null;
    }
}
