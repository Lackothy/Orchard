// Russet: the names Sodium's config API gives mods, declared by Russet so mods that build Sodium pages
// work in Russet's menu without Sodium. Only loaded when Sodium isn't installed (SodiumConfigApi).
package net.caffeinemc.mods.sodium.api.config.option;

public record Range(int min, int max, int step) implements SteppedValidator {
    public Range {
        if (min > max) {
            throw new IllegalArgumentException("min " + min + " is above max " + max);
        }
        if (step <= 0) {
            throw new IllegalArgumentException("step " + step + " isn't above 0");
        }
    }
}
