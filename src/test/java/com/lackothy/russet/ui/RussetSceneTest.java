package com.lackothy.russet.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RussetSceneTest {

    @Test
    @DisplayName("the random numbers stay the same, so the orchard does")
    void seedsStayPut() {
        RussetScene.Random stars = new RussetScene.Random(7);
        assertEquals(0.3898297483912715, stars.unit());
        assertEquals(0.01678829452815611, stars.unit());
        assertEquals(0.9007606806068834, stars.unit());
        assertEquals(0.7323499721314938, new RussetScene.Random(29).unit());
    }

    @Test
    @DisplayName("noon is full daylight under the day sky, midnight none under the night sky")
    void dayAndNight() {
        assertEquals(1, RussetScene.daylight(12));
        assertEquals(0, RussetScene.daylight(0));
        assertEquals(0, RussetScene.daylight(22));
        assertEquals(new RussetScene.Rgb(0x5B9BEA), RussetScene.sky(12)[0]);
        assertEquals(new RussetScene.Rgb(0xBFDDF8), RussetScene.sky(12)[1]);
        assertEquals(new RussetScene.Rgb(0x070B1F), RussetScene.sky(0)[0]);
        assertEquals(RussetScene.sky(0)[0], RussetScene.sky(24)[0]);
        assertTrue(RussetScene.night(23));
        assertFalse(RussetScene.night(10));
    }

    @Test
    @DisplayName("dawn sits between the night and the day it leads to")
    void dawnBlends() {
        double blue = RussetScene.sky(6.5)[0].b();
        assertTrue(blue > new RussetScene.Rgb(0x34407A).b() && blue < new RussetScene.Rgb(0x5F8FD6).b());
        assertTrue(RussetScene.daylight(6) > 0 && RussetScene.daylight(6) < 1);
    }

    @Test
    @DisplayName("russet.scene's names draw their hours")
    void sceneNames() {
        assertEquals(12.0, RussetScene.hour("day"));
        assertEquals(18.4, RussetScene.hour("sunset"));
        assertEquals(23.0, RussetScene.hour("night"));
        assertNull(RussetScene.hour("time_of_day"));
        assertNull(RussetScene.hour("dusk"));
        assertNull(RussetScene.hour(null));
    }

    @Test
    @DisplayName("russet.scene fixes the hour, and russet.sceneHour wins over it")
    void chosenHour() {
        try {
            System.setProperty("russet.scene", "sunset");
            assertEquals(18.4, RussetScene.hour());
            System.setProperty("russet.sceneHour", "6.5");
            assertEquals(6.5, RussetScene.hour());
        } finally {
            System.clearProperty("russet.scene");
            System.clearProperty("russet.sceneHour");
        }
    }
}
