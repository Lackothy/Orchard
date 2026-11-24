// Russet: the names Sodium's config API gives mods, declared by Russet so mods that build Sodium pages
// work in Russet's menu without Sodium. Only loaded when Sodium isn't installed (SodiumConfigApi).
package net.caffeinemc.mods.sodium.api.config.option;

import net.minecraft.network.chat.Component;

@FunctionalInterface
public interface ControlValueFormatter {
    Component format(int value);
}
