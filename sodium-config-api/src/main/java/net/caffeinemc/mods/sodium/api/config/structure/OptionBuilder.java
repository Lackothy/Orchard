// Russet: the names Sodium's config API gives mods, declared by Russet so mods that build Sodium pages
// work in Russet's menu without Sodium. Only loaded when Sodium isn't installed (SodiumConfigApi).
package net.caffeinemc.mods.sodium.api.config.structure;

import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.function.Function;

public interface OptionBuilder {
    OptionBuilder setName(Component name);

    OptionBuilder setTooltip(Component tooltip);

    OptionBuilder setEnabled(boolean available);

    OptionBuilder setEnabledProvider(Function<ConfigState, Boolean> provider, Identifier... dependencies);
}
