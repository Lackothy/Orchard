// Russet: the names Sodium's config API gives mods, declared by Russet so mods that build Sodium pages
// work in Russet's menu without Sodium. Only loaded when Sodium isn't installed (SodiumConfigApi).
package net.caffeinemc.mods.sodium.client.gui.options.control;

import net.caffeinemc.mods.sodium.api.config.option.ControlValueFormatter;
import net.minecraft.network.chat.Component;

import java.util.function.IntFunction;

public final class ControlValueFormatterImpls {

    private ControlValueFormatterImpls() {
    }

    public static ControlValueFormatter number() {
        return value -> Component.literal(String.valueOf(value));
    }

    public static ControlValueFormatter percentage() {
        return value -> Component.literal(value + "%");
    }

    public static ControlValueFormatter multiplier() {
        return value -> Component.literal(value + "x");
    }

    public static ControlValueFormatter translateVariable(String key) {
        return value -> Component.translatable(key, value);
    }

    public static ControlValueFormatter quantityOrDisabled(IntFunction<Component> quantity, Component disabled) {
        return value -> value == 0 ? disabled : quantity.apply(value);
    }
}
