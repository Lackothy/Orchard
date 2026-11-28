package com.lackothy.russet.gametest;

import com.lackothy.russet.video.RendererSettings;
import com.lackothy.russet.video.VideoOptions;
import mcopt.metal.lod.LodConfig;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.InactivityFpsLimit;
import org.lwjgl.sdl.SDLVideo;

final class FarBench {

    private FarBench() {
    }

    private static final long HIDDEN = SDLVideo.SDL_WINDOW_OCCLUDED | SDLVideo.SDL_WINDOW_HIDDEN | SDLVideo.SDL_WINDOW_MINIMIZED;

    static void run(ClientGameTestContext context) {
        if (!LodConfig.LIVE) {
            System.out.println("[Russet far] far terrain can't change live here, nothing to measure");
            return;
        }
        String[] keys = {RendererSettings.FAR_TERRAIN, RendererSettings.FAR_TERRAIN_REACH};
        String[] saved = context.computeOnClient(mc -> new String[] {RendererSettings.get(keys[0]), RendererSettings.get(keys[1])});
        Object[] options = context.computeOnClient(mc -> new Object[] {mc.options.renderDistance().get(), mc.options.enableVsync().get(),
            mc.options.framerateLimit().get(), mc.options.inactivityFpsLimit().get(), VideoOptions.FIX_HIDDEN_WINDOW.on()});
        try (TestSingleplayerContext world = context.worldBuilder()
            .adjustSettings(settings -> settings.setWorldType(settings.getNormalPresetList().getFirst())).create()) {
            world.getServer().runCommand("time set noon");
            world.getServer().runCommand("gamemode spectator @p");
            world.getServer().runCommand("execute as @p at @s run tp @s ~ 140 ~ 0 15");
            context.runOnClient(mc -> mc.options.renderDistance().set(16));
            try {
                world.getConnection().waitForChunksRender(1200);
            } catch (AssertionError e) {
                System.out.println("[Russet far] chunks still rendering after a minute");
            }
            context.runOnClient(mc -> {
                RendererSettings.set(RendererSettings.FAR_TERRAIN, "true");
                RendererSettings.set(RendererSettings.FAR_TERRAIN_REACH, null);
                RendererSettings.applyFarTerrain();
                mc.options.enableVsync().set(false);
                mc.options.framerateLimit().set(260);
                mc.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
                VideoOptions.FIX_HIDDEN_WINDOW.set(false);
            });
            System.out.printf("[Russet far] filling in (small %s, n %d)%n", LodConfig.SMALL, LodConfig.N);
            context.waitTicks(Integer.getInteger("russet.tour.far.settle", 900));
            System.out.println("[Russet far] measuring");
            int ticks = Integer.getInteger("russet.tour.far.measure", 500), hidden = 0;
            for (int i = 0; i < ticks; i++) {
                context.waitTick();
                if (context.computeOnClient(mc -> (SDLVideo.SDL_GetWindowFlags(mc.getWindow().handle()) & HIDDEN) != 0)) {
                    hidden++;
                }
            }
            System.out.printf("[Russet far] done (window hidden %d of %d ticks)%n", hidden, ticks);
        } finally {
            context.runOnClient(mc -> {
                for (int i = 0; i < keys.length; i++) {
                    RendererSettings.set(keys[i], saved[i]);
                }
                RendererSettings.applyFarTerrain();
                mc.options.renderDistance().set((Integer) options[0]);
                mc.options.enableVsync().set((Boolean) options[1]);
                mc.options.framerateLimit().set((Integer) options[2]);
                mc.options.inactivityFpsLimit().set((InactivityFpsLimit) options[3]);
                VideoOptions.FIX_HIDDEN_WINDOW.set((Boolean) options[4]);
            });
        }
    }
}
