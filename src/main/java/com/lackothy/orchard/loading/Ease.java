package com.lackothy.orchard.loading;

/** The curves the loading screen moves along, from 0 to 1. */
final class Ease {

    private Ease() {
    }

    static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /** How far {@code k} is from {@code a} to {@code b}, 0 before and 1 after. */
    static double part(double k, double a, double b) {
        return clamp((k - a) / (b - a));
    }

    static double inOut(double t) {
        return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
    }

    static double out(double t) {
        return 1 - Math.pow(1 - t, 3);
    }

    static double in(double t) {
        return t * t * t;
    }

    /** Past 1 and back, as something popping up does. */
    static double back(double t) {
        double c1 = 1.70158;
        return 1 + (c1 + 1) * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2);
    }
}
