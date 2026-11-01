package com.lackothy.orchard.video;

import java.util.Arrays;

public final class DroppedItemLimit {

    private static float[] seen = new float[1024];
    private static int count;
    private static double furthest = Double.POSITIVE_INFINITY;

    private DroppedItemLimit() {
    }

    public static void nextFrame() {
        int most = VideoOptions.DROPPED_ITEMS.get().most;
        if (most == 0 || count <= most) {
            furthest = Double.POSITIVE_INFINITY;
        } else {
            Arrays.sort(seen, 0, count);
            furthest = seen[most - 1];
        }
        count = 0;
    }

    public static boolean shown(double distanceSquared) {
        if (VideoOptions.DROPPED_ITEMS.get().most == 0) {
            return true;
        }
        if (count == seen.length) {
            seen = Arrays.copyOf(seen, count * 2);
        }
        seen[count++] = (float) distanceSquared;
        return distanceSquared <= furthest;
    }
}
