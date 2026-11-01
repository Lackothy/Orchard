package mcopt.metal;

public final class ScreenTimes {
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

	public static int stop() {
		Native.shownOn(0);
		int kept = Native.shownCount() - from;
		if (kept > SHOWN_MAX) {
			from += kept - SHOWN_MAX;
			kept = SHOWN_MAX;
		}
		return kept;
	}

	public static double[] get(int i) {
		return new double[] {Native.shown(0, from + i), Native.shown(1, from + i), Native.shown(2, from + i), Native.shown(3, from + i)};
	}
}
