// Russet: the names Sodium's config API gives mods, declared by Russet so mods that build Sodium pages
// work in Russet's menu without Sodium. Only loaded when Sodium isn't installed (SodiumConfigApi).
package net.caffeinemc.mods.sodium.api.config.option;

import net.minecraft.resources.Identifier;

import java.util.Locale;

public enum OptionFlag {
    REQUIRES_RENDERER_RELOAD,
    REQUIRES_RENDERER_UPDATE,
    REQUIRES_ASSET_RELOAD,
    REQUIRES_VIDEOMODE_RELOAD,
    REQUIRES_GAME_RESTART;

    private final Identifier id = Identifier.fromNamespaceAndPath("sodium", "builtin_option_flag." + name().toLowerCase(Locale.ROOT));

    public Identifier getId() {
        return id;
    }
}
