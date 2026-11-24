// Russet: the names Sodium's config API gives mods, declared by Russet so mods that build Sodium pages
// work in Russet's menu without Sodium. Only loaded when Sodium isn't installed (SodiumConfigApi).
package net.caffeinemc.mods.sodium.api.config.option;

import java.util.function.Supplier;

public interface SteppedValidator extends Validator<Integer> {
    int min();

    int max();

    int step();

    default boolean isValueValid(int value) {
        return value >= min() && value <= max() && (value - min()) % step() == 0;
    }

    @Override
    default Integer getValidatedValue(Integer value, Supplier<Integer> defaultValueSupplier) {
        return isValueValid(value) ? value : defaultValueSupplier.get();
    }
}
