package com.lackothy.orchard.loading;

/** Where the sun (or moon) is in a frame and how it is turned and squashed, for drawing it and for the ending to start from. */
final class Sun {

    double x;
    double y;
    double angle;
    double sx = 1;
    double sy = 1;
    double scale = 1;
    double clear = 1;
    double phase = 1;
    boolean rays = true;
    /** Drawn by the bar itself, as part of its picture. */
    boolean selfDrawn;
    /** What it bounces on, for its shadow; NaN for nothing. */
    double ground = Double.NaN;

    Sun(double x, double y) {
        this.x = x;
        this.y = y;
    }

    void draw(Pixels pixels, Frame f) {
        pixels.sprite(f.sky().sprite(rays, phase), x, y, angle, sx, sy, clear, scale * f.s());
    }

    /** A shadow under it on its ground, narrower the higher it is. */
    void shadow(Pixels pixels, Frame f) {
        if (Double.isNaN(ground)) {
            return;
        }
        int s = f.s();
        double half = sy * 9 * s * scale;
        double air = Math.max(0, ground - (y + half));
        int w = Math.max(2 * s, (int) Math.round((12 - air / s * 0.6) * s));
        pixels.rect((int) Math.round(x - w / 2.0), (int) ground - s, w, s, 0x66000000);
    }
}
