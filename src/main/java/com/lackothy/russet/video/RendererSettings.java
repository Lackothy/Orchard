package com.lackothy.russet.video;

import com.lackothy.Russet;
import mcopt.metal.Switch;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Options;
import net.minecraft.client.PreferredGraphicsApi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class RendererSettings {

    public static final String METAL_BACKEND = "mcopt.metal";
    public static final String FAR_TERRAIN = "mcopt.lod";
    public static final String FAR_TERRAIN_REACH = "mcopt.lod.radius";
    public static final String FAR_TERRAIN_TREES = "mcopt.lod.trees";
    public static final String FAR_TERRAIN_SMALL = "mcopt.lod.small";
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

    private static List<String> lines() {
        try {
            return Files.isRegularFile(file()) ? Files.readAllLines(file(), StandardCharsets.UTF_8) : List.of();
        } catch (IOException e) {
            Russet.LOG.warn("Could not read {}", file(), e);
            return List.of();
        }
    }

    public static String get(String key) {
        String value = null;
        for (String line : lines()) {
            String[] pair = split(line);
            if (pair != null && pair[0].equals(key)) {
                value = pair[1];
            }
        }
        return value;
    }

    public static boolean flag(String key, boolean otherwise) {
        String value = get(key);
        return value == null ? otherwise : Boolean.parseBoolean(value);
    }

    public static void set(String key, String value) {
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
