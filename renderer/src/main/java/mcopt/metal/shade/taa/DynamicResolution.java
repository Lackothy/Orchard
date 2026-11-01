/*
 * Copyright 2026 mcopt-shade contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package mcopt.metal.shade.taa;

public final class DynamicResolution {
	private static final float STEP_DOWN = 0.04f, STEP_UP = 0.02f, QUANTUM = 1f / 64f, DEADBAND = 0.015f, UP_MARGIN = 0.92f;
	private static final int SETTLE_FRAMES = 8, SETTLE_UP_FRAMES = 45, AVERAGE_FRAMES = 12, IN_FLIGHT = Math.max(1, Integer.getInteger("mcopt.metal.inFlight", 2));

	private static final double HOLD_SET = 0.90, HOLD_DOWN = 0.965, HOLD_UP = 0.86;
	private static final float HOLD_STEP_DOWN = 0.08f, HOLD_STEP_UP = 0.03f;
	private static final int HOLD_SETTLE_UP = 30;
	public static final float HOLD_FLOOR = TaaConfig.clamp(floatProp("mcopt.shade.drsFloor", 0.55f), 0.4f, 1f);

	static float holdFloor() {
		return mcopt.metal.shade.Live.f("mcopt.shade.drsFloor", HOLD_FLOOR, 0.4f, 1f);
	}
	private static final boolean USER_MIN = System.getProperty("mcopt.shade.scaleMin") != null;
	private static final double HOLD_FIXED = TaaConfig.clamp(floatProp("mcopt.shade.drsFixed", 0.5f), 0f, 0.9f);

	private final float min, max;
	private int fps;
	private double targetSeconds;
	private float scale;
	private double average, lastEnd;
	private int samples, sinceChange, skip, pinned;
	private long statusStart;
	private int statusFrames, statusChanges;
	private float statusMin = Float.MAX_VALUE, statusMax;

	public DynamicResolution(float start, float min, float max, int fps) {
		this.min = min;
		this.max = max;
		this.scale = TaaConfig.clamp(start, min, max);
		this.fps = fps;
		this.targetSeconds = 0.86 / fps;
	}

	public static boolean hold() {
		String v = System.getProperty("mcopt.shade.drsHold");
		return v == null || Boolean.parseBoolean(v.trim());
	}

	private static float floatProp(String key, float fallback) {
		String v = System.getProperty(key);
		if (v == null || v.isBlank()) return fallback;
		try {
			return Float.parseFloat(v.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	public float scale() {
		return this.scale;
	}

	public void retarget(int fps) {
		if (fps == this.fps || fps <= 0) return;
		this.fps = fps;
		this.targetSeconds = 0.86 / fps;
		this.pinned = 0;
	}

	public double averageMs() {
		return this.average * 1e3;
	}

	public void frame(double gpuStart, double gpuEnd) {
		if (!(gpuEnd > 0) || !(gpuStart > 0) || gpuEnd < gpuStart) return;
		double busy = gpuEnd - Math.max(gpuStart, this.lastEnd);
		this.lastEnd = Math.max(this.lastEnd, gpuEnd);
		if (busy <= 0 || busy > 0.5) return;
		if (this.skip > 0) {
			this.skip--;
			return;
		}
		this.average = this.samples == 0 ? busy : this.average + (busy - this.average) / AVERAGE_FRAMES;
		this.samples++;
	}

	public float update() {
		boolean hold = hold();
		if (TaaConfig.DRS_LOG) this.status(hold);
		this.sinceChange++;
		if (this.samples < AVERAGE_FRAMES || this.sinceChange < SETTLE_FRAMES) return this.scale;
		if (hold) return this.updateHold();
		if (this.scale <= this.min && this.average > this.targetSeconds) {
			if (++this.pinned % 600 == 0 && TaaConfig.DRS_LOG) {
				System.out.println(String.format(java.util.Locale.ROOT, "[mcopt] shade: render scale pinned at its floor %.3f, GPU %.2f ms/frame over the %.2f ms target",
					this.scale, this.average * 1e3, this.targetSeconds * 1e3));
			}
		} else {
			this.pinned = 0;
		}
		float next = (float) (this.scale * Math.sqrt(this.targetSeconds / this.average));
		if (next > this.scale && (this.sinceChange < SETTLE_UP_FRAMES || this.average > UP_MARGIN * this.targetSeconds)) return this.scale;
		next = TaaConfig.clamp(next, this.scale - STEP_DOWN, this.scale + STEP_UP);
		next = TaaConfig.clamp(Math.round(next / QUANTUM) * QUANTUM, this.min, this.max);
		boolean toBound = (next == this.min || next == this.max) && next != this.scale;
		if (Math.abs(next - this.scale) <= DEADBAND && !toBound) return this.scale;
		return this.change(next);
	}

	private float change(float next) {
		this.scale = next;
		this.statusChanges++;
		this.sinceChange = 0;
		this.samples = 0;
		this.skip = IN_FLIGHT + 1;
		return this.scale;
	}

	private float holdMin() {
		return TaaConfig.PRESET.ordinal() <= TaaConfig.Preset.BALANCED.ordinal() && !USER_MIN ? Math.min(this.min, holdFloor()) : this.min;
	}

	private float modelScale(double t, double goal) {
		double r = (goal / t - HOLD_FIXED) / (1.0 - HOLD_FIXED);
		return (float) (this.scale * Math.sqrt(Math.max(r, 0.0)));
	}

	private float updateHold() {
		double budget = 1.0 / this.fps, t = this.average;
		float lo = this.holdMin(), next;
		if (t > HOLD_DOWN * budget) {
			if (this.scale <= lo) {
				if (++this.pinned % 600 == 0 && TaaConfig.DRS_LOG) {
					System.out.println(String.format(java.util.Locale.ROOT, "[mcopt] shade: render scale pinned at its floor %.3f, GPU %.2f ms/frame over the %.2f ms limit (hold)",
						this.scale, t * 1e3, HOLD_DOWN * budget * 1e3));
				}
				return this.scale;
			}
			next = TaaConfig.clamp(this.modelScale(t, HOLD_SET * budget), this.scale - HOLD_STEP_DOWN, this.scale);
			next = (float) Math.floor(next / QUANTUM) * QUANTUM;
		} else {
			this.pinned = 0;
			if (this.sinceChange < HOLD_SETTLE_UP || this.scale >= this.max) return this.scale;
			next = TaaConfig.clamp(this.modelScale(t, HOLD_UP * budget), this.scale, this.scale + HOLD_STEP_UP);
			next = (float) Math.floor(next / QUANTUM) * QUANTUM;
		}
		next = TaaConfig.clamp(next, lo, this.max);
		if (Math.abs(next - this.scale) < QUANTUM * 0.5f) return this.scale;
		return this.change(next);
	}

	public void restart(float start) {
		this.scale = TaaConfig.clamp(start, hold() ? this.holdMin() : this.min, this.max);
		this.samples = 0;
		this.sinceChange = 0;
		this.skip = IN_FLIGHT + 1;
	}

	private void status(boolean hold) {
		long now = System.nanoTime();
		if (this.statusStart == 0) this.statusStart = now;
		this.statusFrames++;
		this.statusMin = Math.min(this.statusMin, this.scale);
		this.statusMax = Math.max(this.statusMax, this.scale);
		double secs = (now - this.statusStart) * 1e-9;
		if (secs < 1.0) return;
		double budget = 1.0 / this.fps;
		float lo = hold ? this.holdMin() : this.min;
		double limit = hold ? HOLD_DOWN * budget : this.targetSeconds;
		System.out.println(String.format(java.util.Locale.ROOT, "[mcopt] shade: drs status %s scale %.3f (%.3f-%.3f) gpu %.2f ms target %.2f ms, %.1f fps, %d changes%s",
			hold ? "hold" : "m32", this.scale, this.statusMin, this.statusMax, this.average * 1e3, (hold ? HOLD_SET * budget : this.targetSeconds) * 1e3,
			this.statusFrames / secs, this.statusChanges, this.scale <= lo && this.average > limit ? ", at its floor" : ""));
		this.statusStart = now;
		this.statusFrames = 0;
		this.statusChanges = 0;
		this.statusMin = Float.MAX_VALUE;
		this.statusMax = 0f;
	}

	public void reset() {
		this.samples = 0;
		this.average = 0;
		this.sinceChange = 0;
	}
}
