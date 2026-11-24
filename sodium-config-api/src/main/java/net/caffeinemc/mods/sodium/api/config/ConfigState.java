// Russet: the names Sodium's config API gives mods, declared by Russet so mods that build Sodium pages
// work in Russet's menu without Sodium. Only loaded when Sodium isn't installed (SodiumConfigApi).
package net.caffeinemc.mods.sodium.api.config;

import net.minecraft.resources.Identifier;

public interface ConfigState {
    Identifier UPDATE_ON_REBUILD = Identifier.parse("__meta__:update_on_rebuild");
    Identifier UPDATE_ON_APPLY = Identifier.parse("__meta__:update_on_apply");

    boolean readBooleanOption(Identifier id);

    int readIntOption(Identifier id);

    <E extends Enum<E>> E readEnumOption(Identifier id, Class<E> enumClass);
}
