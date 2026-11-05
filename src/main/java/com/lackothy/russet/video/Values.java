package com.lackothy.russet.video;

import java.util.function.Supplier;

public interface Values {

    <T> T get(Object key, Supplier<T> now);

    default boolean on(VideoOptions.Toggle toggle) {
        return get(toggle, toggle::on);
    }

    default <E extends Enum<E>> E get(VideoOptions.Choice<E> choice) {
        return get(choice, choice::get);
    }
}
