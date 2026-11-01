// Modified for Orchard: a cap at or above the refresh rate doesn't fight a display that holds frames to its refreshes (interval).
package mcopt.metal;

import com.mojang.blaze3d.platform.Monitor;
import net.minecraft.client.Minecraft;

/** The frame limiter's wait, on a timer macOS can't defer; see mc_sleep_precise and FramerateLimiterMixin. */
public final class FrameWait {
	/** -Dmcopt.preciseLimiter=false keeps vanilla's park-then-spin wait. */
	public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("mcopt.preciseLimiter", "true"));

	private static volatile boolean displayHolds;
	private static float refreshRate;
	private static long refreshCheckedAt;

	private FrameWait() {
	}

	/** Whether Metal's presents wait for the display (vsync on, or off without the pacer), so it holds frames to its refreshes. */
	static void displayHolds(boolean holds) {
		displayHolds = holds;
	}

	/**
	 * The cap's frame interval, in nanoseconds. Where the display holds frames to its refreshes and the cap is at or above its
	 * refresh rate, a timer of the cap's own drifts against the refreshes and now and then holds a frame past one, which then
	 * shows the frame before again (Battery's 60 on a 60 Hz screen). There it runs a tenth faster than the cap, so it never
	 * holds a frame the display would show and still caps a window the display stops holding (a Stage Manager thumbnail).
	 */
	public static long interval(int limit) {
		long interval = 1_000_000_000L / limit;
		if (!displayHolds) return interval;
		float refresh = refreshRate();
		return refresh > 0 && limit >= refresh * 0.95f ? interval * 9 / 10 : interval;
	}

	private static float refreshRate() {
		long now = System.nanoTime();
		if (refreshCheckedAt == 0 || now - refreshCheckedAt > 1_000_000_000L) { // the window can move to another display
			refreshCheckedAt = now;
			try {
				Monitor monitor = Minecraft.getInstance().getWindow().findBestMonitor();
				refreshRate = monitor == null ? 0 : monitor.currentMode().getRefreshRate();
			} catch (RuntimeException e) {
				refreshRate = 0;
			}
		}
		return refreshRate;
	}

	public static void sleep(long nanos) {
		Native.sleepPrecise(nanos);
	}
}
