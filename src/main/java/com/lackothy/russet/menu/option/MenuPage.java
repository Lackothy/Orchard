package com.lackothy.russet.menu.option;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Function;

public record MenuPage(Component name, List<MenuSection> groups, @Nullable Function<Screen, @Nullable Screen> opens) {

    public MenuPage(Component name, List<MenuSection> groups) {
        this(name, groups, null);
    }

    public boolean external() {
        return this.opens != null;
    }
}
