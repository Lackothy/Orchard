package com.lackothy.russet.fabulous;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LightmapUniformsTest {

    private static final float[] WHITE = {1, 1, 1};
    private static final float[] TORCH = {1, 0.8F, 0.6F};
    private static final float[] DIM = {0.05F, 0.05F, 0.05F};
    private static final float SEEN = 2F / 255;

    private static LightmapUniforms night(float skyFactor, float brightness, float nightVision) {
        return new LightmapUniforms(skyFactor, 1, nightVision, 0, 0, brightness, TORCH, WHITE, DIM, WHITE);
    }

    @Test
    void aTorchShowsInTheDark() {
        assertTrue(night(0.2F, 0.5F, 0).change(0, 0, 14) > 0.5F);
    }

    @Test
    void fullBrightLeavesNothingToShow() {
        assertEquals(0, night(0.2F, 16, 0).change(0, 0, 15), 1e-6);
    }

    @Test
    void nightVisionLeavesNothingToShow() {
        assertEquals(0, night(0.2F, 0.5F, 1).change(0, 0, 15), 1e-6);
    }

    @Test
    void steadyLeavesNightVisionOut() {
        assertTrue(night(0.2F, 0.5F, 1).steady().change(0, 0, 14) > SEEN);
    }

    @Test
    void daylightUnderOpenSkyLeavesNothingToShow() {
        LightmapUniforms day = night(1, 0.5F, 0);
        assertTrue(day.change(15, 0, 15) < SEEN);
        assertTrue(day.change(0, 0, 15) > SEEN);
    }

    @Test
    void aLightNoBrighterThanTheBlockChangesNothing() {
        assertEquals(0, night(0.2F, 0.5F, 0).change(0, 10, 10), 1e-6);
    }
}
