package com.lackothy.orchard.video;

import com.lackothy.orchard.video.VideoOptions.Corner;
import com.lackothy.orchard.video.VideoOptions.FrameRate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The frame rate and coordinates in a corner of the screen, from the Overlay page. The lows are
 * measured here, from the time between HUD frames over the last few seconds, rather than taken
 * from the debug screen, so they are there without it.
 */
public final class FrameOverlay {

    /** About three seconds at 300 FPS. */
    private static final int SAMPLES = 1024;
    private static final long WINDOW_NANOS = 3_000_000_000L;
    private static final int MARGIN = 2;
    private static final int PADDING = 2;

    private static final long[] times = new long[SAMPLES];
    private static final long[] durations = new long[SAMPLES];
    private static int next;
    private static long last;
    private static long statsAt;
    private static String stats = "";

    private FrameOverlay() {
    }

    public static void draw(GuiGraphicsExtractor graphics) {
        long now = System.nanoTime();
        if (last != 0) {
            times[next] = now;
            durations[next] = now - last;
            next = (next + 1) % SAMPLES;
        }
        last = now;

        FrameRate rate = VideoOptions.FRAME_RATE.get();
        Minecraft minecraft = Minecraft.getInstance();
        if (rate == FrameRate.OFF && !VideoOptions.COORDINATES.on()
            || minecraft.gui.hud.isHidden() || minecraft.getDebugOverlay().showDebugScreen()) {
            return;
        }
        List<String> lines = new ArrayList<>(2);
        if (rate == FrameRate.SIMPLE) {
            lines.add(minecraft.getFps() + " FPS");
        } else if (rate == FrameRate.DETAILED) {
            // Worked out twice a second, so the numbers can be read.
            if (now - statsAt > 500_000_000L) {
                statsAt = now;
                stats = detailed(minecraft.getFps(), now);
            }
            lines.add(stats);
        }
        if (VideoOptions.COORDINATES.on() && minecraft.getCameraEntity() != null) {
            BlockPos pos = minecraft.getCameraEntity().blockPosition();
            lines.add("XYZ " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
        }
        draw(graphics, minecraft.font, lines);
    }

    private static String detailed(int fps, long now) {
        long[] recent = new long[SAMPLES];
        int count = 0;
        for (int i = 0; i < SAMPLES; i++) {
            if (times[i] != 0 && now - times[i] <= WINDOW_NANOS) {
                recent[count++] = durations[i];
            }
        }
        if (count == 0) {
            return fps + " FPS";
        }
        Arrays.sort(recent, 0, count);
        long total = 0;
        for (int i = 0; i < count; i++) {
            total += recent[i];
        }
        // The 1% low: the frame rate the slowest one frame in a hundred ran at.
        long slow = recent[Math.min(count - 1, (int) Math.floor(count * 0.99))];
        int low = slow > 0 ? (int) Math.round(1e9 / slow) : fps;
        return String.format("%d FPS  1%% low %d  %.1f ms", fps, low, total / (double) count / 1e6);
    }

    private static void draw(GuiGraphicsExtractor graphics, Font font, List<String> lines) {
        Corner corner = VideoOptions.CORNER.get();
        int lineHeight = font.lineHeight + 1;
        int height = lines.size() * lineHeight - 1;
        int y = corner.bottom() ? graphics.guiHeight() - MARGIN - PADDING - height : MARGIN + PADDING;
        for (String line : lines) {
            int width = font.width(line);
            int x = corner.right() ? graphics.guiWidth() - MARGIN - PADDING - width : MARGIN + PADDING;
            if (VideoOptions.OVERLAY_BACKGROUND.on()) {
                // As the debug screen does: each line on its own plate, the plates touching.
                graphics.fill(x - PADDING, y - 1, x + width + PADDING, y + lineHeight - 1, 0x90505050);
            }
            graphics.text(font, line, x, y, 0xFFFFFFFF, !VideoOptions.OVERLAY_BACKGROUND.on());
            y += lineHeight;
        }
    }
}
