package com.lackothy.orchard.loading;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.util.Util;

import java.util.concurrent.CompletableFuture;

/**
 * A load that loads nothing, for the settings to show the loading screen: it fills unevenly over
 * a few seconds, as a real one does, then finishes, and the menu comes back.
 */
public final class Rehearsal implements ReloadInstance {

    private static final double SECONDS = 3.4;
    /** How far along it is at points through its time: slow, a jump, a stall, a run to the end. */
    private static final double[][] STEPS = {
        {0, 0}, {0.12, 0.16}, {0.26, 0.21}, {0.4, 0.47}, {0.55, 0.53}, {0.72, 0.8}, {0.86, 0.86}, {1, 1},
    };

    private final long start = Util.getMillis();
    private final CompletableFuture<Void> done = new CompletableFuture<>();

    /** Plays the loading screen over whatever is open. */
    public static void play(Minecraft minecraft) {
        minecraft.gui.setOverlay(new LoadingOverlay(minecraft, new Rehearsal(), error -> { }, true));
    }

    private double through() {
        return Math.min(1, (Util.getMillis() - start) / 1000.0 / SECONDS);
    }

    @Override
    public CompletableFuture<?> done() {
        if (!done.isDone() && through() >= 1) {
            done.complete(null);
        }
        return done;
    }

    @Override
    public float getActualProgress() {
        double u = through();
        for (int i = 0; i + 1 < STEPS.length; i++) {
            double[] a = STEPS[i];
            double[] b = STEPS[i + 1];
            if (u <= b[0]) {
                double t = (u - a[0]) / (b[0] - a[0]);
                return (float) (a[1] + (b[1] - a[1]) * (-(Math.cos(Math.PI * t) - 1) / 2));
            }
        }
        return 1;
    }
}
