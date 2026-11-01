package com.lackothy.orchard.menu.option;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.function.BooleanSupplier;

/** Something to do, such as opening one of Minecraft's screens; {@code label} says what, on the right. */
public final class MenuButton extends MenuOption {

    private final Component label;
    private final Runnable run;

    public MenuButton(Identifier id, Component name, @Nullable Component tooltip, BooleanSupplier enabled,
                      Component label, Runnable run) {
        super(id, name, tooltip, enabled);
        this.label = label;
        this.run = run;
    }

    public Component label() {
        return label;
    }

    public void run() {
        run.run();
    }
}
