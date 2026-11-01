package com.lackothy.orchard.video;

import com.lackothy.Orchard;
import com.lackothy.orchard.loading.Ending;
import com.lackothy.orchard.loading.Loader;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Orchard's own video settings, the ones Minecraft has no option for, kept in
 * {@code config/orchard.properties}. Each is read where it takes effect (the mixins), so every
 * getter here is a field read: they run every frame or every particle.
 */
public final class VideoOptions {

    private static final List<Setting> ALL = new ArrayList<>();

    public static final Toggle ANIMATIONS = toggle("animations");
    public static final Toggle ANIMATE_WATER = toggle("animations.water");
    public static final Toggle ANIMATE_LAVA = toggle("animations.lava");
    public static final Toggle ANIMATE_FIRE = toggle("animations.fire");
    public static final Toggle ANIMATE_PORTAL = toggle("animations.portal");
    public static final Toggle ANIMATE_SCULK = toggle("animations.sculk");
    public static final Toggle ANIMATE_OTHER = toggle("animations.other");

    public static final Toggle FASTER_TRANSPARENCY = toggle("quality.fasterTransparency");
    public static final Toggle HIDDEN_SEE_THROUGH = toggle("metal.hiddenSeeThrough");

    /** How far Minecraft's Render Distance goes, in chunks (its own limit is 32). */
    public static final int RENDER_DISTANCE_LIMIT = 64;

    public static final Toggle PARTICLES = toggle("particles");
    public static final Toggle BLOCK_BREAKING = toggle("particles.blockBreaking");
    public static final Toggle BLOCK_CRACKING = toggle("particles.blockCracking");

    public static final Toggle SKY = toggle("world.sky");
    public static final Toggle SUN_AND_MOON = toggle("world.sunAndMoon");
    public static final Toggle STARS = toggle("world.stars");
    public static final Toggle SUNRISE = toggle("world.sunrise");
    public static final Toggle WEATHER = toggle("world.weather");
    public static final Toggle FOG = toggle("world.fog");
    public static final Toggle FARM_CHANGES = toggle("world.farmChanges");

    public static final Toggle ITEM_FRAMES = toggle("entities.itemFrames");
    public static final Toggle ARMOR_STANDS = toggle("entities.armorStands");
    public static final Toggle PAINTINGS = toggle("entities.paintings");
    public static final Toggle BEACON_BEAMS = toggle("entities.beaconBeams");
    public static final Toggle BAKED_CHESTS = toggle("entities.bakedChests");
    public static final Toggle BAKED_SHULKER_BOXES = toggle("entities.bakedShulkerBoxes");
    public static final Toggle ENCHANTING_BOOKS = toggle("entities.enchantingBooks");
    public static final Toggle PLAYER_NAMES = toggle("entities.playerNames");
    public static final Toggle OTHER_NAMES = toggle("entities.otherNames");
    public static final Toggle ONE_ITEM_PER_STACK = toggle("entities.oneItemPerStack");
    public static final Choice<DroppedItems> DROPPED_ITEMS = new Choice<>("entities.droppedItems", DroppedItems.N256);

    public static final Choice<FrameRate> FRAME_RATE = new Choice<>("overlay.frameRate", FrameRate.OFF);
    public static final Toggle COORDINATES = new Toggle("overlay.coordinates", false);
    public static final Choice<Corner> CORNER = new Choice<>("overlay.corner", Corner.TOP_LEFT);
    public static final Toggle OVERLAY_BACKGROUND = toggle("overlay.background");

    public static final Toggle ADVANCEMENT_TOASTS = toggle("toasts.advancements");
    public static final Toggle RECIPE_TOASTS = toggle("toasts.recipes");
    public static final Toggle SYSTEM_TOASTS = toggle("toasts.system");
    public static final Toggle TUTORIAL_TOASTS = toggle("toasts.tutorials");

    public static final Choice<Limit> BATTERY_LIMIT = new Choice<>("battery.onBattery", Limit.OFF);
    public static final Choice<Limit> LOW_POWER_LIMIT = new Choice<>("battery.lowPower", Limit.FPS_60);
    public static final Choice<Limit> HOT_LIMIT = new Choice<>("battery.hot", Limit.FPS_60);

    public static final Choice<Scale> RENDER_SCALE = new Choice<>("metal.renderScale", Scale.FULL);

    public static final Choice<Loader> LOADER = new Choice<>("loading.bar", Loader.COMET);
    public static final Choice<Ending> ENDING = new Choice<>("loading.ending", Ending.SUNRISE);

    /** Particle types turned off one by one, by id, so ones from mods not loaded now are kept. */
    private static final Set<String> hiddenParticles = new TreeSet<>();
    /** The same, resolved against the registry the first time a particle asks. */
    private static volatile Set<ParticleType<?>> hiddenParticleTypes;

    private static boolean loaded;

    private VideoOptions() {
    }

    public enum FrameRate {
        OFF("Off"), SIMPLE("FPS"), DETAILED("FPS and lows");

        public final String label;

        FrameRate(String label) {
            this.label = label;
        }
    }

    /** A cap on frames a second, for the Battery page; {@code fps} 0 is none. */
    public enum Limit {
        OFF(0, "Off"), FPS_30(30, "30"), FPS_45(45, "45"), FPS_60(60, "60"), FPS_90(90, "90"), FPS_120(120, "120");

        public final int fps;
        public final String label;

        Limit(int fps, String label) {
            this.fps = fps;
            this.label = label;
        }
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

    /** How many items lying on the ground are drawn, nearest first, for {@link DroppedItemLimit}; {@code most} 0 is all. */
    public enum DroppedItems {
        ALL(0, "All"), N512(512, "512"), N256(256, "256"), N128(128, "128"), N64(64, "64");

        public final int most;
        public final String label;

        DroppedItems(int most, String label) {
            this.most = most;
            this.label = label;
        }
    }

    public enum Corner {
        TOP_LEFT("Top left"), TOP_RIGHT("Top right"), BOTTOM_LEFT("Bottom left"), BOTTOM_RIGHT("Bottom right");

        public final String label;

        Corner(String label) {
            this.label = label;
        }

        public boolean right() {
            return this == TOP_RIGHT || this == BOTTOM_RIGHT;
        }

        public boolean bottom() {
            return this == BOTTOM_LEFT || this == BOTTOM_RIGHT;
        }
    }

    private static Toggle toggle(String key) {
        return new Toggle(key, true);
    }

    // ── what the mixins ask ──

    /** Whether particles of {@code type} may be made: all of them on, and this one not turned off. */
    public static boolean shows(ParticleType<?> type) {
        if (!PARTICLES.on) {
            return false;
        }
        Set<ParticleType<?>> hidden = hiddenParticleTypes;
        if (hidden == null) {
            hidden = resolveHidden();
        }
        return hidden.isEmpty() || !hidden.contains(type);
    }

    public static boolean particleShown(Identifier id) {
        return !hiddenParticles.contains(id.toString());
    }

    public static void showParticle(Identifier id, boolean shown) {
        boolean changed = shown ? hiddenParticles.remove(id.toString()) : hiddenParticles.add(id.toString());
        if (changed) {
            hiddenParticleTypes = null;
            save();
        }
    }

    private static Set<ParticleType<?>> resolveHidden() {
        Set<ParticleType<?>> types = Collections.newSetFromMap(new IdentityHashMap<>());
        for (String id : hiddenParticles) {
            Identifier parsed = Identifier.tryParse(id);
            if (parsed != null) {
                BuiltInRegistries.PARTICLE_TYPE.getOptional(parsed).ifPresent(types::add);
            }
        }
        hiddenParticleTypes = types;
        return types;
    }

    // ── file ──

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("orchard.properties");
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
            Orchard.LOG.warn("Could not read {}", file, e);
            return;
        }
        for (Setting setting : ALL) {
            String value = properties.getProperty(setting.key);
            if (value != null) {
                setting.read(value.strip());
            }
        }
        hiddenParticles.clear();
        for (String id : properties.getProperty("particles.hidden", "").split(",")) {
            if (!id.isBlank()) {
                hiddenParticles.add(id.strip());
            }
        }
        hiddenParticleTypes = null;
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
        lines.put("particles.hidden", String.join(",", hiddenParticles));
        StringBuilder out = new StringBuilder("# Orchard's video settings, from Options > Video Settings\n");
        lines.forEach((key, value) -> out.append(key).append('=').append(value).append('\n'));
        Path file = file();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Orchard.LOG.warn("Could not save {}", file, e);
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
