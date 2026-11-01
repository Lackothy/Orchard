package mcopt.metal.own;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.lwjgl.system.MemoryUtil;

final class OwnPosTable {
	static final boolean ON = Boolean.getBoolean("mcopt.own.mesh.exactPos") && Boolean.getBoolean("mcopt.own.compact");
	static final int BASE = 61440, SIZE = 4096, UNITS = SIZE * 4 / 64;
	private static final ConcurrentHashMap<Integer, Integer> CODES = new ConcurrentHashMap<>();
	private static final float[] VALUES = new float[SIZE];
	private static volatile int count;
	private static final AtomicLong OVERFLOW = new AtomicLong();
	private static long logAt, syncLogAt;

	private OwnPosTable() {
	}

	static int encode(float p) {
		int g = Math.max(0, Math.min(65535, Math.round((p + 8f) * 2048f)));
		if (g < BASE && g / 2048f - 8f == p) return g;
		Integer c = CODES.get(Float.floatToRawIntBits(p));
		if (c != null) return c;
		synchronized (VALUES) {
			c = CODES.get(Float.floatToRawIntBits(p));
			if (c != null) return c;
			int i = count;
			if (i >= SIZE) {
				long n = OVERFLOW.incrementAndGet();
				long now = System.nanoTime();
				if (now > logAt) {
					logAt = now + 5_000_000_000L;
					System.out.println("mcopt-own mesh exactPos: table full (" + SIZE + "), " + n + " coordinates rounded to the grid");
				}
				return Math.min(g, BASE - 1);
			}
			VALUES[i] = p;
			count = i + 1;
			CODES.put(Float.floatToRawIntBits(p), BASE + i);
			return BASE + i;
		}
	}

	static float decode(int c) {
		return c >= BASE && ON ? VALUES[c - BASE] : c / 2048f - 8f;
	}

	static int sync(long a, int from) {
		int n = count;
		if (n != from) {
			long now = System.nanoTime();
			if (now > syncLogAt) {
				syncLogAt = now + 5_000_000_000L;
				System.out.println("mcopt-own mesh exactPos: table " + n + " of " + SIZE + " entries, " + OVERFLOW.get() + " rounded");
			}
		}
		for (int i = from; i < n; i++) MemoryUtil.memPutFloat(a + 4L * i, VALUES[i]);
		return n;
	}

	static int size() {
		return count;
	}
}
