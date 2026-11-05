package com.lackothy.russet.menu.option;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.function.Supplier;

/** Something to read, not set: a fact with its value on the right, or with no value a note in grey. */
public final class MenuFact extends MenuOption {

    private final @Nullable Supplier<Component> value;

    public MenuFact(Identifier id, Component name, @Nullable Component tooltip, @Nullable Supplier<Component> value) {
        super(id, name, tooltip, () -> true);
        this.value = value;
    }

    public boolean isNote() {
        return value == null;
    }

    public Component value() {
        return value == null ? Component.empty() : value.get();
    }
}
