package com.lackothy.russet.video.sodium;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

public final class SodiumConfigApi implements PreLaunchEntrypoint {

    private static final Logger LOG = LoggerFactory.getLogger("Russet");
    private static boolean loaded;

    public static boolean loaded() {
        return loaded;
    }

    @Override
    public void onPreLaunch() {
        FabricLoader fabric = FabricLoader.getInstance();
        if (fabric.isModLoaded("sodium")) {
            return;
        }
        try (InputStream in = SodiumConfigApi.class.getResourceAsStream("/META-INF/russet/sodium-config-api.jar")) {
            if (in == null) {
                LOG.warn("Russet's jar has no sodium-config-api.jar, so mods' Sodium pages won't show");
                return;
            }
            byte[] bytes = in.readAllBytes();
            Path dir = fabric.getGameDir().resolve(".fabric").resolve("russet");
            Files.createDirectories(dir);
            Path jar = dir.resolve("sodium-config-api-" + Integer.toHexString(Arrays.hashCode(bytes)) + ".jar");
            if (!Files.exists(jar) || Files.size(jar) != bytes.length) {
                Path part = Files.createTempFile(dir, "sodium-config-api", ".part");
                Files.write(part, bytes);
                Files.move(part, jar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            FabricLauncherBase.getLauncher().addToClassPath(jar);
            loaded = true;
        } catch (IOException | RuntimeException | LinkageError e) {
            LOG.warn("Couldn't load Russet's copy of Sodium's config API names, so mods' Sodium pages won't show", e);
        }
    }
}
