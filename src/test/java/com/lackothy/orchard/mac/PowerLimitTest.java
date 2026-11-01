package com.lackothy.orchard.mac;

import com.lackothy.orchard.mac.MacPower.State;
import com.lackothy.orchard.video.VideoOptions.Limit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PowerLimitTest {

    @Test
    @DisplayName("plugged in and cool, nothing is capped")
    void pluggedIn() {
        assertEquals(0, PowerLimit.cap(State.PLUGGED_IN, Limit.FPS_30, Limit.FPS_30, Limit.FPS_30));
    }

    @Test
    @DisplayName("each limit applies only in its own case")
    void eachCase() {
        assertEquals(30, PowerLimit.cap(new State(true, false, 0), Limit.FPS_30, Limit.FPS_60, Limit.FPS_90));
        assertEquals(60, PowerLimit.cap(new State(false, true, 0), Limit.FPS_30, Limit.FPS_60, Limit.FPS_90));
        assertEquals(90, PowerLimit.cap(new State(false, false, 2), Limit.FPS_30, Limit.FPS_60, Limit.FPS_90));
        assertEquals(0, PowerLimit.cap(new State(false, false, 1), Limit.FPS_30, Limit.FPS_60, Limit.FPS_90));
    }

    @Test
    @DisplayName("the lowest that applies wins, and Off never counts as lowest")
    void lowestWins() {
        assertEquals(45, PowerLimit.cap(new State(true, true, 3), Limit.OFF, Limit.FPS_45, Limit.FPS_60));
        assertEquals(0, PowerLimit.cap(new State(true, true, 3), Limit.OFF, Limit.OFF, Limit.OFF));
    }

    @Test
    @DisplayName("the cap only ever lowers the game's own limit, unlimited included")
    void onlyLowers() {
        assertEquals(30, PowerLimit.apply(60, 30));
        assertEquals(30, PowerLimit.apply(30, 60));
        assertEquals(60, PowerLimit.apply(260, 60));
        assertEquals(144, PowerLimit.apply(144, 0));
    }

    @Test
    @DisplayName("this Mac answers: a thermal state in range, through FFM")
    void readsThisMac() throws Throwable {
        assumeTrue(MacPower.MAC);
        State now = MacPower.readNow();
        assertTrue(now.thermal() >= 0 && now.thermal() <= 3, "thermal " + now.thermal());
        System.out.println("This Mac: " + now);
    }
}
