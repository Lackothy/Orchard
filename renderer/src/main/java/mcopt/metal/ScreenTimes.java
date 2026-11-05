// Russet's own file (see NOTICE): not part of mcopt.
package mcopt.metal;

/**
 * Measuring only: when each frame presented between start and stop reached the screen, by its drawable's presentedTime,
 * with the game time it was drawn for (tag, set by the frame before it presents). Read by the tour's frame timing.
 */
public final class ScreenTimes {
	/** mcmetal.m's SHOWN_MAX: how many presents the native ring keeps. */
	private static final int SHOWN_MAX = 8192;
	private static int from;

	private ScreenTimes() {
	}

	public static void tag(double gameTime) {
		Native.shownTag(gameTime);
	}

	public static void start() {
		from = Native.shownCount();
		Native.shownOn(1);
	}

	/** Stops keeping new presents and says how many were kept (the newest SHOWN_MAX); their handlers may still be filling in. */
	public static int stop() {
		Native.shownOn(0);
		int kept = Native.shownCount() - from;
		if (kept > SHOWN_MAX) {
			from += kept - SHOWN_MAX;
			kept = SHOWN_MAX;
		}
		return kept;
	}

	/** Present i: when it reached the screen (0 never, -1 not yet), when it was sent, its game time, and when it was tagged. */
	public static double[] get(int i) {
		return new double[] {Native.shown(0, from + i), Native.shown(1, from + i), Native.shown(2, from + i), Native.shown(3, from + i)};
	}
}
