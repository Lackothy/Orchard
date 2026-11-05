package com.lackothy;

import com.lackothy.orchard.pack.OrchardPack;
import com.lackothy.orchard.pack.PackDownloads;
import com.lackothy.orchard.video.VideoOptions;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

public final class OrchardMetal implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        VideoOptions.load();
        PackDownloads.start(FabricLoader.getInstance().getGameDir().resolve(OrchardPack.FOLDER));
        Orchard.LOG.info("Orchard Metal loaded");
    }
}
