// Russet: the names Sodium's config API gives mods, declared by Russet so mods that build Sodium pages
// work in Russet's menu without Sodium. Only loaded when Sodium isn't installed (SodiumConfigApi).
package net.caffeinemc.mods.sodium.api.config.option;

import java.util.function.Supplier;

public interface Validator<V> {
    V getValidatedValue(V value, Supplier<V> defaultValueSupplier);
}
