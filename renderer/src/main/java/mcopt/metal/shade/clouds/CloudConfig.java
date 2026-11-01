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
package mcopt.metal.shade.clouds;

import mcopt.metal.shade.Live;
import mcopt.metal.shade.Shade;

public final class CloudConfig {
	private static final int TIER = Shade.TIER;
	public static final boolean M33 = flag("mcopt.shade.clouds.m33", true);
	public static final boolean ENABLED = Shade.ENABLED && flag("mcopt.shade.clouds", true) && flag("mcopt.shade.hdr", true)
		&& !Boolean.parseBoolean(System.getProperty("mcopt.lod", "false"));
	public static final float SCALE = clamp(floatProperty("mcopt.shade.clouds.scale", new float[] {0.4f, 0.45f, 0.5f, 0.67f}[TIER]), 0.2f, 1f);
	public static final int CHECKER = clampInt(Integer.getInteger("mcopt.shade.clouds.checker", new int[] {3, 3, 2, 2}[TIER]), 1, 4);
	public static final int CHECKER_ALT = clampInt(Integer.getInteger("mcopt.shade.clouds.checkerAlt", 4), 1, 4);
	public static final int STEPS = clampInt(Integer.getInteger("mcopt.shade.clouds.steps", new int[] {20, 20, 40, 56}[TIER]), 6, 128);
	public static final int LIGHT_STEPS = clampInt(Integer.getInteger("mcopt.shade.clouds.lightSteps", new int[] {3, 3, 4, 5}[TIER]), 1, 8);
	public static final boolean SHADOWS = flag("mcopt.shade.clouds.shadows", true);
	public static final float SHADOW_STRENGTH = clamp(floatProperty("mcopt.shade.clouds.shadowStrength", 0.8f), 0f, 1f);
	public static final float SHADOW_EXTENT = clamp(floatProperty("mcopt.shade.clouds.shadowExtent", 1024f), 256f, 4096f);
	public static final boolean DOME = flag("mcopt.shade.clouds.dome", true);
	public static final int DOME_STEPS = clampInt(Integer.getInteger("mcopt.shade.clouds.domeSteps", new int[] {8, 10, 12, 16}[TIER]), 4, 64);
	public static final int DOME_W = 128, DOME_H = 64;
	public static final int DOME_EVERY = Integer.getInteger("mcopt.shade.clouds.domeEvery", TIER <= 1 ? 8 : 4) >= 8 ? 8 : 4;
	public static final boolean CIRRUS = flag("mcopt.shade.clouds.cirrus", true);
	public static final boolean BLOCKY = System.getProperty("mcopt.shade.clouds.style", "storybook".equalsIgnoreCase(System.getProperty("mcopt.shade.look", "").trim())
		? "blocky" : "volumetric").trim().equalsIgnoreCase("blocky");
	public static final float BLOCK_THICKNESS = clamp(floatProperty("mcopt.shade.clouds.blockThickness", 8f), 2f, 64f);
	public static final float VANILLA_THICKNESS = clamp(floatProperty("mcopt.shade.clouds.blockThickness", 4f), 2f, 64f);
	public static final float BASE = floatProperty("mcopt.shade.clouds.base", Float.NaN);
	public static final float THICKNESS = clamp(floatProperty("mcopt.shade.clouds.thickness", 150f), 20f, 600f);
	public static final float CIRRUS_ABOVE = clamp(floatProperty("mcopt.shade.clouds.cirrusAbove", 900f), 100f, 4000f);
	public static final float COVERAGE = floatProperty("mcopt.shade.clouds.coverage", -1f);
	public static final float TYPE = floatProperty("mcopt.shade.clouds.type", -1f);
	public static final float WIND = Math.max(0f, floatProperty("mcopt.shade.clouds.wind", 1f));
	public static final float NOISE_DRIFT = clamp(floatProperty("mcopt.shade.clouds.noiseDrift", 0.82f), 0f, 2f);
	public static final long SEED = Long.getLong("mcopt.shade.clouds.seed", 0L);
	public static final float DENSITY = clamp(floatProperty("mcopt.shade.clouds.density", 1f), 0.1f, 10f);
	public static final float DETAIL = clamp(floatProperty("mcopt.shade.clouds.detail", 1f), 0f, 3f);
	public static final float ALTITUDE_SCALE = clamp(floatProperty("mcopt.shade.clouds.altitudeScale", 4f), 0f, 50f);
	public static final int MAX_AGE = maxAge(CHECKER);

	public static int maxAge(int n) {
		return clampInt(Integer.getInteger("mcopt.shade.clouds.maxAge", n >= 4 ? 8 : n >= 3 ? 10 : 12), 1, 63);
	}

	public static float density() {
		return Live.f("mcopt.shade.clouds.density", DENSITY, 0.1f, 10f);
	}

	public static float detail() {
		return Live.f("mcopt.shade.clouds.detail", DETAIL, 0f, 3f);
	}

	public static float base() {
		return Live.f("mcopt.shade.clouds.base", BASE);
	}

	public static float thickness() {
		return Live.f("mcopt.shade.clouds.thickness", THICKNESS, 20f, 600f);
	}

	public static float blockThickness(boolean vanillaCells) {
		return Live.f("mcopt.shade.clouds.blockThickness", vanillaCells ? VANILLA_THICKNESS : BLOCK_THICKNESS, 2f, 64f);
	}

	public static float wind() {
		return Live.f("mcopt.shade.clouds.wind", WIND, 0f, 4f);
	}

	public static boolean cirrus() {
		return Live.b("mcopt.shade.clouds.cirrus", CIRRUS);
	}

	public static float cirrusAbove() {
		return Live.f("mcopt.shade.clouds.cirrusAbove", CIRRUS_ABOVE, 100f, 4000f);
	}

	public static boolean shadows() {
		return Live.b("mcopt.shade.clouds.shadows", SHADOWS);
	}

	public static float shadowStrength() {
		return Live.f("mcopt.shade.clouds.shadowStrength", SHADOW_STRENGTH, 0f, 1f);
	}

	public static boolean dome() {
		return Live.b("mcopt.shade.clouds.dome", DOME);
	}

	public static int steps() {
		return clampInt(Live.i("mcopt.shade.clouds.steps", STEPS), 6, 128);
	}

	public static int lightSteps() {
		return clampInt(Live.i("mcopt.shade.clouds.lightSteps", LIGHT_STEPS), 1, 8);
	}

	public static int domeSteps() {
		return clampInt(Live.i("mcopt.shade.clouds.domeSteps", DOME_STEPS), 4, 64);
	}

	public static float scale() {
		return Live.f("mcopt.shade.clouds.scale", SCALE, 0.2f, 1f);
	}

	public static int checker() {
		return clampInt(Live.i("mcopt.shade.clouds.checker", CHECKER), 1, 4);
	}

	public static float altitudeScale() {
		return Live.f("mcopt.shade.clouds.altitudeScale", ALTITUDE_SCALE, 0f, 50f);
	}

	public static float userCoverage() {
		return Live.f("mcopt.shade.user.cloudCoverage", 0f, -0.5f, 0.5f);
	}

	public static float userType() {
		return Live.f("mcopt.shade.user.cloudType", -1f, -1f, 1f);
	}

	public static float userBrightness() {
		return Live.f("mcopt.shade.user.cloudBrightness", 1f, 0.25f, 2f);
	}

	public static float userOpacity() {
		return Live.f("mcopt.shade.user.cloudOpacity", 1f, 0f, 1f);
	}

	public static float userCirrus() {
		return Live.f("mcopt.shade.user.cirrusAmount", 1f, 0f, 2f);
	}
	public static final int DEBUG = Integer.getInteger("mcopt.shade.clouds.debug", 0);
	public static final boolean LEAN = flag("mcopt.shade.clouds.lean", true);
	public static final int DEV_NO_SCREEN = 1, DEV_NO_MAPS = 2, DEV_NO_SKY = 4, DEV_NO_FOG = 8, DEV_NO_SHADOW_USE = 16, DEV_NO_VL = 32,
		DEV_OLD_FOG = 256, DEV_OLD_VL = 512, DEV_OLD_RESOLVE = 1024, DEV_OLD_MARCH = 2048, DEV_SCALE_ALT = 4096, DEV_OLD_MIP = 8192,
		DEV_OLD_DOME = 16384, DEV_OLD_STABLE = 32768, DEV_OLD_LIGHT = 65536, DEV_OLD_BATCH = 131072, DEV_OLD_PAIR = 262144,
		DEV_CHECKER_ALT = 524288, DEV_OLD_SHADOW = 1048576,
		DEV_EARLY_FOG = 2097152,
		DEV_NO_AP = 4194304,
		DEV_AP_SUN = 8388608;
	public static boolean aerialSun() {
		return "true".equalsIgnoreCase(System.getProperty("mcopt.shade.aerialSun", "false").trim());
	}
	public static boolean lateFog() {
		return flag("mcopt.shade.clouds.lateFog", true);
	}
	public static final int DEV_OLD_M33 = DEV_OLD_STABLE | DEV_OLD_LIGHT | DEV_OLD_BATCH | DEV_OLD_PAIR;
	public static final int DEV_OLD_ALL = DEV_OLD_FOG | DEV_OLD_VL | DEV_OLD_RESOLVE | DEV_OLD_MARCH | DEV_OLD_MIP | DEV_OLD_DOME;
	public static final float SCALE_ALT = clamp(floatProperty("mcopt.shade.clouds.scaleAlt", 0.4f), 0.2f, 1f);

	public static int devBits() {
		return Integer.getInteger("mcopt.shade.clouds.dev", 0) | (LEAN ? 0 : DEV_OLD_ALL);
	}
	public static final int MODE_STABLE = 1;
	public static final int MODE_LIGHT2 = 2;
	public static final int MODE_BATCH = 4;
	public static final int MODE_PAIR = 8;
	public static final int MODE_EDGE = 16;
	public static final int MODE_DRIFT = 32;
	public static final int MODE_ZOOM = 64;

	public static int modeBits() {
		if (!M33) return 0;
		int dev = Integer.getInteger("mcopt.shade.clouds.dev", 0);
		return (flag("mcopt.shade.clouds.stable", true) && (dev & DEV_OLD_STABLE) == 0 ? MODE_STABLE : 0)
			| (flag("mcopt.shade.clouds.halfLight", false) && (dev & DEV_OLD_LIGHT) == 0 ? MODE_LIGHT2 : 0)
			| (flag("mcopt.shade.clouds.batch", true) && (dev & DEV_OLD_BATCH) == 0 ? MODE_BATCH : 0)
			| (flag("mcopt.shade.clouds.pair", true) && (dev & DEV_OLD_PAIR) == 0 ? MODE_PAIR : 0)
			| (flag("mcopt.shade.clouds.edgeGate", true) ? MODE_EDGE : 0)
			| (flag("mcopt.shade.clouds.driftClamp", true) ? MODE_DRIFT : 0)
			| (flag("mcopt.shade.clouds.zoomAge", true) ? MODE_ZOOM : 0);
	}
	public static boolean smooth() {
		return flag("mcopt.shade.clouds.smooth", SMOOTH_DEFAULT);
	}

	static final boolean SMOOTH_DEFAULT = false;
	public static final boolean GPU_TIMER = Boolean.getBoolean("mcopt.shade.clouds.gpuTimer");
	public static final boolean LEAN_PASSES = !"false".equals(System.getProperty("mcopt.shade.cloudLean"));
	public static final boolean LOG = GPU_TIMER || Boolean.getBoolean("mcopt.shade.clouds.log");

	private CloudConfig() {
	}

	public static String source() {
		return mcopt.metal.shade.ShadeMsl.prelude() + "\n" + mcopt.metal.shade.ShadeMsl.resource("clouds.metal");
	}

	static String describe() {
		return (BLOCKY ? "blocky at start, " : "") + "preset " + Shade.PRESET + ", scale " + SCALE + " (" + CHECKER + "x" + CHECKER + " checkerboard)" + ", " + STEPS + "/" + LIGHT_STEPS + " steps"
			+ (SHADOWS ? ", shadows " + SHADOW_STRENGTH : ", no shadows") + (DOME ? ", dome " + DOME_STEPS + " steps" : "") + (CIRRUS ? ", cirrus" : "")
			+ (LEAN ? "" : ", not lean (M3 passes)");
	}

	static boolean flag(String key, boolean def) {
		String v = System.getProperty(key);
		return v == null ? def : Boolean.parseBoolean(v.trim());
	}

	static float floatProperty(String key, float def) {
		String v = System.getProperty(key);
		if (v == null) return def;
		try {
			return Float.parseFloat(v.trim());
		} catch (NumberFormatException e) {
			return def;
		}
	}

	private static float clamp(float v, float lo, float hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	private static int clampInt(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	public static void warmUp(long ctx) {
		long lib = mcopt.metal.shade.ShadeCompile.library(ctx, source());
		try {
			int failed = CloudNative.warm(ctx, lib);
			if (failed != 0) throw new IllegalStateException(failed + " cloud pipelines failed");
			if (LEAN_PASSES) CloudNative.warmLean(ctx, lib, devBits(), modeBits(), ShadeClouds.leanStyle());
		} finally {
			mcopt.metal.MetalBridge.release(lib);
		}
	}
}
