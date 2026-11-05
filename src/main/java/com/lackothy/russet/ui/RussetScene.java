package com.lackothy.russet.ui;

import java.time.LocalTime;
import java.util.function.DoubleUnaryOperator;

/**
 * The loading screen's backdrop: a blocky orchard on rolling hills at the time of day it is on
 * this Mac, with clouds drifting over, drawn without its sun or moon (the loading bar has its own).
 *
 * <p>Everything is whole blocks of whole pixels, so neighbouring blocks meet on pixel edges. Only
 * the clouds move, and not under the Mac's Reduce Motion ({@link #REDUCE_MOTION}). For screenshots,
 * {@code russet.scene} fixes the time of day and {@code russet.sceneHour} the hour exactly.
 */
public final class RussetScene {

    private static final double[][] KEYFRAMES = {
        {0, 0x070B1F, 0x16224A},
        {5, 0x0D1530, 0x2A2F5C},
        {6, 0x34407A, 0xF3A27A},
        {7.25, 0x5F8FD6, 0xF2D3B0},
        {9, 0x5B9BEA, 0xBFDDF8},
        {16.5, 0x5B9BEA, 0xBFDDF8},
        {18, 0x4A64B0, 0xF4B183},
        {19, 0x2C3570, 0xE77F6B},
        {20.25, 0x121A3D, 0x3B3566},
        {24, 0x070B1F, 0x16224A},
    };

    /** Reduce Motion in the Mac's Accessibility settings, read once, or {@code -Drusset.reduceMotion=true}. */
    public static final boolean REDUCE_MOTION = Boolean.getBoolean("russet.reduceMotion") || macReduceMotion();

    private final double hour;
    private final Rgb skyTop;
    private final Rgb skyBottom;
    /** 1 in full day, 0 at night. */
    private final double daylight;
    /** Peaks at sunrise and sunset. */
    private final double golden;

    private Surface c;
    private double grown = 1;
    private Runnable beforeLand;
    private float width;
    private float height;
    private float b;
    private float horizon;

    RussetScene(double hour) {
        this.hour = hour;
        Rgb[] sky = sky(hour);
        skyTop = sky[0];
        skyBottom = sky[1];
        daylight = daylight(hour);
        golden = Math.max(bump(hour, 6.4), bump(hour, 18.4));
    }

    /**
     * The scene without its sun or moon, as the loading screen's backdrop: trees grown to
     * {@code growth}, and {@code beforeLand} drawn over the sky, behind the hills.
     */
    public static void backdrop(Surface surface, float width, float height, double growth, Runnable beforeLand) {
        RussetScene scene = new RussetScene(hour());
        scene.grown = growth;
        scene.beforeLand = beforeLand;
        scene.paint(surface, width, height, drift());
    }

    /** Wall-clock seconds, so the clouds drift on from where they were the last time. */
    private static double drift() {
        return REDUCE_MOTION ? 0 : System.currentTimeMillis() / 1000.0;
    }

    /** False off a Mac, or when the setting has never been switched on (macOS then has no such key). */
    private static boolean macReduceMotion() {
        try {
            Process read = new ProcessBuilder("/usr/bin/defaults", "read", "com.apple.universalaccess", "reduceMotion")
                .redirectErrorStream(true).start();
            String value = new String(read.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
            return read.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) && read.exitValue() == 0 && value.equals("1");
        } catch (java.io.IOException | RuntimeException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Where the sun (or by night the moon) is: its centre and side, in a scene this size. */
    public static float[] sun(float width, float height) {
        double hour = hour();
        float b = Math.max(6, Math.round(height / 46));
        float horizon = Math.round(height * 0.64f);
        boolean sun = sunUp(hour);
        double progress = sun ? (hour - 5) / 14.5 : ((hour + 24 - 19.5) % 24) / 9.5;
        float x = (float) (width * (0.12 + 0.76 * progress));
        float y = (float) (horizon - Math.sin(progress * Math.PI) * height * 0.5 + b * 1.5);
        return new float[] {x, y, b * (sun ? 3.5f : 3)};
    }

    /** Where the field in front of the orchard starts, in a scene this tall. */
    public static float field(float height) {
        return Math.round(height * 0.64f) + Math.max(6, Math.round(height / 46)) * 5;
    }

    /** Whether the sky shows the sun at this hour, rather than the moon. */
    public static boolean sunUp(double hour) {
        return hour >= 5 && hour < 19.5;
    }

    /** The hour to draw, 0 to 24: {@code russet.sceneHour}, else {@code russet.scene}'s, else the clock's. */
    public static double hour() {
        String fixed = System.getProperty("russet.sceneHour");
        if (fixed != null) {
            try {
                return Double.parseDouble(fixed);
            } catch (NumberFormatException e) {
                // Fall through to russet.scene.
            }
        }
        Double chosen = hour(System.getProperty("russet.scene"));
        if (chosen != null) {
            return chosen;
        }
        LocalTime now = LocalTime.now();
        return now.getHour() + now.getMinute() / 60.0;
    }

    /**
     * The hour {@code russet.scene} names (day, sunset or night), or null to follow the clock, as
     * anything else does.
     */
    static Double hour(String scene) {
        if (scene == null) {
            return null;
        }
        return switch (scene) {
            case "day" -> 12.0;
            case "sunset" -> 18.4;
            case "night" -> 23.0;
            default -> null;
        };
    }

    /** Whether the sky at this hour is dark enough that what sits on it wants light text. */
    public static boolean night(double hour) {
        return daylight(hour) < 0.5;
    }

    private void paint(Surface canvas, float w, float h, double drift) {
        c = canvas;
        width = w;
        height = h;
        b = Math.max(6, Math.round(h / 46));
        horizon = Math.round(h * 0.64f);
        sky();
        clouds(drift);
        if (beforeLand != null) {
            beforeLand.run();
        }
        land();
    }

    /** A terrain colour under this hour's light: dimmer and bluer at night, warmer at the ends of the day. */
    private Rgb lit(Rgb color) {
        double light = 0.26 + 0.74 * daylight;
        return color.scaled(light)
            .mixed(new Rgb(0x2A3C7A).scaled(0.6), 0.22 * (1 - daylight))
            .mixed(new Rgb(0xF19A5B), 0.18 * golden);
    }

    private void block(double x, double y, double w, double h, int color) {
        c.rect((float) x, (float) y, (float) w, (float) h, 0, color);
    }

    // ── sky ──

    private void sky() {
        c.gradient(0, 0, width, horizon, 0, skyTop.argb(1), skyBottom.argb(1));
        block(0, horizon, width, height - horizon, skyBottom.argb(1));

        double night = 1 - daylight;
        if (night > 0.05) {
            Random random = new Random(7);
            for (int i = 0; i < 90; i++) {
                double x = random.unit() * width;
                double y = random.unit() * horizon * 0.8;
                double s = (random.unit() < 0.2 ? 0.34 : 0.22) * b;
                double a = night * (0.35 + 0.65 * random.unit());
                block(Math.round(x), Math.round(y), s, s, new Rgb(0xFFFFFF).argb(a));
            }
        }

    }

    // ── clouds ──

    private void clouds(double drift) {
        Random random = new Random(21);
        Rgb white = new Rgb(0xFFFFFF).mixed(new Rgb(0xFFB89A), 0.35 * golden).scaled(0.35 + 0.65 * daylight);
        int color = white.argb(0.55 + 0.35 * daylight);
        for (int i = 0; i < 6; i++) {
            double w = Math.round(5 + random.unit() * 9) * b;
            double y = Math.round(random.unit() * horizon * 0.55 + b * 2);
            double speed = (0.25 + random.unit() * 0.35) * b / 6;
            double span = width + w * 2;
            double x = (random.unit() * span + drift * speed) % span - w;
            block(Math.round(x), y, w, b, color);
            double topWidth = Math.round(w * (0.4 + random.unit() * 0.3) / b) * b;
            double topX = x + Math.round((w - topWidth) * random.unit() / b) * b;
            block(Math.round(topX), y - b, topWidth, b, color);
        }
    }

    // ── land ──

    private void land() {
        hills(horizon + b, 3, 3, lit(new Rgb(0x6F9F6A)).mixed(skyBottom, 0.45));
        DoubleUnaryOperator backRow = hills(horizon + b * 3, 2, 5, lit(new Rgb(0x4F8E46)));

        // The orchard's back row stands on the near hills, smaller and hazier.
        Random random = new Random(11);
        float small = Math.round(b * 0.62f);
        double x = -small * 2;
        while (x < width) {
            double ground = backRow.applyAsDouble(x + small * 2);
            tree(x, ground, small, random, 0.28);
            x += small * (6 + Math.round(random.unit() * 4));
        }

        // The field, with a path down the middle and the front row either side of it.
        float field = horizon + b * 5;
        block(0, field, width, height - field, lit(new Rgb(0x5DA544)).argb(1));
        block(0, field, width, b, lit(new Rgb(0x79C25A)).argb(1));
        Random shade = new Random(17);
        int columns = (int) (width / b) + 1;
        int rows = (int) ((height - field) / b) + 1;
        for (int row = 1; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                if (shade.unit() < 0.22) {
                    Rgb tone = new Rgb(shade.unit() < 0.5 ? 0x56993F : 0x66AE4B);
                    block(column * b, field + row * b, b, b, lit(tone).argb(1));
                }
            }
        }
        float centre = Math.round(width / 2 / b) * b;
        for (int row = 0; row < rows; row++) {
            float half = Math.round(row * 0.9f + 1) * b;
            block(centre - half, field + row * b, half * 2, b, lit(new Rgb(row == 0 ? 0xB79466 : 0xA4825A)).argb(1));
        }

        Random front = new Random(29);
        double left = centre - b * 5;
        while (left > -b * 6) {
            tree(left - b * 5, field, b, front, 0);
            left -= b * (9 + Math.round(front.unit() * 3));
        }
        double right = centre + b * 5;
        while (right < width + b * 6) {
            tree(right, field, b, front, 0);
            right += b * (9 + Math.round(front.unit() * 3));
        }
    }

    /** A stepped ridge; returns its height at any x, for standing things on it. */
    private DoubleUnaryOperator hills(double base, double amplitude, long seed, Rgb color) {
        Random random = new Random(seed);
        double p0 = random.unit() * 6.3;
        double p1 = random.unit() * 6.3;
        double p2 = random.unit() * 6.3;
        float step = b * 2;
        DoubleUnaryOperator top = x -> {
            double i = Math.floor(x / step);
            double wave = Math.sin(i * 0.21 + p0) * 0.6 + Math.sin(i * 0.47 + p1) * 0.3 + Math.sin(i * 1.1 + p2) * 0.1;
            return base - Math.round(wave * amplitude) * b;
        };
        int argb = color.argb(1);
        for (float x = 0; x < width; x += step) {
            double y = top.applyAsDouble(x);
            block(x, y, step, height - y, argb);
        }
        return top;
    }

    /**
     * A fruit tree five blocks wide whose trunk stands on {@code ground}, with a few apples, one
     * sometimes golden.
     */
    private void tree(double x, double ground, float size, Random random, double haze) {
        // Grown trees scale up from the foot of the trunk. The random numbers are taken the same
        // either way, so every tree comes out the shape it always is.
        double k = grown(x + size * 2.5);
        double foot = x + size * 2.5;
        double trunk = (2 + Math.floor(random.unit() * 1.6)) * size;
        grownBlock(foot, ground, k, x + size * 2, ground - trunk, size, trunk, paint(0x6E4B2E, haze));
        int[] leaves = {0x3F8A36, 0x4B9A3E, 0x367A30};
        double crownTop = ground - trunk - size * 3;
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 5; column++) {
                // Rounded off: the top corners and one bottom corner are left out.
                if ((row == 0 && (column == 0 || column == 4)) || (row == 2 && column == 4 && random.unit() < 0.5)) {
                    continue;
                }
                double cx = x + column * size;
                double cy = crownTop + row * size;
                grownBlock(foot, ground, k, cx, cy, size, size, paint(leaves[(int) (random.unit() * 3) % 3], haze));
                if (row > 0 && random.unit() < 0.16) {
                    int apple = random.unit() < 0.08 ? 0xF2B53A : 0xD7362F;
                    double s = Math.round(size * 0.45f);
                    grownBlock(foot, ground, k, cx + size / 2.0 - s / 2, cy + size / 2.0 - s / 2, s, s, paint(apple, haze));
                }
            }
        }
    }

    /** How grown the tree standing at {@code x} is: trees come up left to right, overshooting a little. */
    private double grown(double x) {
        if (grown >= 1) {
            return 1;
        }
        double k = Math.max(0, Math.min(1, grown * 1.6 - x / width * 0.6));
        double c1 = 1.70158;
        return k <= 0 ? 0 : 1 + (c1 + 1) * Math.pow(k - 1, 3) + c1 * Math.pow(k - 1, 2);
    }

    /** A block of a tree scaled by {@code k} about the tree's foot, edges on whole pixels. */
    private void grownBlock(double footX, double ground, double k, double x, double y, double w, double h, int color) {
        if (k >= 1) {
            block(x, y, w, h, color);
            return;
        }
        if (k <= 0) {
            return;
        }
        double x0 = Math.round(footX + (x - footX) * k);
        double x1 = Math.round(footX + (x + w - footX) * k);
        double y0 = Math.round(ground + (y - ground) * k);
        double y1 = Math.round(ground + (y + h - ground) * k);
        if (x1 > x0 && y1 > y0) {
            block(x0, y0, x1 - x0, y1 - y0, color);
        }
    }

    private int paint(int rgb, double haze) {
        return lit(new Rgb(rgb)).mixed(skyBottom, haze).argb(1);
    }

    // ── time of day ──

    /** The sky's top and bottom colours at this hour. */
    static Rgb[] sky(double hour) {
        double h = ((hour % 24) + 24) % 24;
        for (int i = 0; i + 1 < KEYFRAMES.length; i++) {
            double[] a = KEYFRAMES[i];
            double[] next = KEYFRAMES[i + 1];
            if (h >= a[0] && h <= next[0]) {
                double t = (h - a[0]) / (next[0] - a[0]);
                return new Rgb[] {
                    new Rgb((int) a[1]).mixed(new Rgb((int) next[1]), t),
                    new Rgb((int) a[2]).mixed(new Rgb((int) next[2]), t),
                };
            }
        }
        return new Rgb[] {new Rgb((int) KEYFRAMES[0][1]), new Rgb((int) KEYFRAMES[0][2])};
    }

    static double daylight(double hour) {
        return ramp(hour, 5, 7) * (1 - ramp(hour, 17.5, 19.75));
    }

    private static double ramp(double x, double from, double to) {
        return Math.min(1, Math.max(0, (x - from) / (to - from)));
    }

    private static double bump(double x, double centre) {
        return Math.exp(-Math.pow((x - centre) / 0.9, 2));
    }

    /** A colour as three channels from 0 to 1, for mixing light without rounding at every step. */
    record Rgb(double r, double g, double b) {

        Rgb(int hex) {
            this(((hex >> 16) & 0xFF) / 255.0, ((hex >> 8) & 0xFF) / 255.0, (hex & 0xFF) / 255.0);
        }

        Rgb mixed(Rgb other, double t) {
            return new Rgb(r + (other.r - r) * t, g + (other.g - g) * t, b + (other.b - b) * t);
        }

        Rgb scaled(double k) {
            return new Rgb(r * k, g * k, b * k);
        }

        int argb(double alpha) {
            return channel(alpha) << 24 | channel(r) << 16 | channel(g) << 8 | channel(b);
        }

        private static int channel(double v) {
            return (int) Math.round(Math.max(0, Math.min(1, v)) * 255);
        }
    }

    /** SplitMix64: the same scene every launch, without storing it. */
    static final class Random {
        private long state;

        Random(long seed) {
            state = seed;
        }

        double unit() {
            state += 0x9E3779B97F4A7C15L;
            long z = state;
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            return ((z ^ (z >>> 31)) >>> 11) / (double) (1L << 53);
        }
    }
}
