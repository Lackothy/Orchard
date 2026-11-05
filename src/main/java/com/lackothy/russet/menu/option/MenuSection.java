package com.lackothy.russet.menu.option;

import net.minecraft.network.chat.Component;

import java.util.List;

public record MenuSection(Component name, List<MenuOption> options) {
}
