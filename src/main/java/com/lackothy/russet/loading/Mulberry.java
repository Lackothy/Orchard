package com.lackothy.russet.loading;

/** Mulberry32: the small random numbers the loading screen's sparks and leaves are scattered with. */
final class Mulberry {

    private int state;

    Mulberry(int seed) {
        state = seed;
    }

    /** A number from 0 up to 1. */
    double next() {
        state += 0x6D2B79F5;
        int t = (state ^ (state >>> 15)) * (1 | state);
        t = (t + ((t ^ (t >>> 7)) * (61 | t))) ^ t;
        return ((t ^ (t >>> 14)) & 0xFFFFFFFFL) / 4294967296.0;
    }
}
