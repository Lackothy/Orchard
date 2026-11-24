package com.lackothy.russet.video;

import com.lackothy.Russet;
import com.lackothy.russet.video.sodium.SodiumConfigApi;
import com.lackothy.russet.video.sodium.SodiumPages;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

public final class GraphicsMods {

    static final List<String> IDS = List.of("entityculling", "moreculling", "sodium-extra", "iris", "continuity",
        "lambdynlights", "immediatelyfast", "enhancedblockentities", "dynamic_fps", "particlerain");

    static final Pages.Theme[] THEMES = {
        new Pages.Theme(0xFFE68EA9, 0xFFF8D5E0, 0xFFC28A9C),
        new Pages.Theme(0xFF6FD1C4, 0xFFC6F0EA, 0xFF86B5AE),
        new Pages.Theme(0xFFB19AE8, 0xFFE3DAF8, 0xFF9E92C2),
        new Pages.Theme(0xFFEE9A72, 0xFFFADAC9, 0xFFC69C86),
        new Pages.Theme(0xFFC8C8C8, 0xFFEDEDED, 0xFFA6A6A6),
    };

    private static @Nullable List<Found> found;
    private static final Map<String, Optional<Identifier>> ICONS = new HashMap<>();
    private static final Map<Object, Runnable> TO_SAVE = new LinkedHashMap<>();
    private static final Map<Object, Runnable> AFTER = new LinkedHashMap<>();

    private record Found(ModContainer mod, Function<Screen, @Nullable Screen> opens) {
    }

    private GraphicsMods() {
    }

    public static List<Pages.Group> groups() {
        if (found == null) {
            found = find();
        }
        List<Pages.Group> groups = new ArrayList<>();
        Set<String> sodium = Set.of();
        if (SodiumConfigApi.loaded()) {
            try {
                groups.addAll(SodiumPages.groups());
                sodium = SodiumPages.modIds();
            } catch (RuntimeException | LinkageError e) {
                Russet.LOG.warn("Couldn't show mods' Sodium pages", e);
            }
        }
        for (Found mod : found) {
            String id = mod.mod().getMetadata().getId();
            if (!sodium.contains(id)) {
                groups.add(new Pages.Group(mod.mod().getMetadata().getName(), Pages.version(id), icon(mod.mod()), theme(id),
                    pages(id, mod.opens())));
            }
        }
        groups.sort(Comparator.comparing(group -> group.name().toLowerCase(Locale.ROOT)));
        return groups;
    }

    private static List<Pages.Page> pages(String id, Function<Screen, @Nullable Screen> opens) {
        List<Pages.Page> own = id.equals(EntityCullingSettings.ID) ? EntityCullingSettings.pages() : ClothSettings.pages(id, opens);
        List<Pages.Page> pages = new ArrayList<>(own == null ? List.of() : own);
        pages.add(new Pages.Page(pages.isEmpty() ? "Settings" : "All settings", List.of(), opens));
        return pages;
    }

    public static void saveLater(Object key, Runnable save) {
        TO_SAVE.put(key, save);
    }

    public static void afterSaving(Object key, Runnable run) {
        AFTER.put(key, run);
    }

    public static void saveChanged() {
        for (Map<Object, Runnable> phase : List.of(TO_SAVE, AFTER)) {
            List<Runnable> runs = List.copyOf(phase.values());
            phase.clear();
            for (Runnable run : runs) {
                try {
                    run.run();
                } catch (RuntimeException | LinkageError e) {
                    Russet.LOG.warn("Couldn't save a mod's settings", e);
                }
            }
        }
    }

    public static Pages.Theme theme(String id) {
        return THEMES[Math.floorMod(id.hashCode(), THEMES.length)];
    }

    private static List<Found> find() {
        Method factoryOf;
        Method create;
        try {
            ClassLoader loader = GraphicsMods.class.getClassLoader();
            factoryOf = Class.forName("com.terraformersmc.modmenu.api.ModMenuApi", false, loader).getMethod("getModConfigScreenFactory");
            create = Class.forName("com.terraformersmc.modmenu.api.ConfigScreenFactory", false, loader).getMethod("create", Screen.class);
        } catch (ReflectiveOperationException | LinkageError e) {
            return List.of();
        }
        List<Found> mods = new ArrayList<>();
        for (EntrypointContainer<Object> container : FabricLoader.getInstance().getEntrypointContainers("modmenu", Object.class)) {
            String id = container.getProvider().getMetadata().getId();
            if (!IDS.contains(id)) {
                continue;
            }
            try {
                Object api = container.getEntrypoint();
                if (api.getClass().getMethod("getModConfigScreenFactory").getDeclaringClass() == factoryOf.getDeclaringClass()) {
                    continue;
                }
                Object factory = factoryOf.invoke(api);
                mods.add(new Found(container.getProvider(), parent -> open(id, create, factory, parent)));
            } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                Russet.LOG.warn("Couldn't find {}'s settings through Mod Menu", id, e);
            }
        }
        mods.sort(Comparator.comparing(mod -> mod.mod().getMetadata().getName().toLowerCase(Locale.ROOT)));
        return mods;
    }

    private static @Nullable Screen open(String id, Method create, Object factory, Screen parent) {
        try {
            return (Screen) create.invoke(factory, parent);
        } catch (InvocationTargetException e) {
            Russet.LOG.warn("{}'s settings screen failed to open", id, e.getCause());
        } catch (ReflectiveOperationException | RuntimeException e) {
            Russet.LOG.warn("{}'s settings screen failed to open", id, e);
        }
        return null;
    }

    private static @Nullable Identifier icon(ModContainer mod) {
        String id = mod.getMetadata().getId();
        return ICONS.computeIfAbsent(id, ignored -> {
            Optional<Path> path = mod.getMetadata().getIconPath(64).flatMap(mod::findPath);
            if (path.isEmpty()) {
                return Optional.empty();
            }
            try (InputStream in = Files.newInputStream(path.get())) {
                Identifier texture = Russet.id("mod_icon/" + id.replaceAll("[^a-z0-9_.-]", "_"));
                Minecraft.getInstance().getTextureManager().register(texture,
                    new DynamicTexture(() -> "Russet: " + id + "'s icon", NativeImage.read(in)));
                return Optional.of(texture);
            } catch (Exception e) {
                Russet.LOG.warn("Couldn't read {}'s icon", id, e);
                return Optional.empty();
            }
        }).orElse(null);
    }
}
