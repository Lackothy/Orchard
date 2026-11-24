// Russet: the names Sodium's config API gives mods, declared by Russet so mods that build Sodium pages
// work in Russet's menu without Sodium. Only loaded when Sodium isn't installed (SodiumConfigApi).
package net.caffeinemc.mods.sodium.api.config.structure;

import net.minecraft.resources.Identifier;

public interface ConfigBuilder {
    ModOptionsBuilder registerModOptions(String configId, String name, String version);

    ModOptionsBuilder registerModOptions(String configId);

    ModOptionsBuilder registerOwnModOptions();

    ColorThemeBuilder createColorTheme();

    OptionPageBuilder createOptionPage();

    ExternalPageBuilder createExternalPage();

    OptionGroupBuilder createOptionGroup();

    BooleanOptionBuilder createBooleanOption(Identifier id);

    IntegerOptionBuilder createIntegerOption(Identifier id);

    <E extends Enum<E>> EnumOptionBuilder<E> createEnumOption(Identifier id, Class<E> enumClass);

    ExternalButtonOptionBuilder createExternalButtonOption(Identifier id);
}
