// Modified for Russet: a cap at or above the refresh rate doesn't fight a display that holds frames to its refreshes (interval).
package mcopt.metal;

import com.mojang.blaze3d.platform.Monitor;
import net.minecraft.client.Minecraft;

public final class FrameWait {
	public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("mcopt.preciseLimiter", "true"));

	private static volatile boolean displayHolds;
	private static float refreshRate;
	private static long refreshCheckedAt;

	private FrameWait() {
	}

	static void displayHolds(boolean holds) {
		displayHolds = holds;
	}

	public static long interval(int limit) {
		long interval = 1_000_000_000L / limit;
		if (!displayHolds) return interval;
		float refresh = refreshRate();
		return refresh > 0 && limit >= refresh * 0.95f ? interval * 9 / 10 : interval;
	}

	private static float refreshRate() {
		long now = System.nanoTime();
		if (refreshCheckedAt == 0 || now - refreshCheckedAt > 1_000_000_000L) {
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
