// From Reese's Sodium Options 2.2.5 by FlashyReese (MIT, see licenses/reeses-sodium-options.txt), modified for Russet.
package com.lackothy.russet.menu.state;

import org.jetbrains.annotations.Nullable;

public final class Holder<T> {
    private @Nullable T value;

    public Holder(@Nullable T value) {
        this.value = value;
    }

    public @Nullable T get() {
        return this.value;
    }

    public T getOrDefault(T fallback) {
        return this.value != null ? this.value : fallback;
    }

    public void set(@Nullable T value) {
        this.value = value;
    }
}
