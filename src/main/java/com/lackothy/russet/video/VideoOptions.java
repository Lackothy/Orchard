package com.lackothy.russet.video;

import com.lackothy.Russet;
import com.lackothy.russet.loading.Ending;
import com.lackothy.russet.loading.Loader;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Russet's own video settings, the ones Minecraft has no option for, kept in
 * {@code config/russet.properties}. Each is read where it takes effect, so every getter here is a
 * field read: some run every frame.
 */
public final class VideoOptions {

    private static final List<Setting> ALL = new ArrayList<>();

    public static final Toggle HIDDEN_SEE_THROUGH = toggle("metal.hiddenSeeThrough");
    public static final Choice<Scale> RENDER_SCALE = new Choice<>("metal.renderScale", Scale.FULL);

    public static final Choice<Loader> LOADER = new Choice<>("loading.bar", Loader.COMET);
    public static final Choice<Ending> ENDING = new Choice<>("loading.ending", Ending.SUNRISE);

    private static boolean loaded;

    private VideoOptions() {
    }

    /** How much of the window's size the world is drawn at with Metal, for {@link RenderScale}. */
    public enum Scale {
        FULL(100), P90(90), P80(80), P75(75), P67(67), P50(50);

        public final int percent;
        public final String label;

        Scale(int percent) {
            this.percent = percent;
            this.label = percent + "%";
        }
    }

    private static Toggle toggle(String key) {
        return new Toggle(key, true);
    }

    // ── file ──

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("russet.properties");
    }

    public static synchronized void load() {
        loaded = true;
        Path file = file();
        if (!Files.isRegularFile(file)) {
            return;
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            Russet.LOG.warn("Could not read {}", file, e);
            return;
        }
        for (Setting setting : ALL) {
            String value = properties.getProperty(setting.key);
            if (value != null) {
                setting.read(value.strip());
            }
        }
    }

    public static synchronized void save() {
        if (!loaded) {
            return;
        }
        // Written by hand rather than with Properties.store, so the keys come out in order and
        // without a timestamp: the file only changes when a setting does.
        Map<String, String> lines = new TreeMap<>();
        for (Setting setting : ALL) {
            lines.put(setting.key, setting.write());
        }
        StringBuilder out = new StringBuilder("# Russet's video settings, from Options > Video Settings\n");
        lines.forEach((key, value) -> out.append(key).append('=').append(value).append('\n'));
        Path file = file();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Russet.LOG.warn("Could not save {}", file, e);
        }
    }

    // ── settings ──

    public abstract static class Setting {
        final String key;

        Setting(String key) {
            this.key = key;
            ALL.add(this);
        }

        abstract void read(String value);

        abstract String write();
    }

    public static final class Toggle extends Setting {
        private final boolean initial;
        private volatile boolean on;

        Toggle(String key, boolean initial) {
            super(key);
            this.initial = initial;
            this.on = initial;
        }

        public boolean on() {
            return on;
        }

        public boolean initial() {
            return initial;
        }

        public void set(boolean on) {
            if (this.on != on) {
                this.on = on;
                save();
            }
        }

        @Override
        void read(String value) {
            on = Boolean.parseBoolean(value);
        }

        @Override
        String write() {
            return Boolean.toString(on);
        }
    }

    public static final class Choice<E extends Enum<E>> extends Setting {
        private final E initial;
        private volatile E value;

        Choice(String key, E initial) {
            super(key);
            this.initial = initial;
            this.value = initial;
        }

        public E get() {
            return value;
        }

        public E initial() {
            return initial;
        }

        public E[] values() {
            return initial.getDeclaringClass().getEnumConstants();
        }

        public void set(E value) {
            if (this.value != value) {
                this.value = value;
                save();
            }
        }

        @Override
        void read(String text) {
            for (E e : values()) {
                if (e.name().equalsIgnoreCase(text)) {
                    value = e;
                    return;
                }
            }
        }

        @Override
        String write() {
            return value.name().toLowerCase(Locale.ROOT);
        }
    }
}
