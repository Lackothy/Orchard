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
package mcopt.metal.shade.vl;

import mcopt.metal.shade.Live;
import mcopt.metal.shade.Shade;

public final class VlConfig {
	public static final boolean ENABLED = Shade.ENABLED && Boolean.parseBoolean(System.getProperty("mcopt.shade.vl", "true"));
	public static final String PRESET = Shade.PRESET;
	private static final int TIER = Shade.TIER;
	public static final float SCALE = clamp(floatProperty("mcopt.shade.vl.scale", new float[] {0.25f, 0.33f, 0.5f, 0.5f}[TIER]), 0.125f, 1f);
	public static final int STEPS = Math.max(4, Math.min(64, Integer.getInteger("mcopt.shade.vl.steps", new int[] {10, 12, 16, 24}[TIER])));
	public static final int NEAR_STEPS = Math.max(0, Math.min(32, Integer.getInteger("mcopt.shade.vl.nearSteps", new int[] {6, 8, 10, 12}[TIER])));
	public static final int SS_TAPS = Math.max(0, Math.min(32, Integer.getInteger("mcopt.shade.vl.ssTaps", new int[] {6, 8, 10, 12}[TIER])));
	public static final float DIST = clamp(floatProperty("mcopt.shade.vl.dist", TIER >= 2 ? 224f : 160f), 16f, 512f);
	public static final float STRENGTH = Math.max(0f, floatProperty("mcopt.shade.vl.strength", 1f));
	public static final float MIST = Math.max(0f, floatProperty("mcopt.shade.vl.mist", 1f));
	public static final boolean EXTINCTION = Boolean.parseBoolean(System.getProperty("mcopt.shade.vl.extinction", "true"));
	public static final float MOON = Math.max(0f, floatProperty("mcopt.shade.vl.moon", 1f));
	public static final boolean UNDERWATER = Boolean.parseBoolean(System.getProperty("mcopt.shade.vl.underwater", "true"));
	public static final boolean WATER_M3 = Boolean.parseBoolean(System.getProperty("mcopt.shade.vl.waterM3", "true"));
	public static final boolean TEMPORAL = Boolean.parseBoolean(System.getProperty("mcopt.shade.vl.temporal", "true"));
	public static final float CANOPY = floatProperty("mcopt.shade.vl.canopy", -1f);
	public static final boolean LOSSY = Boolean.getBoolean("mcopt.shade.vl.lossy");
	public static final int DEBUG = Integer.getInteger("mcopt.shade.vl.debug", 0);
	public static final boolean FOLD = DEBUG == 0 && Boolean.parseBoolean(System.getProperty("mcopt.shade.vl.fold", "true"));
	public static final float STILL = clamp(floatProperty("mcopt.shade.vl.still", 0.6f), 0f, 4f);
	public static final boolean BOX3 = Boolean.parseBoolean(System.getProperty("mcopt.shade.vl.box3", "true"));
	public static final boolean HIST_DEPTH = Boolean.parseBoolean(System.getProperty("mcopt.shade.vl.histDepth", "true"));
	public static final boolean CREP_SMOOTH = Boolean.getBoolean("mcopt.shade.vl.crepSmooth");
	public static final boolean NEAR_DITHER = Boolean.parseBoolean(System.getProperty("mcopt.shade.vl.nearDither", "true"));
	public static final boolean WATER_DITHER = Boolean.parseBoolean(System.getProperty("mcopt.shade.vl.waterDither", "true"));
	public static final boolean GPU_TIMER = Boolean.getBoolean("mcopt.shade.vl.gpuTimer");
	public static final float SUNSET_RAYS_DEFAULT = 2f;
	public static final boolean SUNSET_FAR_DEFAULT = false;
	public static final float SUNSET_FALL_DEFAULT = 0f;
	public static final boolean LOG = GPU_TIMER || Boolean.getBoolean("mcopt.shade.vl.log");

	private VlConfig() {
	}

	public static float strength() {
		return Math.max(0f, Live.f("mcopt.shade.vl.strength", STRENGTH));
	}

	public static float moon() {
		return Math.max(0f, Live.f("mcopt.shade.vl.moon", MOON));
	}

	public static float mist() {
		return Math.max(0f, Live.f("mcopt.shade.vl.mist", MIST));
	}

	public static boolean extinction() {
		return Live.b("mcopt.shade.vl.extinction", EXTINCTION);
	}

	public static boolean underwater() {
		return Live.b("mcopt.shade.vl.underwater", UNDERWATER);
	}

	public static float dist() {
		return Live.f("mcopt.shade.vl.dist", DIST, 16f, 512f);
	}

	public static float userUnderwater() {
		return Live.f("mcopt.shade.user.underwaterRays", 1f, 0f, 2f);
	}

	public static String source() {
		return mcopt.metal.shade.ShadeMsl.prelude() + "\n" + mcopt.metal.shade.ShadeMsl.resource("vl.metal");
	}

	public static float sunsetRays() {
		String v = System.getProperty("mcopt.shade.vl.sunsetRays");
		if (v == null) return SUNSET_RAYS_DEFAULT;
		v = v.trim();
		if (v.equalsIgnoreCase("false") || v.equalsIgnoreCase("off")) return 1f;
		if (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("on")) return SUNSET_RAYS_DEFAULT;
		return clamp(floatProperty("mcopt.shade.vl.sunsetRays", SUNSET_RAYS_DEFAULT), 0f, 4f);
	}

	public static boolean sunsetFar() {
		String v = System.getProperty("mcopt.shade.vl.sunsetFar");
		return v == null ? SUNSET_FAR_DEFAULT : Boolean.parseBoolean(v.trim());
	}

	public static float sunsetFall() {
		String v = System.getProperty("mcopt.shade.vl.sunsetFall");
		return v == null ? SUNSET_FALL_DEFAULT : clamp(floatProperty("mcopt.shade.vl.sunsetFall", SUNSET_FALL_DEFAULT), 0f, 16f);
	}

	public static float skyRays() {
		String v = System.getProperty("mcopt.shade.vl.skyRays");
		return v == null ? 1f : clamp(floatProperty("mcopt.shade.vl.skyRays", 1f), 0f, 4f);
	}

	public static boolean ft21() {
		return "ft21".equals(System.getProperty("mcopt.shade.vl.look"));
	}

	public static float look(String key, float now, float ft21) {
		return live(key, "ft21".equals(System.getProperty("mcopt.shade.vl.look")) ? ft21 : now);
	}

	public static float live(String key, float def) {
		String v = System.getProperty(key);
		if (v == null) return def;
		try {
			return Float.parseFloat(v.trim());
		} catch (NumberFormatException e) {
			return def;
		}
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

	static String describe() {
		return "preset " + PRESET + ", scale " + SCALE + ", " + STEPS + " steps + " + NEAR_STEPS + " near + " + SS_TAPS + " screen taps, " + (int) DIST + " blocks"
			+ (FOLD ? "" : ", no fold") + (TEMPORAL ? (STILL > 0f ? ", still " + STILL : "") + (BOX3 ? "" : ", plus box") + (HIST_DEPTH ? "" : ", bilinear history") + (CREP_SMOOTH ? ", per-texel taps" : "") + (NEAR_DITHER ? "" : ", frame-jittered mist steps") + (WATER_DITHER ? "" : ", frame-jittered water steps") : ", no temporal") + (LOSSY ? ", lossy" : "") + (DEBUG != 0 ? ", debug " + DEBUG : "");
	}

	public static void warmUp(long ctx, boolean hdr) {
		java.nio.ByteBuffer source = org.lwjgl.system.MemoryUtil.memUTF8(source());
		try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
			long err = stack.ncalloc(1, 2048, 1);
			long s = VlNative.create(ctx, org.lwjgl.system.MemoryUtil.memAddress(source), (LOSSY ? 1 : 0) | (hdr ? 2 : 0), err, 2048);
			if (s == 0) throw new IllegalStateException(org.lwjgl.system.MemoryUtil.memUTF8(err));
			VlNative.free(s);
		} finally {
			org.lwjgl.system.MemoryUtil.memFree(source);
		}
	}
}
