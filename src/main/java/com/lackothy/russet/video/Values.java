package com.lackothy.russet.video;

import java.util.function.Supplier;

/**
 * What other settings are set to, for a row that depends on them: as they are now, or as the
 * video settings hold them until Apply, so a part greys out as soon as what it is part of goes off.
 * Settings are found by the {@link Row#key()} of the row that sets them.
 */
public interface Values {

    /** The value of the setting {@code key}, or {@code now} when nothing holds it. */
    <T> T get(Object key, Supplier<T> now);

    default boolean on(VideoOptions.Toggle toggle) {
        return get(toggle, toggle::on);
    }

    default <E extends Enum<E>> E get(VideoOptions.Choice<E> choice) {
        return get(choice, choice::get);
    }
}
