package com.lackothy.orchard.menu.option;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.function.BooleanSupplier;

public abstract class MenuOption implements OptionExtended {

    private final Identifier id;
    private final Component name;
    private final Component tooltip;
    private final BooleanSupplier enabled;

    MenuOption(Identifier id, Component name, @Nullable Component tooltip, BooleanSupplier enabled) {
        this.id = id;
        this.name = name;
        this.tooltip = tooltip == null ? Component.empty() : tooltip;
        this.enabled = enabled;
    }

    public Identifier getId() {
        return id;
    }

    @Override
    public Identifier rso$getId() {
        return id;
    }

    public Component getName() {
        return name;
    }

    public Component getTooltip() {
        return tooltip;
    }

    public boolean isEnabled() {
        return enabled.getAsBoolean();
    }

    public boolean hasChanged() {
        return false;
    }
}
