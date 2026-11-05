package com.lackothy.russet.gametest;

import com.mojang.blaze3d.systems.RenderSystem;
import mcopt.metal.ScreenTimes;
import mcopt.metal.Switch;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * What the screen showed, for frame pacing (-Drusset.tour.frames, on Metal): for each case, every frame presented, when
 * its drawable reached the screen (presentedTime), when it was sent, and the game time it was drawn for, written to
 * frames-&lt;case&gt;.txt beside the screenshots. Smooth motion is game time moving on by the same step at every refresh.
 */
final class ScreenTiming {

    private static final Identifier ID = Identifier.fromNamespaceAndPath("russet-gametest", "timing");

    private ScreenTiming() {
    }

    /** Each case runs 6 s after 2 s to settle. */
    static void run(ClientGameTestContext context, Path out) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getServer().runCommand("time set noon");
            world.getConnection().waitForChunksRender();
            if (!context.computeOnClient(mc -> Switch.ON && RenderSystem.getDevice().getDeviceInfo().backendName().equals("Metal"))) {
                System.out.println("[Russet frames] not on Metal, nothing to measure");
                return;
            }
            // Under Stage Manager the window opens as a thumbnail in the strip, drawn unlike a window played in, and macOS
            // won't let the game bring itself on (SDL_RaiseWindow): whatever runs the test has a second to (System Events can).
            context.waitTicks(20);
            Object[] saved = context.computeOnClient(mc -> new Object[] {
                mc.options.enableVsync().get(), mc.options.framerateLimit().get(), mc.options.inactivityFpsLimit().get()});
            context.runOnClient(mc -> {
                mc.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED); // or the idle limit holds it at 30
                HudElementRegistry.addLast(ID, ScreenTiming::tag);
            });
            String cases = System.getProperty("russet.tour.frames.cases", "on,off60,off");
            for (String name : cases.split(",")) {
                boolean vsync = name.equals("on");
                int limit = name.equals("off60") ? 60 : name.equals("off30") ? 30 : 260;
                context.runOnClient(mc -> {
                    mc.options.enableVsync().set(vsync);
                    mc.options.framerateLimit().set(limit);
                });
                System.out.printf("[Russet frames] settling %s%n", name); // the cue to bring the window on
                context.waitTicks(40);
                context.runOnClient(mc -> ScreenTimes.start());
                context.waitTicks(120);
                int kept = context.computeOnClient(mc -> ScreenTimes.stop());
                context.waitTicks(10); // the last ones' presented handlers
                List<double[]> presents = new ArrayList<>();
                List<String> lines = new ArrayList<>();
                for (int i = 0; i < kept; i++) {
                    double[] present = ScreenTimes.get(i);
                    presents.add(present);
                    lines.add(String.format(Locale.ROOT, "%.6f %.6f %.4f %.6f", present[0], present[1], present[2], present[3]));
                }
                write(out.resolve("frames-" + name + ".txt"), lines);
                System.out.println("[Russet frames] " + name + ": " + summary(presents));
            }
            context.runOnClient(mc -> {
                HudElementRegistry.removeElement(ID);
                mc.options.enableVsync().set((Boolean) saved[0]);
                mc.options.framerateLimit().set((Integer) saved[1]);
                mc.options.inactivityFpsLimit().set((InactivityFpsLimit) saved[2]);
            });
        }
    }

    /**
     * Frames shown (with a presentedTime; none while the window is a Stage Manager thumbnail, so such a case doesn't count),
     * refreshes that showed the frame before again, steps where game time moved on by over 2 ms more or less than the
     * screen did, and the median time from a frame being drawn to the screen.
     */
    static String summary(List<double[]> presents) {
        List<double[]> shown = new ArrayList<>(presents.stream().filter(p -> p[0] > 0).toList());
        shown.sort((a, b) -> Double.compare(a[0], b[0]));
        if (shown.size() < 10) {
            return presents.size() + " presents, " + shown.size() + " reached the screen: not in front, doesn't count";
        }
        double period = 1.0 / 60;
        int repeats = 0;
        int uneven = 0;
        double[] latency = new double[shown.size()];
        for (int i = 0; i < shown.size(); i++) {
            latency[i] = shown.get(i)[0] - shown.get(i)[3];
            if (i == 0) {
                continue;
            }
            double screen = shown.get(i)[0] - shown.get(i - 1)[0];
            double game = (shown.get(i)[2] - shown.get(i - 1)[2]) * 0.05;
            repeats += Math.max(0, (int) Math.round(screen / period) - 1);
            if (Math.abs(game - screen) > 0.002) {
                uneven++;
            }
        }
        Arrays.sort(latency);
        double seconds = shown.get(shown.size() - 1)[0] - shown.get(0)[0];
        return String.format(Locale.ROOT, "%d of %d presents shown over %.1f s, %d refreshes repeated a frame, %d uneven steps, "
            + "drawn to shown %.0f ms", shown.size(), presents.size(), seconds, repeats, uneven, latency[latency.length / 2] * 1e3);
    }

    /** Runs in every frame's HUD, before the frame presents: the game time this frame is drawn for, in ticks. */
    private static void tag(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null) {
            ScreenTimes.tag(minecraft.level.getGameTime() + (double) delta.getGameTimeDeltaPartialTick(true));
        }
    }

    private static void write(Path file, List<String> lines) {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, lines);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
