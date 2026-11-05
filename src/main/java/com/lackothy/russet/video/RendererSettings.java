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

/**
 * The Metal renderer's own settings, which it reads once at start from
 * {@code config/mcopt.properties}. Changing one here rewrites that file, keeping its comments and
 * every other line, and takes effect the next time Minecraft starts.
 */
public final class RendererSettings {

    /** False draws with Minecraft's own graphics API; the rest of the renderer stays. */
    public static final String METAL_BACKEND = "mcopt.metal";
    public static final String FAR_TERRAIN = "mcopt.lod";
    public static final String FAR_TERRAIN_REACH = "mcopt.lod.radius";
    public static final String FAR_TERRAIN_TREES = "mcopt.lod.trees";
    public static final String FAR_TERRAIN_SMALL = "mcopt.lod.small";
    /** The shaders in the renderer (see its NOTICE), off until asked for, and their preset. */
    public static final String SHADERS = "mcopt.shade";
    public static final String SHADER_PRESET = "mcopt.shade.preset";
    public static final String DEFAULT_SHADER_PRESET = "balanced";
    public static final List<String> SHADER_PRESETS = List.of("performance", DEFAULT_SHADER_PRESET, "quality", "ultra");

    /** The preset in the file's {@code saved} line (null without one), or the default for none it knows. */
    static String shaderPreset(String saved) {
        // (a List.of's contains throws on null)
        return saved != null && SHADER_PRESETS.contains(saved) ? saved : DEFAULT_SHADER_PRESET;
    }

    /** The renderer's own default reach, in chunks. */
    public static final int DEFAULT_REACH = 512;

    /** What draws the game: Metal in front of Minecraft's own, or one of those with Metal off. */
    public enum Api {
        METAL("Metal"), OPENGL("OpenGL"), VULKAN("Vulkan (Experimental)");

        public final String label;

        Api(String label) {
            this.label = label;
        }
    }

    /** What draws the game from the next start. */
    public static Api api(Options options) {
        if (flag(METAL_BACKEND, true)) {
            return Api.METAL;
        }
        return options.preferredGraphicsBackend().get() == PreferredGraphicsApi.VULKAN ? Api.VULKAN : Api.OPENGL;
    }

    /**
     * Turns Metal on or off, and sets Minecraft's own choice to match. Minecraft's only orders the
     * APIs behind Metal, so with Metal it goes back to Default, where it can't say "Prefer OpenGL".
     */
    public static void choose(Options options, Api api) {
        set(METAL_BACKEND, api == Api.METAL ? null : "false");
        options.preferredGraphicsBackend().set(switch (api) {
            case METAL -> PreferredGraphicsApi.DEFAULT;
            case OPENGL -> PreferredGraphicsApi.OPENGL;
            case VULKAN -> PreferredGraphicsApi.VULKAN;
        });
    }

    /** What the file said when Minecraft started, so the screen can tell what is waiting for a restart. */
    private static List<String> atStart;

    private RendererSettings() {
    }

    /** Whether all of the renderer is drawing: it is in Russet's jar, off on Mojang's renderer, and only its backend beside Sodium. */
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

    /** The value set for {@code key}, or null when the file leaves it to the renderer. */
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

    /** Sets {@code key}, or with {@code value} null takes its line out. */
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

    /** Whether anything was changed since Minecraft started. */
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
