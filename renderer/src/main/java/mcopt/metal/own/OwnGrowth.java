package mcopt.metal.own;

final class OwnGrowth {
	private static final boolean ON = Boolean.getBoolean("mcopt.own.growthStats");

	private OwnGrowth() {
	}

	static long begin() {
		return ON ? System.nanoTime() : 0;
	}

	static void end(String kind, long start, long oldBytes, long newBytes, long physicalBytes) {
		if (!ON) return;
		long end = System.nanoTime();
		System.out.println(String.format(java.util.Locale.ROOT,
			"mcopt-own growth: kind=%s startNs=%d endNs=%d epochMs=%d oldBytes=%d newBytes=%d physicalBytes=%d durationUs=%.3f thread=%s",
			kind, start, end, System.currentTimeMillis(), oldBytes, newBytes, physicalBytes, (end - start) / 1e3, Thread.currentThread().getName()));
	}
}
