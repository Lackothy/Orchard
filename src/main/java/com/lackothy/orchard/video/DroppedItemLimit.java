package com.lackothy.orchard.video;

import java.util.Arrays;

/**
 * The Entities page's "Most items drawn": of the items on the ground in view, only the nearest so
 * many are drawn. Each frame's cut-off is the distance of the last one in from the frame before,
 * so it follows the player a frame behind, and an item counts whether or not it was drawn.
 */
public final class DroppedItemLimit {

    /** Squared distances of the items in view this frame. */
    private static float[] seen = new float[1024];
    private static int count;
    /** Squared distance past which items aren't drawn this frame. */
    private static double furthest = Double.POSITIVE_INFINITY;

    private DroppedItemLimit() {
    }

    /** Before a frame's entities are picked. */
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

    /** Whether an item on the ground in view, this far away squared, is drawn. */
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
