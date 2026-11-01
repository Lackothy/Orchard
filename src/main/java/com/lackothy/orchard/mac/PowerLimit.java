package com.lackothy.orchard.mac;

import com.lackothy.orchard.video.VideoOptions;
import com.lackothy.orchard.video.VideoOptions.Limit;

public final class PowerLimit {

    private PowerLimit() {
    }

    public static int now() {
        return MacPower.running()
            ? cap(MacPower.state(), VideoOptions.BATTERY_LIMIT.get(), VideoOptions.LOW_POWER_LIMIT.get(), VideoOptions.HOT_LIMIT.get())
            : 0;
    }

    public static int cap(MacPower.State state, Limit battery, Limit lowPower, Limit hot) {
        int cap = 0;
        if (state.battery()) {
            cap = lower(cap, battery.fps);
        }
        if (state.lowPower()) {
            cap = lower(cap, lowPower.fps);
        }
        if (state.hot()) {
            cap = lower(cap, hot.fps);
        }
        return cap;
    }

    public static int apply(int game, int cap) {
        return cap > 0 ? Math.min(game, cap) : game;
    }

    private static int lower(int cap, int fps) {
        if (fps == 0) {
            return cap;
        }
        return cap == 0 ? fps : Math.min(cap, fps);
    }
}
