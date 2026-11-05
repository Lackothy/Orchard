package com.lackothy.russet.loading;

/**
 * One frame of the loading screen: its size in pixels, {@code s} pixels to a sprite pixel, the
 * sky's colours, and seconds since it opened, for whatever twinkles.
 */
record Frame(int width, int height, int s, Sky sky, double t, Memory memory) {

    /** What a bar keeps from frame to frame. */
    static final class Memory {
        int hop = -1;
        double hopFrom;
    }

    /** The bar across the middle, where the bars that run along one draw it. */
    int barX() {
        return (int) Math.round(width * 0.27);
    }

    int barY() {
        return (int) Math.round(height * 0.66);
    }

    int barWidth() {
        return (int) Math.round(width * 0.46);
    }
}
