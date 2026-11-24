// Russet: the names Sodium's config API gives mods, declared by Russet so mods that build Sodium pages
// work in Russet's menu without Sodium. Only loaded when Sodium isn't installed (SodiumConfigApi).
package net.caffeinemc.mods.sodium.api.config.structure;

import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.caffeinemc.mods.sodium.api.config.option.FlagHook;
import net.minecraft.resources.Identifier;

import java.util.Collection;
import java.util.function.BiConsumer;
import java.util.function.Function;

public interface ModOptionsBuilder {
    ModOptionsBuilder setName(String name);

    ModOptionsBuilder setVersion(String version);

    ModOptionsBuilder formatVersion(Function<String, String> versionFormatter);

    ModOptionsBuilder setColorTheme(ColorThemeBuilder colorTheme);

    ModOptionsBuilder setIcon(Identifier texture);

    ModOptionsBuilder setNonTintedIcon(Identifier texture);

    ModOptionsBuilder addPage(PageBuilder page);

    ModOptionsBuilder registerOptionReplacement(Identifier target, OptionBuilder replacement);

    ModOptionsBuilder registerOptionReplacement(Identifier target, OptionBuilder replacement, int priority);

    ModOptionsBuilder registerOptionOverlay(Identifier target, OptionBuilder overlay);

    ModOptionsBuilder registerOptionOverlay(Identifier target, OptionBuilder overlay, int priority);

    ModOptionsBuilder registerFlagHook(BiConsumer<Collection<Identifier>, ConfigState> hook, Identifier... triggers);

    ModOptionsBuilder registerFlagHook(FlagHook hook);
}
