// Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0 (LICENSE).
package mcopt.metal.shade.shadow;

import mcopt.metal.shade.Live;

public final class ShadowConfig {
	private ShadowConfig() {
	}

	public static final boolean SHADE = mcopt.metal.shade.Shade.ENABLED;
	public static final boolean SHADOWS = mcopt.metal.shade.Shade.SHADOWS;
	public static final int RES = clamp(Integer.getInteger("mcopt.shade.shadowRes", mcopt.metal.shade.Shade.TIER >= 3 ? 3072 : 2048), 512, 8192);
	public static final int DEPTH_BITS = Integer.getInteger("mcopt.shade.shadowDepth", 16) == 32 ? 32 : 16;
	public static final float DIST = clamp(floatProp("mcopt.shade.shadowDist", 128f), 16f, 1024f);
	public static final float NEAR = clamp(floatProp("mcopt.shade.shadowNear", DIST / 4f), 4f, DIST);
	public static final int FAR_EVERY = clamp(Integer.getInteger("mcopt.shade.shadowFarEvery", 4), 1, 64);
	public static final double SUN_STEP_DEGREES = clamp(floatProp("mcopt.shade.shadowSunStep", 0.1f), 0f, 5f);
	public static final boolean CULL_LIGHT = !"camera".equals(System.getProperty("mcopt.shade.shadowCull", "light"));
	public static final boolean ALL_FACES = "all".equals(System.getProperty("mcopt.shade.shadowFaces", "sun"));
	public static final float BIAS_SLOPE = floatProp("mcopt.shade.shadowBias", 1.0f);
	public static final float BIAS_TEXELS = floatProp("mcopt.shade.shadowBiasTexels", 1.0f);
	public static final int DEBUG = Integer.getInteger("mcopt.shade.debug", 0);
	public static final boolean FAR_CUTOUT_OPAQUE = !"test".equals(System.getProperty("mcopt.shade.shadowFarCutout", "opaque"));
	public static final boolean STATS = Boolean.getBoolean("mcopt.shade.shadowStats");

	static float dist() {
		return Live.f("mcopt.shade.shadowDist", DIST, 16f, 1024f);
	}

	static float near() {
		float dist = dist();
		return clamp(Live.f("mcopt.shade.shadowNear", dist / 4f), 4f, dist);
	}

	static final String FAR_SPREAD_DEFAULT = "true", TINT_TIGHT_DEFAULT = "true", TINT_SUB_DEFAULT = "true";

	static boolean farSpread() {
		return !"false".equals(System.getProperty("mcopt.shade.shadowFarSpread", FAR_SPREAD_DEFAULT));
	}

	static boolean times() {
		return Boolean.getBoolean("mcopt.shade.shadowTimes");
	}

	static boolean tintTight() {
		return !"false".equals(System.getProperty("mcopt.shade.tintTight", TINT_TIGHT_DEFAULT));
	}

	static boolean tintSub() {
		return !"false".equals(System.getProperty("mcopt.shade.tintSub", TINT_SUB_DEFAULT));
	}

	static int tintDev() {
		return Integer.getInteger("mcopt.shade.tintDev", 0);
	}

	private static float floatProp(String key, float fallback) {
		String v = System.getProperty(key);
		if (v == null) return fallback;
		try {
			return Float.parseFloat(v.trim());
		} catch (NumberFormatException e) {
			System.out.println("[mcopt] shade: bad " + key + "=" + v + ", using " + fallback);
			return fallback;
		}
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	private static float clamp(float v, float lo, float hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	public static String source() {
		try (java.io.InputStream in = ShadowConfig.class.getResourceAsStream("/mcopt/shade/shadow.metal")) {
			if (in == null) throw new IllegalStateException("missing /mcopt/shade/shadow.metal");
			return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		} catch (java.io.IOException e) {
			throw new IllegalStateException(e);
		}
	}

	public static void warmUp(long ctx) {
		java.nio.ByteBuffer source = org.lwjgl.system.MemoryUtil.memUTF8(source());
		try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
			long err = stack.ncalloc(1, 2048, 1);
			long s = ShadowNative.create(ctx, org.lwjgl.system.MemoryUtil.memAddress(source), 1, 1, DEPTH_BITS, err, 2048);
			if (s == 0) throw new IllegalStateException(org.lwjgl.system.MemoryUtil.memUTF8(err));
			try {
				if (mcopt.metal.shade.weather.ShadeWeather.COLOR_SHADOWS && ShadowNative.tintInit(s, 1, err, 2048) != 0) {
					throw new IllegalStateException(org.lwjgl.system.MemoryUtil.memUTF8(err));
				}
			} finally {
				ShadowNative.free(s);
			}
		} finally {
			org.lwjgl.system.MemoryUtil.memFree(source);
		}
	}
}
