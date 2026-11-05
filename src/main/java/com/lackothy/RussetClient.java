package com.lackothy;

import com.lackothy.russet.pack.PackDownloads;
import com.lackothy.russet.pack.RussetPack;
import com.lackothy.russet.video.VideoOptions;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Russet, for any Fabric launcher (Lunar, Prism and the like): the Metal renderer, the
 * Russet Pack, the video settings with Minecraft's pages, the Loading page and the Metal
 * page, and the loading screen. The game fetches the Russet Pack's packs itself
 * ({@code PackDownloads}).
 */
public final class RussetClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        VideoOptions.load();
        PackDownloads.start(FabricLoader.getInstance().getGameDir().resolve(RussetPack.FOLDER));
        Russet.LOG.info("Russet loaded");
    }
}
