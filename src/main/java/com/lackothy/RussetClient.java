package com.lackothy;

import com.lackothy.russet.pack.PackDownloads;
import com.lackothy.russet.rain.loaders.fabric.FabricEntrypoint;
import com.lackothy.russet.pack.RussetPack;
import com.lackothy.russet.video.VideoOptions;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

public final class RussetClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        VideoOptions.load();
        new FabricEntrypoint().onInitializeClient();
        PackDownloads.start(FabricLoader.getInstance().getGameDir().resolve(RussetPack.FOLDER));
        Russet.LOG.info("Russet loaded");
    }
}
