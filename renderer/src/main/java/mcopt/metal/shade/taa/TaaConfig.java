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

import java.util.Locale;
import mcopt.metal.shade.Live;

public final class TaaConfig {
	public enum Preset {
		PERFORMANCE(0.67f, 0.60f, 0.75f, 120, false),
		BALANCED(0.80f, 0.60f, 0.90f, 120, true),
		QUALITY(1.00f, 0.85f, 1.00f, 80, true),
		ULTRA(1.00f, 0.90f, 1.00f, 60, true);

		public final float scale, min, max;
		public final int fps;
		public final boolean stable;

		Preset(float scale, float min, float max, int fps, boolean stable) {
			this.scale = scale;
			this.min = min;
			this.max = max;
			this.fps = fps;
			this.stable = stable;
		}
	}

	public static final float FLOOR = 0.6f;

	public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("mcopt.shade.taa", "true"));
	public static final Preset PRESET = Preset.values()[mcopt.metal.shade.Shade.TIER];
	public static final float SCALE_MAX = clamp(floatProp("mcopt.shade.scaleMax", PRESET.max), FLOOR, 1f);
	public static final float SCALE_MIN = clamp(floatProp("mcopt.shade.scaleMin", PRESET.min), FLOOR, SCALE_MAX);
	public static final float SCALE = clamp(floatProp("mcopt.shade.scale", PRESET.scale), SCALE_MIN, SCALE_MAX);
	public static final boolean DRS = Boolean.parseBoolean(System.getProperty("mcopt.shade.drs", "true")) && SCALE_MIN < SCALE_MAX;
	public static final int DRS_FPS = Math.max(20, Integer.getInteger("mcopt.shade.drsFps", PRESET.fps));
	public static final boolean STABLE = switch (System.getProperty("mcopt.shade.taaMode", "").toLowerCase(Locale.ROOT)) {
		case "stable" -> true;
		case "responsive" -> false;
		default -> PRESET.stable;
	};
	private static final float SHARPEN = floatProp("mcopt.shade.sharpen", -1f);
	public static final boolean MIP_BIAS = Boolean.parseBoolean(System.getProperty("mcopt.shade.mipBias", "true"));
	public static final boolean LOSSY = Boolean.getBoolean("mcopt.shade.taaLossy");
	public static final int DEBUG = Integer.getInteger("mcopt.shade.taaDebug", 0);
	public static final boolean DRS_LOG = Boolean.getBoolean("mcopt.shade.drsLog");

	private TaaConfig() {
	}

	private static float floatProp(String key, float fallback) {
		String v = System.getProperty(key);
		if (v == null || v.isBlank()) return fallback;
		try {
			return Float.parseFloat(v.trim());
		} catch (NumberFormatException e) {
			System.out.println("[mcopt] shade: bad " + key + "=" + v + ", using " + fallback);
			return fallback;
		}
	}

	static float clamp(float v, float lo, float hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	public static float sharpen(float scale) {
		float sharpen = Live.f("mcopt.shade.sharpen", SHARPEN);
		if (sharpen >= 0f) return Math.min(sharpen, 1f);
		return 0.25f + 0.15f * clamp((1f - scale) / 0.33f, 0f, 1f);
	}

	public static boolean stable() {
		return switch (Live.s("mcopt.shade.taaMode", "")) {
			case "stable" -> true;
			case "responsive" -> false;
			default -> PRESET.stable;
		};
	}

	public static boolean drs() {
		return SCALE_MIN < SCALE_MAX && Live.b("mcopt.shade.drs", true);
	}

	public static int drsFps() {
		return Math.max(20, Live.i("mcopt.shade.drsFps", DRS_FPS));
	}

	public static float alphaMin() {
		return stable() ? 0.08f : 0.12f;
	}

	public static float motionAlpha() {
		return stable() ? 0.18f : 0.25f;
	}

	public static float clipGamma(boolean still) {
		return (stable() ? 1.25f : 1.0f) + (still ? 0.5f : 0f);
	}

	public static String describe() {
		return String.format(Locale.ROOT, "preset %s, scale %.2f (%.2f-%.2f%s), %s, sharpen %.2f%s", PRESET.name().toLowerCase(Locale.ROOT), SCALE, SCALE_MIN, SCALE_MAX,
			DRS ? ", dynamic to hold " + DRS_FPS + " fps" : ", fixed", STABLE ? "stable" : "responsive", sharpen(SCALE), MIP_BIAS ? "" : ", no mip bias");
	}

	public static String source() {
		return (Boolean.getBoolean("mcopt.shade.taaCompressTaps") ? "" : "#define TAA_LINEAR_HISTORY\n") + mcopt.metal.shade.ShadeMsl.resource("taa.metal");
	}

	public static void warmUp(long ctx) {
		java.nio.ByteBuffer source = org.lwjgl.system.MemoryUtil.memUTF8(source());
		try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
			long err = stack.ncalloc(1, 4096, 1);
			long taa = TaaNative.create(ctx, org.lwjgl.system.MemoryUtil.memAddress(source), (mcopt.metal.shade.ShadeConfig.HDR ? 1 : 0) | (LOSSY ? 2 : 0) | (DEBUG != 0 ? 4 : 0),
				err, 4096);
			if (taa == 0) throw new IllegalStateException(org.lwjgl.system.MemoryUtil.memUTF8(err));
			TaaNative.free(taa);
		} finally {
			org.lwjgl.system.MemoryUtil.memFree(source);
		}
	}
}
