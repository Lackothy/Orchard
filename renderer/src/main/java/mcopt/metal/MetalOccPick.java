package mcopt.metal;

import java.util.Arrays;

final class MetalOccPick {
	private static final int BLOCK = 24, SETTLE = 6, BLOCKS = 16, SAMPLES = BLOCKS / 2 * (BLOCK - SETTLE);
	private static final long FIRST_RETRY = 2_000_000_000L, RETRY = 30_000_000_000L;
	private final long[][] samples = new long[2][SAMPLES];
	private int frame = -1;
	private long lastSubmit, decidedAt, pixels, retry;
	private double bias;
	private boolean split = true;

	boolean next(long pixels) {
		long now = System.nanoTime(), time = now - this.lastSubmit;
		this.lastSubmit = now;
		if (pixels == 0) return this.split;
		boolean resized = pixels != this.pixels;
		if (resized || this.frame < 0 && now - this.decidedAt >= this.retry) {
			this.retry = resized ? FIRST_RETRY : Math.min(2 * this.retry, RETRY);
			if (resized) this.bias = 0;
			this.pixels = pixels;
			this.frame = 0;
			return this.split = true;
		}
		if (this.frame < 0) return this.split;
		int block = this.frame / BLOCK, at = this.frame % BLOCK - SETTLE;
		if (at >= 0) this.samples[block & 1][block / 2 * (BLOCK - SETTLE) + at] = time;
		if (++this.frame < BLOCKS * BLOCK) return this.split = (this.frame / BLOCK & 1) == 0;
		long split = median(this.samples[0]), plain = median(this.samples[1]);
		this.bias += Math.log((double) split / plain);
		this.split = this.bias <= 0;
		this.frame = -1;
		this.decidedAt = now;
		System.out.println(String.format("mcopt-metal occ: %d px, split %.3f ms, plain %.3f ms, sum %+.3f: %s", pixels, split / 1e6, plain / 1e6, this.bias,
			this.split ? "split" : "plain"));
		return this.split;
	}

	private static long median(long[] times) {
		Arrays.sort(times);
		return times[times.length / 2];
	}
}
