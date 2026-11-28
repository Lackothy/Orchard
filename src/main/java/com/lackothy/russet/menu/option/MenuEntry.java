package com.lackothy.russet.menu.option;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public final class MenuEntry extends MenuSetting<String> {

    private final Function<String, @Nullable String> problem;

    public MenuEntry(Identifier id, Component name, @Nullable Component tooltip, BooleanSupplier enabled,
                     Supplier<String> applied, Consumer<String> write, @Nullable String fallback,
                     Function<String, @Nullable String> problem) {
        super(id, name, tooltip, enabled, applied, write, fallback);
        this.problem = problem;
    }

    public @Nullable String problem(String text) {
        return problem.apply(text);
    }
}
