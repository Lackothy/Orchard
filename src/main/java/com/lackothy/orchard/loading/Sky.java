package com.lackothy.orchard.loading;

import com.lackothy.orchard.ui.OrchardScene;

/**
 * The loading screen's colours for the time of day the orchard behind it shows: a yellow sun by
 * day, an orange one at sunrise and sunset, the moon at night.
 */
enum Sky {
    DAY(0xFFFFE27A, 0xFFFFD34D, 0xFFFFF0A6, 0xFFFFFFFF, 0x59000000, 0xFFFFD65A, 0xFFFFE58A,
        0xFF3F8F3A, 0xFF4FA547, 0xFF6B4A2B, 0xFFD6362E),
    SUNSET(0xFFFF7A3D, 0xFFFF9440, 0xFFFFB35C, 0xFFFFE2A6, 0x66000000, 0xFFFF9C4A, 0xFFFFB066,
        0xFF355F2C, 0xFF41723A, 0xFF4F3520, 0xFFC22F2A),
    NIGHT(0, 0, 0, 0, 0x24FFFFFF, 0xFFCFD8FF, 0xFFDFE6FF,
        0xFF16301B, 0xFF1B3A21, 0xFF2A1E14, 0xFF6B1F22);

    final int halo;
    final int ray;
    final int core;
    final int inner;
    /** The bar's empty part, and its filled part. */
    final int track;
    final int fill;
    final int spark;
    final int leaf;
    final int leafLight;
    final int trunk;
    final int apple;

    Sky(int halo, int ray, int core, int inner, int track, int fill, int spark, int leaf, int leafLight, int trunk,
        int apple) {
        this.halo = halo;
        this.ray = ray;
        this.core = core;
        this.inner = inner;
        this.track = track;
        this.fill = fill;
        this.spark = spark;
        this.leaf = leaf;
        this.leafLight = leafLight;
        this.trunk = trunk;
        this.apple = apple;
    }

    /** The sky now: the moon whenever the orchard shows it, the evening sun in the hours either side of night. */
    static Sky now() {
        double hour = OrchardScene.hour();
        if (!OrchardScene.sunUp(hour)) {
            return NIGHT;
        }
        return hour < 7 || hour >= 17 ? SUNSET : DAY;
    }

    Sprite sprite(boolean rays, double phase) {
        return this == NIGHT ? Sprite.moon(phase) : Sprite.sun(this, rays);
    }
}
