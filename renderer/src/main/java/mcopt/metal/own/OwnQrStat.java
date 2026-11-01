package mcopt.metal.own;

final class OwnQrStat {
	static final boolean ON = Boolean.getBoolean("mcopt.own.mesh.qrstat");
	static final ThreadLocal<int[]> GREY = ThreadLocal.withInitial(() -> new int[1]);
	private static final long[][] T = new long[2][9 + 17];
	private static final long START = System.nanoTime();
	private static long logAt;

	private OwnQrStat() {
	}

	static void quad(int layer, float[] p, int[] abgr, float[] uv, int[] light) {
		boolean box = true, axisRect = false;
		int constAxes = 0;
		for (int a = 0; a < 3; a++) {
			float v0 = p[a], v1 = v0;
			int distinct = 1;
			for (int v = 1; v < 4; v++) {
				float x = p[v * 3 + a];
				if (x == v0 || x == v1) continue;
				if (distinct == 1) {
					v1 = x;
					distinct = 2;
				} else {
					distinct = 3;
				}
			}
			if (distinct > 2) box = false;
			if (distinct == 1) constAxes++;
		}
		axisRect = box && constAxes == 1;
		boolean uvRect = distinctUv(uv, 0) <= 2 && distinctUv(uv, 1) <= 2;
		boolean colUni = abgr[0] == abgr[1] && abgr[0] == abgr[2] && abgr[0] == abgr[3];
		int[] g = GREY.get();
		boolean greyTint = !colUni && g[0] == 1;
		g[0] = 0;
		boolean lightUni = light[0] == light[1] && light[0] == light[2] && light[0] == light[3];
		int bytes = 4 + (box ? 12 : 24) + (uvRect ? 8 : 16) + (colUni ? 4 : greyTint ? 8 : 16) + (lightUni ? 2 : 8);
		bytes = (bytes + 3) & ~3;
		synchronized (T) {
			long[] t = T[layer];
			t[0]++;
			if (box) t[1]++;
			if (axisRect) t[2]++;
			if (uvRect) t[3]++;
			if (colUni) t[4]++;
			if (greyTint) t[5]++;
			if (lightUni) t[6]++;
			if (colUni && lightUni) t[7]++;
			t[8] += bytes;
			t[9 + Math.min(16, bytes / 4)]++;
			long now = System.nanoTime();
			if (now > logAt) {
				logAt = now + 2_000_000_000L;
				StringBuilder sb = new StringBuilder(String.format("mcopt-own mesh qrstat: t %.1f s", (now - START) / 1e9));
				for (int l = 0; l < 2; l++) {
					long[] q = T[l];
					double n = Math.max(1, q[0]);
					sb.append(String.format("; %s %d quads: box %.1f%% (axis rect %.1f%%), uv rect %.1f%%, colour uniform %.1f%% grey-tint %.1f%%, light uniform %.1f%%, both uniform %.1f%%, mean %.2f B; hist",
						l == 0 ? "solid" : "cutout", q[0], q[1] * 100 / n, q[2] * 100 / n, q[3] * 100 / n, q[4] * 100 / n, q[5] * 100 / n, q[6] * 100 / n, q[7] * 100 / n, q[8] / n));
					for (int k = 0; k <= 16; k++) if (q[9 + k] > 0) sb.append(' ').append(k * 4).append(':').append(q[9 + k]);
				}
				System.out.println(sb);
			}
		}
	}

	private static int distinctUv(float[] uv, int c) {
		float a = uv[c], b = a;
		int n = 1;
		for (int v = 1; v < 4; v++) {
			float x = uv[v * 2 + c];
			if (x == a || x == b) continue;
			if (n == 1) {
				b = x;
				n = 2;
			} else {
				return 3;
			}
		}
		return n;
	}
}
