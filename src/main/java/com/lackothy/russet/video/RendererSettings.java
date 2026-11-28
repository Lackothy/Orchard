package com.lackothy.russet.video;

import com.lackothy.Russet;
import mcopt.metal.Switch;
import mcopt.metal.lod.LodConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.PreferredGraphicsApi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class RendererSettings {

    public static final String METAL_BACKEND = "mcopt.metal";
    public static final String FAR_TERRAIN = "mcopt.lod";
    public static final String FAR_TERRAIN_REACH = "mcopt.lod.radius";
    public static final String FAR_TERRAIN_TREES = "mcopt.lod.trees";
    public static final String FAR_TERRAIN_SMALL = "mcopt.lod.small";
    public static final String FAR_QUALITY_DISTANCE = "mcopt.lod.qualityDistance";
    public static final String FAR_GLOW = "mcopt.lod.glow";
    public static final int DEFAULT_QUALITY_DISTANCE = 512;
    public static final List<Integer> QUALITY_DISTANCES = List.of(256, 384, 512, 640, 768, 896, 1024);

    public static final List<String> FAR_QUALITIES = List.of("low", "medium", "high", "ultra");
    public static final String CUSTOM_QUALITY = "custom";
    private static final Map<String, String> FAR_DETAILS = Map.of(FAR_GLOW, "ultra");

    public static boolean hasFarDetails() {
        return !FAR_DETAILS.isEmpty();
    }

    public static List<String> farQualities() {
        return FAR_QUALITIES.stream().filter(q -> q.equals(FAR_QUALITIES.get(0)) || FAR_DETAILS.containsValue(q)).toList();
    }

    public static String farQuality() {
        for (String quality : farQualities()) {
            if (FAR_DETAILS.entrySet().stream()
                .allMatch(d -> flag(d.getKey(), false) == has(quality, d.getValue()))) {
                return quality;
            }
        }
        return CUSTOM_QUALITY;
    }

    public static void setFarQuality(String quality) {
        if (!FAR_QUALITIES.contains(quality)) {
            return;
        }
        FAR_DETAILS.forEach((key, from) -> set(key, has(quality, from) ? "true" : null));
        applyFarTerrain();
    }

    private static boolean has(String quality, String from) {
        return FAR_QUALITIES.indexOf(quality) >= FAR_QUALITIES.indexOf(from);
    }

    public static int qualityDistance() {
        try {
            String saved = get(FAR_QUALITY_DISTANCE);
            return saved == null ? DEFAULT_QUALITY_DISTANCE : Integer.parseInt(saved);
        } catch (NumberFormatException e) {
            return DEFAULT_QUALITY_DISTANCE;
        }
    }
    public static final String SHADERS = "mcopt.shade";
    public static final String SHADER_PRESET = "mcopt.shade.preset";
    public static final String DEFAULT_SHADER_PRESET = "balanced";
    public static final List<String> SHADER_PRESETS = List.of("performance", DEFAULT_SHADER_PRESET, "quality", "ultra");

    static String shaderPreset(String saved) {
        return saved != null && SHADER_PRESETS.contains(saved) ? saved : DEFAULT_SHADER_PRESET;
    }

    public static final int DEFAULT_REACH = 512;

    public enum Api {
        METAL("Metal"), OPENGL("OpenGL"), VULKAN("Vulkan (Experimental)");

        public final String label;

        Api(String label) {
            this.label = label;
        }
    }

    public static Api api(Options options) {
        if (flag(METAL_BACKEND, true)) {
            return Api.METAL;
        }
        return options.preferredGraphicsBackend().get() == PreferredGraphicsApi.VULKAN ? Api.VULKAN : Api.OPENGL;
    }

    public static void choose(Options options, Api api) {
        set(METAL_BACKEND, api == Api.METAL ? null : "false");
        options.preferredGraphicsBackend().set(switch (api) {
            case METAL -> PreferredGraphicsApi.DEFAULT;
            case OPENGL -> PreferredGraphicsApi.OPENGL;
            case VULKAN -> PreferredGraphicsApi.VULKAN;
        });
    }

    private static List<String> atStart;

    private RendererSettings() {
    }

    public static boolean present() {
        return Switch.ON;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("mcopt.properties");
    }

    private static List<String> read = List.of();
    private static Map<String, String> values = Map.of();
    private static FileTime readTime;
    private static long readSize = -1;
    private static long checked = Long.MIN_VALUE;
    private static final long CHECK_EVERY = 500_000_000L;

    private static synchronized List<String> lines() {
        long now = System.nanoTime();
        if (readSize != -2 && checked != Long.MIN_VALUE && now - checked < CHECK_EVERY) {
            return read;
        }
        checked = now;
        Path file = file();
        try {
            FileTime time = Files.isRegularFile(file) ? Files.getLastModifiedTime(file) : null;
            long size = time == null ? -1 : Files.size(file);
            if (!Objects.equals(time, readTime) || size != readSize) {
                read = time == null ? List.of() : List.copyOf(Files.readAllLines(file, StandardCharsets.UTF_8));
                Map<String, String> next = new HashMap<>();
                for (String line : read) {
                    String[] pair = split(line);
                    if (pair != null) {
                        next.put(pair[0], pair[1]);
                    }
                }
                values = next;
                readTime = time;
                readSize = size;
            }
            return read;
        } catch (IOException e) {
            Russet.LOG.warn("Could not read {}", file, e);
            return List.of();
        }
    }

    public static synchronized String get(String key) {
        lines();
        return values.get(key);
    }

    public static boolean flag(String key, boolean otherwise) {
        String value = get(key);
        return value == null ? otherwise : Boolean.parseBoolean(value);
    }

    public static synchronized void set(String key, String value) {
        checked = Long.MIN_VALUE;
        if (atStart == null) {
            atStart = lines();
        }
        List<String> out = new ArrayList<>();
        boolean written = false;
        for (String line : lines()) {
            String[] pair = split(line);
            if (pair != null && pair[0].equals(key)) {
                if (value != null && !written) {
                    out.add(key + "=" + value);
                    written = true;
                }
                continue;
            }
            out.add(line);
        }
        if (value != null && !written) {
            out.add(key + "=" + value);
        }
        try {
            Files.createDirectories(file().getParent());
            Files.write(file(), out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Russet.LOG.warn("Could not save {}", file(), e);
        }
        readSize = -2;
    }

    public static void applyFarTerrain() {
        String reach = get(FAR_TERRAIN_REACH);
        int chunks = DEFAULT_REACH;
        if (reach != null) {
            try {
                chunks = Integer.parseInt(reach);
            } catch (NumberFormatException e) {
            }
        }
        boolean was = LodConfig.ENABLED;
        LodConfig.set(flag(FAR_TERRAIN, false) || reach != null, chunks, flag(FAR_TERRAIN_TREES, true));
        LodConfig.setQuality(qualityDistance(), flag(FAR_GLOW, false));
        if (LodConfig.ENABLED != was && Minecraft.getInstance().level != null) {
            Minecraft.getInstance().levelExtractor.allChanged();
        }
    }

    public static boolean changed() {
        return atStart != null && !atStart.equals(lines());
    }

    private static String[] split(String line) {
        String trimmed = line.strip();
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
            return null;
        }
        int at = trimmed.indexOf('=');
        if (at < 0) {
            return null;
        }
        return new String[] {trimmed.substring(0, at).strip(), trimmed.substring(at + 1).strip()};
    }
}
