package com.lackothy.orchard.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrchardSceneTest {

    @Test
    @DisplayName("the random numbers are the launcher's, so both draw the same orchard")
    void sameSeedsAsTheLauncher() {
        // From SeededRandom in launcher/Sources/Orchard/OrchardScene.swift.
        OrchardScene.Random stars = new OrchardScene.Random(7);
        assertEquals(0.3898297483912715, stars.unit());
        assertEquals(0.01678829452815611, stars.unit());
        assertEquals(0.9007606806068834, stars.unit());
        assertEquals(0.7323499721314938, new OrchardScene.Random(29).unit());
    }

    @Test
    @DisplayName("noon is full daylight under the day sky, midnight none under the night sky")
    void dayAndNight() {
        assertEquals(1, OrchardScene.daylight(12));
        assertEquals(0, OrchardScene.daylight(0));
        assertEquals(0, OrchardScene.daylight(22));
        assertEquals(new OrchardScene.Rgb(0x5B9BEA), OrchardScene.sky(12)[0]);
        assertEquals(new OrchardScene.Rgb(0xBFDDF8), OrchardScene.sky(12)[1]);
        assertEquals(new OrchardScene.Rgb(0x070B1F), OrchardScene.sky(0)[0]);
        assertEquals(OrchardScene.sky(0)[0], OrchardScene.sky(24)[0]);
        assertTrue(OrchardScene.night(23));
        assertFalse(OrchardScene.night(10));
    }

    @Test
    @DisplayName("dawn sits between the night and the day it leads to")
    void dawnBlends() {
        double blue = OrchardScene.sky(6.5)[0].b();
        assertTrue(blue > new OrchardScene.Rgb(0x34407A).b() && blue < new OrchardScene.Rgb(0x5F8FD6).b());
        assertTrue(OrchardScene.daylight(6) > 0 && OrchardScene.daylight(6) < 1);
    }

    @Test
    @DisplayName("the launcher's Background choices draw the hours the launcher shows")
    void launcherChoices() {
        assertEquals(12.0, OrchardScene.hour("day"));
        assertEquals(18.4, OrchardScene.hour("sunset"));
        assertEquals(23.0, OrchardScene.hour("night"));
        assertNull(OrchardScene.hour("time_of_day"));
        assertNull(OrchardScene.hour("dusk"));
        assertNull(OrchardScene.hour(null));
    }

    @Test
    @DisplayName("the launcher's choice fixes the hour, and the screenshot hour wins over it")
    void chosenHour() {
        try {
            System.setProperty("orchard.scene", "sunset");
            assertEquals(18.4, OrchardScene.hour());
            System.setProperty("orchard.sceneHour", "6.5");
            assertEquals(6.5, OrchardScene.hour());
        } finally {
            System.clearProperty("orchard.scene");
            System.clearProperty("orchard.sceneHour");
        }
    }
}
