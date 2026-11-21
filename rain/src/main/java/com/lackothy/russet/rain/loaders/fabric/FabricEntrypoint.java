package com.lackothy.russet.rain.loaders.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import com.lackothy.russet.rain.ParticleRain;
import com.lackothy.russet.rain.VersionUtil;
import com.lackothy.russet.rain.ParticleLoader;

public class FabricEntrypoint implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Russet: called from RussetClient, not an entrypoint of its own. Nothing is registered (the
        // streak is made directly), so a LAN world hosted with Russet asks nothing of who joins.
        if (ParticleRain.STOOD_DOWN) {
            ParticleRain.LOGGER.info("Particle Rain is installed, so Russet's copy of it stays off");
            return;
        }
        ParticleRain.onInitializeClient();

        ClientTickEvents.END_CLIENT_TICK.register(ParticleRain::onTick);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ParticleRain.getCommands());
        });
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {

            @Override
            public void onResourceManagerReload(ResourceManager resourceManager) {
                ParticleLoader.onResourceManagerReload(resourceManager);
            }

            @Override
            public Identifier getFabricId() {
                return VersionUtil.getId("reload");
            }
        });
    }
}
