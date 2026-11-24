// Russet: the names Sodium's config API gives mods, declared by Russet so mods that build Sodium pages
// work in Russet's menu without Sodium. Only loaded when Sodium isn't installed (SodiumConfigApi).
package net.caffeinemc.mods.sodium.api.config.option;

import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.minecraft.resources.Identifier;

import java.util.Collection;
import java.util.function.BiConsumer;

public interface FlagHook extends BiConsumer<Collection<Identifier>, ConfigState> {
    Collection<Identifier> getTriggers();
}
