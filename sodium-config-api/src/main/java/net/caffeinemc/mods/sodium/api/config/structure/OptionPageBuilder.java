// Russet: the names Sodium's config API gives mods, declared by Russet so mods that build Sodium pages
// work in Russet's menu without Sodium. Only loaded when Sodium isn't installed (SodiumConfigApi).
package net.caffeinemc.mods.sodium.api.config.structure;

import net.minecraft.network.chat.Component;

public interface OptionPageBuilder extends PageBuilder {
    OptionPageBuilder setName(Component name);

    OptionPageBuilder addOptionGroup(OptionGroupBuilder group);

    OptionPageBuilder addOption(OptionBuilder option);
}
