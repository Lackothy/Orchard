package com.lackothy.orchard.menu.option;

import net.minecraft.network.chat.Component;

import java.util.List;

/** A titled run of lines on a page. */
public record MenuSection(Component name, List<MenuOption> options) {
}
