package com.lackothy.russet.rain.config;

import com.google.gson.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import com.lackothy.russet.rain.ParticleRain;
import com.lackothy.russet.rain.config.gui.MainConfigScreen;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

public class ConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    // Russet: config/russet-rain, beside Russet's own settings. Looked up when used, so tests can
    // load the particles without Fabric Loader.
    public static Path folder() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("russet-rain");
    }

    private static String configPath() {
        return folder().resolve("config.json").toString();
    }
    private static ConfigData config;

    public static Screen screenPlease(Screen lastScreen) {
        return new MainConfigScreen(lastScreen, getConfig(), getDefaultConfig(), Component.translatable("particlerain.title"));
    }

    public static ConfigData getConfig() {
        if (config == null) load();
        return config;
    }

    public static ConfigData getDefaultConfig() {
        return new ConfigData();
    }

    public static void load() {
        ParticleRain.LOGGER.info("Loading config");
        File file = new File(configPath());
        if (file.exists()) {
            try (FileReader reader = new FileReader(file)) {
                config = GSON.fromJson(reader, ConfigData.class);
            } catch (Exception e) {
                ParticleRain.LOGGER.error("Error loading config: {}", e.getMessage());
                config = getDefaultConfig();
                save();
            }
        } else {
            ParticleRain.LOGGER.info("Creating config file at " + configPath());
            config = getDefaultConfig();
            save();
        }
        if (getConfig().configVersion < getDefaultConfig().configVersion) {
            ParticleRain.LOGGER.info("Overwriting old config file");
            config = getDefaultConfig();
            save();
        }
       getConfig().updateTransientVariables();
    }

    public static void save() {
        try {
            Files.createDirectories(folder());
        } catch (IOException e) {
            ParticleRain.LOGGER.error("Couldn't create directory '{}'", folder(), e);
        }
        try (FileWriter writer = new FileWriter(configPath())) {
           getConfig().updateTransientVariables();
           GSON.toJson(getConfig(), writer);
        } catch (IOException e) {
            ParticleRain.LOGGER.error("Couldn't save config", e);
        }
    }
}