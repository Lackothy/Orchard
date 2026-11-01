package com.lackothy.orchard.loading;

import static com.lackothy.orchard.loading.Ease.inOut;
import static com.lackothy.orchard.loading.Ease.lerp;
import static com.lackothy.orchard.loading.Ease.part;

/**
 * What plays once the bar is full, from the loading screen into the menu under it. Each builds
 * the frame from the loading screen's last picture ({@code from}: with the sun, or without it
 * when the ending moves the sun itself) and the sun's last place; what it leaves clear shows the
 * menu, which Minecraft draws underneath.
 */
public enum Ending {

    /** The sun rises into the orchard's sky as the trees grow in under it and the menu fades up. */
    SUNRISE("Sunrise", 1.8, false) {
        @Override
        void compose(Pixels out, Pixels from, Sun last, Frame f, double k, Target target) {
            out.clear();
            out.alpha = 1 - inOut(part(k, 0.1, 0.55));
            out.paint(from);
            out.alpha = 1;
            double m = inOut(part(k, 0, 0.55));
            Sun sun = new Sun(lerp(last.x, target.x(), m), lerp(last.y, target.y(), m) - Math.sin(Math.PI * m) * f.height() * 0.08);
            sun.scale = lerp(last.scale, target.scale(), m);
            sun.clear = target.orchard() ? 1 - part(k, 0.8, 1) : 1 - part(k, 0.45, 0.75);
            sun.draw(out, f);
        }
    },

    /** A gust of leaves and blossom blows across and sweeps the loading screen away. */
    LEAF_GUST("Leaf gust", 1.5, true) {
        @Override
        void compose(Pixels out, Pixels from, Sun last, Frame f, double k, Target target) {
            int s = f.s();
            int front = (int) Math.round(lerp(-30 * s, f.width() + 40 * s, inOut(k)));
            out.clear();
            int x = Math.max(0, front);
            if (x < f.width()) {
                out.paint(from, x, 0, f.width() - x, f.height(), x, 0);
            }
            Mulberry r = new Mulberry(7);
            int[] colors = {f.sky().leaf, f.sky().leafLight, 0xFFF2A7C3, 0xFFFFFFFF};
            double t = f.t();
            for (int i = 0; i < 70; i++) {
                double y0 = r.next() * f.height();
                double spread = r.next();
                double ph = r.next() * 6.28;
                int color = colors[(int) Math.floor(r.next() * 4)];
                double lx = front - spread * 40 * s + Math.sin(t * 6 + ph) * 3 * s;
                double ly = y0 + Math.sin(t * 4 + ph) * 4 * s;
                boolean flat = Math.sin(t * 9 + ph) > 0;
                out.rect((int) Math.round(lx), (int) Math.round(ly), (flat ? 2 : 1) * s, (flat ? 1 : 2) * s, color);
            }
        }
    },

    /** It jumps to the middle, swells and flashes; the menu is behind the flash. */
    BURST("Burst", 1.5, false) {
        @Override
        void compose(Pixels out, Pixels from, Sun last, Frame f, double k, Target target) {
            if (k < 0.62) {
                out.copy(from);
                double m = inOut(part(k, 0, 0.35));
                double g = Ease.in(part(k, 0.3, 0.6));
                Sun sun = new Sun(lerp(last.x, f.width() / 2.0, m),
                    lerp(last.y, f.height() * 0.45, m) - Math.sin(Math.PI * m) * f.height() * 0.12);
                sun.scale = last.scale * (1 + 0.4 * m + 6 * g);
                sun.angle = g > 0 ? 0 : m * Math.PI;
                sun.draw(out, f);
            } else {
                out.clear();
            }
            double flash = k < 0.62 ? part(k, 0.45, 0.62) : 1 - Ease.out(part(k, 0.62, 1));
            if (flash > 0) {
                out.alpha = flash;
                out.rect(0, 0, f.width(), f.height(), 0xFFFFFBE8);
                out.alpha = 1;
            }
        }
    },

    /** The loading screen parts down the middle and slides away to both sides. */
    CURTAIN("Curtain", 1.1, true) {
        @Override
        void compose(Pixels out, Pixels from, Sun last, Frame f, double k, Target target) {
            int s = f.s();
            int half = f.width() / 2;
            int off = (int) Math.round(inOut(k) * half);
            out.clear();
            out.paint(from, 0, 0, half, f.height(), -off, 0);
            out.paint(from, half, 0, f.width() - half, f.height(), half + off, 0);
            // The seam's shadow comes in as the halves part, rather than being there at once.
            out.alpha = part(k, 0, 0.1);
            out.rect(half - off, 0, 2 * s, f.height(), 0x59000000);
            out.rect(half + off - 2 * s, 0, 2 * s, f.height(), 0x59000000);
            out.alpha = 1;
        }
    },

    /** A circle opens out from the sun or moon until the menu fills the screen. */
    IRIS("Iris", 1.3, true) {
        @Override
        void compose(Pixels out, Pixels from, Sun last, Frame f, double k, Target target) {
            out.copy(from);
            double far = 0;
            for (int[] corner : new int[][] {{0, 0}, {f.width(), 0}, {0, f.height()}, {f.width(), f.height()}}) {
                far = Math.max(far, Math.hypot(corner[0] - last.x, corner[1] - last.y));
            }
            double r = Ease.in(k) * far;
            if (r <= 0) {
                return;
            }
            out.disc(last.x, last.y, r + 3 * f.s(), 2 * f.s(), f.sky().fill);
            out.disc(last.x, last.y, r, 2 * f.s(), 0);
        }
    },

    /** The loading screen fades into the menu: the ending under Reduce Motion. */
    FADE("Fade", 0.6, true) {
        @Override
        void compose(Pixels out, Pixels from, Sun last, Frame f, double k, Target target) {
            out.clear();
            out.alpha = 1 - inOut(k);
            out.paint(from);
            out.alpha = 1;
        }
    };

    /** Where the sun goes in the menu's orchard, at what size, and whether the orchard is there at all. */
    record Target(double x, double y, double scale, boolean orchard) {
    }

    public final String label;
    final double seconds;
    /** Whether it starts from the loading screen's picture with the sun in it. */
    final boolean withSun;

    Ending(String label, double seconds, boolean withSun) {
        this.label = label;
        this.seconds = seconds;
        this.withSun = withSun;
    }

    /** Paints frame {@code k} (0 to 1) of the ending into {@code out}. */
    abstract void compose(Pixels out, Pixels from, Sun last, Frame f, double k, Target target);

    /** The ones to choose from: the fade is only for Reduce Motion. */
    public static Ending[] choices() {
        return new Ending[] {SUNRISE, LEAF_GUST, BURST, CURTAIN, IRIS};
    }
}
