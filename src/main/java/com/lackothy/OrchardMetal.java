package com.lackothy;

import com.lackothy.orchard.pack.OrchardPack;
import com.lackothy.orchard.pack.PackDownloads;
import com.lackothy.orchard.video.VideoOptions;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Orchard Metal, for any Fabric launcher (Lunar, Prism and the like): the Metal renderer, the
 * Orchard Pack, Orchard's video settings with Minecraft's pages, the Loading page and the Metal
 * page, and the loading screen. The game fetches the Orchard Pack's packs itself
 * ({@code PackDownloads}).
 */
public final class OrchardMetal implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        VideoOptions.load();
        PackDownloads.start(FabricLoader.getInstance().getGameDir().resolve(OrchardPack.FOLDER));
        Orchard.LOG.info("Orchard Metal loaded");
    }
}
