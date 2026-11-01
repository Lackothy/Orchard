package com.lackothy.orchard.loading;

import java.util.HashMap;
import java.util.Map;

/** A small picture drawn once, in pixels: the sun, the moon, a tree. */
record Sprite(int width, int height, int[] argb) {

    private static final Map<String, Sprite> MADE = new HashMap<>();

    private static final String[] TREE = {
        "...xxxxx...", ".xxxxxxxxx.", "xxxxxxxxxxx", "xxxxxxxxxxx", "xxxxxxxxxxx", ".xxxxxxxxx.", "..xxxxxxx..",
    };
    private static final int[][] FRUIT = {{2, 3}, {7, 2}, {5, 5}, {8, 4}, {3, 5}, {6, 1}};

    /** The sun, eighteen pixels across with its glow, with or without the rays at its corners and sides. */
    static Sprite sun(Sky sky, boolean rays) {
        return MADE.computeIfAbsent("sun" + sky + rays, key -> {
            Sprite s = blank(18, 18);
            s.fill(2, 2, 14, 14, withAlpha(sky.halo, 0.28));
            if (rays) {
                int[][] at = {{8, 0}, {8, 16}, {0, 8}, {16, 8}, {2, 2}, {14, 2}, {2, 14}, {14, 14}};
                for (int[] r : at) {
                    s.fill(r[0], r[1], 2, 2, sky.ray);
                }
            }
            s.fill(4, 4, 10, 10, sky.core);
            s.fill(6, 6, 6, 6, sky.inner);
            return s;
        });
    }

    /** The moon, sixteen pixels across, lit from the right to {@code phase} (0 new, 1 full). */
    static Sprite moon(double phase) {
        int dark = (int) Math.round((1 - Math.round(Math.max(0, Math.min(1, phase)) * 10) / 10.0) * 10);
        return MADE.computeIfAbsent("moon" + dark, key -> {
            Sprite s = blank(16, 16);
            s.fill(1, 1, 14, 14, withAlpha(0xFFCDD8FF, 0.18));
            s.fill(3, 3, 10, 10, 0xFFE8ECF6);
            int crater = 0xFFC3C9D8;
            s.fill(5, 5, 2, 2, crater);
            s.fill(9, 8, 2, 1, crater);
            s.fill(6, 10, 1, 1, crater);
            s.fill(10, 4, 1, 1, crater);
            if (dark > 0) {
                s.fill(3, 3, dark, 10, 0xFF262C48);
            }
            return s;
        });
    }

    enum Fruit { NONE, APPLES, BLOSSOM }

    /** A fruit tree eleven pixels wide and thirteen high, its trunk's foot at the bottom middle. */
    static Sprite tree(Sky sky, Fruit fruit) {
        return MADE.computeIfAbsent("tree" + sky + fruit, key -> {
            Sprite s = blank(11, 13);
            Mulberry random = new Mulberry(11);
            for (int y = 0; y < TREE.length; y++) {
                for (int x = 0; x < TREE[y].length(); x++) {
                    if (TREE[y].charAt(x) == 'x') {
                        s.fill(x, y, 1, 1, random.next() < 0.35 ? sky.leafLight : sky.leaf);
                    }
                }
            }
            s.fill(4, 7, 3, 6, sky.trunk);
            int[] colors = switch (fruit) {
                case NONE -> new int[0];
                case APPLES -> new int[] {sky.apple};
                case BLOSSOM -> new int[] {0xFFF6B8D0, 0xFFFFFFFF};
            };
            for (int i = 0; i < FRUIT.length && colors.length > 0; i++) {
                s.fill(FRUIT[i][0], FRUIT[i][1], 1, 1, colors[i % colors.length]);
            }
            return s;
        });
    }

    private static Sprite blank(int width, int height) {
        return new Sprite(width, height, new int[width * height]);
    }

    private void fill(int x, int y, int w, int h, int color) {
        for (int py = y; py < y + h; py++) {
            for (int px = x; px < x + w; px++) {
                argb[py * width + px] = color;
            }
        }
    }

    static int withAlpha(int color, double alpha) {
        return (int) Math.round(alpha * 255) << 24 | (color & 0xFFFFFF);
    }
}
