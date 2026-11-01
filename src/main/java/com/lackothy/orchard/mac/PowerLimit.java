package com.lackothy.orchard.mac;

import com.lackothy.orchard.video.VideoOptions;
import com.lackothy.orchard.video.VideoOptions.Limit;

/**
 * The Battery page's limits on the frame rate: one on battery, one in Low Power Mode, one while
 * the Mac is hot enough to slow itself down. The lowest that applies wins, and it only ever
 * lowers the game's own limit ({@code FramerateLimitTrackerMixin}).
 */
public final class PowerLimit {

    private PowerLimit() {
    }

    /** The cap now, or 0 for none. */
    public static int now() {
        return MacPower.running()
            ? cap(MacPower.state(), VideoOptions.BATTERY_LIMIT.get(), VideoOptions.LOW_POWER_LIMIT.get(), VideoOptions.HOT_LIMIT.get())
            : 0;
    }

    /** The lowest of the limits that apply to {@code state}, or 0 when none does. */
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

    /** {@code limit} applied to the game's own: never raises it. */
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
