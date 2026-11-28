package com.lackothy.russet.gametest;

import com.lackothy.russet.video.RendererSettings;
import com.lackothy.russet.video.VideoOptions;
import mcopt.metal.lod.LodConfig;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import org.lwjgl.sdl.SDLVideo;

final class LeafBench {

    private LeafBench() {
    }

    private static final long HIDDEN = SDLVideo.SDL_WINDOW_OCCLUDED | SDLVideo.SDL_WINDOW_HIDDEN | SDLVideo.SDL_WINDOW_MINIMIZED;
    private static final Path OUT = Path.of(System.getProperty("russet.screenshots", "russet-screenshots"));
    private static final List<String> VIEWS = List.of(System.getProperty("russet.tour.leaves.views", "above,close,inside").split(","));
    private static final List<String> CASES = List.of(System.getProperty("russet.tour.leaves.cases",
        "as set,smart leaves off,leaves opaque,far terrain off").replace('_', ' ').split(","));
    private static final String[] KEYS = {RendererSettings.FAR_TERRAIN, RendererSettings.FAR_TERRAIN_REACH, RendererSettings.FAR_GLOW};

    static void run(ClientGameTestContext context) {
        String[] saved = context.computeOnClient(mc -> {
            String[] values = new String[KEYS.length];
            for (int i = 0; i < KEYS.length; i++) {
                values[i] = RendererSettings.get(KEYS[i]);
            }
            return values;
        });
        Object[] options = context.computeOnClient(mc -> new Object[] {mc.options.renderDistance().get(), mc.options.enableVsync().get(),
            mc.options.framerateLimit().get(), mc.options.inactivityFpsLimit().get(), VideoOptions.FIX_HIDDEN_WINDOW.on(),
            mc.options.cutoutLeaves().get(), VideoOptions.SMART_LEAVES.on(), mc.options.biomeBlendRadius().get(), mc.options.mipmapLevels().get(),
            mc.options.particles().get(), mc.options.simulationDistance().get()});
        try (TestSingleplayerContext world = context.worldBuilder()
            .adjustSettings(settings -> settings.setWorldType(settings.getNormalPresetList().getFirst())).create()) {
            world.getServer().runCommand("time set noon");
            world.getServer().runCommand("gamemode spectator @p");
            BlockPos swamp = find(world, Biomes.MANGROVE_SWAMP);
            if (swamp == null) {
                System.out.println("[Russet leaves] no mangrove swamp within 6400 blocks");
                return;
            }
            System.out.println("[Russet leaves] mangrove swamp at " + swamp.toShortString());
            context.runOnClient(mc -> {
                mc.options.renderDistance().set(16);
                mc.options.enableVsync().set(false);
                mc.options.framerateLimit().set(260);
                mc.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
                VideoOptions.FIX_HIDDEN_WINDOW.set(false);
                mc.options.cutoutLeaves().set(true);
                VideoOptions.SMART_LEAVES.set(true);
                mc.options.biomeBlendRadius().set(7);
                mc.options.mipmapLevels().set(4);
                mc.options.particles().set(ParticleStatus.ALL);
                mc.options.simulationDistance().set(12);
                RendererSettings.set(RendererSettings.FAR_TERRAIN, "true");
                RendererSettings.set(RendererSettings.FAR_TERRAIN_REACH, "128");
                RendererSettings.set(RendererSettings.FAR_GLOW, "true");
                RendererSettings.applyFarTerrain();
            });
            context.getInput().resizeWindow(Integer.getInteger("russet.tour.leaves.width", 2400),
                Integer.getInteger("russet.tour.leaves.height", 1500));
            context.runOnClient(mc -> {
                SDLVideo.SDL_SetWindowAlwaysOnTop(mc.getWindow().handle(), true);
                SDLVideo.SDL_RaiseWindow(mc.getWindow().handle());
            });
            world.getServer().runCommand("tp @p " + swamp.getX() + " 150 " + swamp.getZ());
            chunks(context, world);
            String at = "execute positioned " + swamp.getX() + " 0 " + swamp.getZ() + " positioned over ";
            view(context, world, "above", at + "motion_blocking run tp @p ~ ~45 ~ 0 30");
            view(context, world, "close", at + "motion_blocking run tp @p ~ ~6 ~ 0 40");
            view(context, world, "inside", at + "motion_blocking_no_leaves run tp @p ~ ~1.6 ~ 0 0");
            fly(context, world, "fly-swamp", swamp);
            if (VIEWS.contains("fly-plains")) {
                BlockPos plains = find(world, Biomes.PLAINS);
                if (plains != null) {
                    fly(context, world, "fly-plains", plains);
                }
            }
        } finally {
            context.runOnClient(mc -> {
                for (int i = 0; i < KEYS.length; i++) {
                    RendererSettings.set(KEYS[i], saved[i]);
                }
                RendererSettings.applyFarTerrain();
                mc.options.renderDistance().set((Integer) options[0]);
                mc.options.enableVsync().set((Boolean) options[1]);
                mc.options.framerateLimit().set((Integer) options[2]);
                mc.options.inactivityFpsLimit().set((InactivityFpsLimit) options[3]);
                VideoOptions.FIX_HIDDEN_WINDOW.set((Boolean) options[4]);
                mc.options.cutoutLeaves().set((Boolean) options[5]);
                VideoOptions.SMART_LEAVES.set((Boolean) options[6]);
                mc.options.biomeBlendRadius().set((Integer) options[7]);
                mc.options.mipmapLevels().set((Integer) options[8]);
                mc.options.particles().set((ParticleStatus) options[9]);
                mc.options.simulationDistance().set((Integer) options[10]);
            });
            context.runOnClient(mc -> SDLVideo.SDL_SetWindowAlwaysOnTop(mc.getWindow().handle(), false));
            context.getInput().resizeWindow(854, 480);
        }
    }

    private static void view(ClientGameTestContext context, TestSingleplayerContext world, String view, String tp) {
        if (!VIEWS.contains(view)) {
            return;
        }
        world.getServer().runCommand(tp);
        chunks(context, world);
        context.waitTicks(Integer.getInteger("russet.tour.leaves.settle", 900));
        measure(context, view, "as set");
        if (CASES.contains("smart leaves off")) {
            leaves(context, world, true, false);
            measure(context, view, "smart leaves off");
        }
        if (CASES.contains("leaves opaque")) {
            leaves(context, world, false, true);
            measure(context, view, "leaves opaque");
        }
        leaves(context, world, true, true);
        if (!CASES.contains("far terrain off")) {
            return;
        }
        farTerrain(context, false);
        context.waitTicks(40);
        measure(context, view, "far terrain off");
        farTerrain(context, true);
        context.waitTicks(Integer.getInteger("russet.tour.leaves.settle", 600));
    }

    private static void fly(ClientGameTestContext context, TestSingleplayerContext world, String view, BlockPos pos) {
        if (!VIEWS.contains(view)) {
            return;
        }
        String start = "tp @p " + (pos.getX() - 150) + " 200 " + pos.getZ() + " -90 25";
        Runnable step = () -> world.getServer().runCommand("tp @p ~1 ~ ~");
        for (String name : List.of("new terrain", "as set", "smart leaves off", "far terrain off")) {
            if (name.contains(" off") && !CASES.contains(name)) {
                continue;
            }
            if (name.equals("smart leaves off")) {
                leaves(context, world, true, false);
            } else if (name.equals("far terrain off")) {
                farTerrain(context, false);
            }
            world.getServer().runCommand(start);
            chunks(context, world);
            context.waitTicks(Integer.getInteger("russet.tour.leaves.settle", 900) / 3);
            measure(context, view, name, step);
            if (name.equals("smart leaves off")) {
                leaves(context, world, true, true);
            } else if (name.equals("far terrain off")) {
                farTerrain(context, true);
            }
        }
    }

    private static void farTerrain(ClientGameTestContext context, boolean on) {
        context.runOnClient(mc -> {
            RendererSettings.set(RendererSettings.FAR_TERRAIN, on ? "true" : null);
            RendererSettings.set(RendererSettings.FAR_TERRAIN_REACH, on ? "128" : null);
            RendererSettings.applyFarTerrain();
        });
    }

    private static BlockPos find(TestSingleplayerContext world, ResourceKey<Biome> biome) {
        return world.getServer().computeOnServer(server -> {
            var found = server.overworld().findClosestBiome3d(b -> b.is(biome), server.overworld().getRespawnData().pos(), 6400, 32, 64);
            return found == null ? null : found.getFirst();
        });
    }

    private static void leaves(ClientGameTestContext context, TestSingleplayerContext world, boolean cutout, boolean smart) {
        context.runOnClient(mc -> {
            mc.options.cutoutLeaves().set(cutout);
            VideoOptions.SMART_LEAVES.set(smart);
            mc.levelExtractor.allChanged();
        });
        chunks(context, world);
    }

    private static void chunks(ClientGameTestContext context, TestSingleplayerContext world) {
        try {
            world.getConnection().waitForChunksRender(1200);
        } catch (AssertionError e) {
            System.out.println("[Russet leaves] chunks still rendering after a minute");
        }
    }

    private static long serverCpu(ThreadMXBean threads) {
        for (ThreadInfo info : threads.getThreadInfo(threads.getAllThreadIds())) {
            if (info != null && info.getThreadName().equals("Server thread")) {
                return threads.getThreadCpuTime(info.getThreadId());
            }
        }
        return 0;
    }

    private static void measure(ClientGameTestContext context, String view, String name) {
        measure(context, view, name, () -> { });
    }

    private static void measure(ClientGameTestContext context, String view, String name, Runnable tick) {
        context.waitTicks(40);
        int ticks = Integer.getInteger("russet.tour.leaves.measure", 200), hidden = 0;
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        long render0 = context.computeOnClient(mc -> threads.getCurrentThreadCpuTime()), server0 = serverCpu(threads), process0 = os.getProcessCpuTime();
        long fps = 0, start = System.currentTimeMillis();
        for (int i = 0; i < ticks; i++) {
            tick.run();
            context.waitTick();
            fps += context.computeOnClient(mc -> mc.getFps());
            if (context.computeOnClient(mc -> (SDLVideo.SDL_GetWindowFlags(mc.getWindow().handle()) & HIDDEN) != 0)) {
                hidden++;
            }
        }
        long end = System.currentTimeMillis(), wall = Math.max(1, end - start);
        long render = context.computeOnClient(mc -> threads.getCurrentThreadCpuTime()) - render0, server = serverCpu(threads) - server0;
        double frames = Math.max(1, fps / (double) ticks * wall / 1000.0);
        System.out.printf("[Russet leaves] case %s / %s from %d to %d: %d fps (window hidden %d of %d ticks, small %s); render thread %.1f ms CPU "
                + "a frame, server thread %.0f%% of a core, the game %.1f cores%n", view, name, start, end, fps / ticks, hidden, ticks, LodConfig.SMALL,
            render / 1e6 / frames, server / 1e4 / wall, (os.getProcessCpuTime() - process0) / 1e6 / wall);
        context.takeScreenshot(TestScreenshotOptions.of("leaves-" + view + "-" + name.replace(' ', '-')).disableCounterPrefix()
            .withDestinationDir(OUT));
    }
}
