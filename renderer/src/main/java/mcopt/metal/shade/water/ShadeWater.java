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
package mcopt.metal.shade.water;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import mcopt.metal.shade.Shade;
import mcopt.metal.shade.ShadeMsl;
import org.lwjgl.system.MemoryUtil;

public final class ShadeWater {
	public static final boolean MODULE = Boolean.parseBoolean(System.getProperty("mcopt.shade.water", "true"));
	public static final boolean ON = Shade.ENABLED && MODULE;
	public static final boolean SSR = Boolean.parseBoolean(System.getProperty("mcopt.shade.ssr", "true"));
	public static final boolean SSR_QUAD = Boolean.parseBoolean(System.getProperty("mcopt.shade.ssrQuad", Shade.TIER >= 3 ? "false" : "true"));
	public static final int SSR_STEPS = Math.clamp(Integer.getInteger("mcopt.shade.ssrSteps", new int[] {16, 24, 24, 32}[Shade.TIER]), 4, 64);
	public static final float SSR_DIST = Math.clamp(floatProperty("mcopt.shade.ssrDist", 512f), 8f, 1024f);
	public static final float WAVES = Math.clamp(floatProperty("mcopt.shade.waterWaves", 1f), 0f, 4f);
	public static final boolean CAUSTICS = Boolean.parseBoolean(System.getProperty("mcopt.shade.caustics", "true"));
	public static final boolean FOAM = Boolean.parseBoolean(System.getProperty("mcopt.shade.waterFoam", "true"));
	public static final float REFLECT = Math.clamp(floatProperty("mcopt.shade.waterReflect", 1f), 0f, 2f);
	public static final boolean GLOSSY = Boolean.parseBoolean(System.getProperty("mcopt.shade.glossy", "false"));
	public static final boolean BAKE = Boolean.parseBoolean(System.getProperty("mcopt.shade.waterBake", "true"));
	public static final int BAKE_SIZE = Integer.getInteger("mcopt.shade.waterBakeSize", Shade.TIER >= 2 ? 1024 : 512) >= 1024 ? 1024 : 512;
	public static final boolean SSR_PREV = Boolean.parseBoolean(System.getProperty("mcopt.shade.ssrPrev", "true"));
	public static final boolean DETAIL = Boolean.parseBoolean(System.getProperty("mcopt.shade.waterDetail", "true"));
	public static final int DETAIL_SIZE = Integer.getInteger("mcopt.shade.waterDetailSize", 256) >= 512 ? 512 : 256;
	private static final boolean DETAIL_EXTRAS = Boolean.parseBoolean(System.getProperty("mcopt.shade.waterDetailExtras",
		String.valueOf(!"m21".equals(System.getProperty("mcopt.shade.water.style", "photon")))));
	private static final String PATTERN_FAR = System.getProperty("mcopt.shade.waterPatternFar", "0.075");
	private static final int DETAIL_FAR = Math.clamp(Integer.getInteger("mcopt.shade.waterDetailFar",
		"m21".equals(System.getProperty("mcopt.shade.water.style", "photon")) ? 96 : 112), 32, 512);
	public static final boolean SSR_EDGE = Boolean.parseBoolean(System.getProperty("mcopt.shade.ssrEdge", "true"));
	public static final boolean EDGE_REFL = Boolean.parseBoolean(System.getProperty("mcopt.shade.water.edgeRefl", "true"));
	public static final float SSR_THICK = Math.clamp(floatProperty("mcopt.shade.water.ssrThick", 3f), 0.5f, 8f);
	public static final boolean SSR_BEHIND = Boolean.parseBoolean(System.getProperty("mcopt.shade.water.ssrBehind", "true"));
	public static final boolean SMOOTH = !"m21".equals(System.getProperty("mcopt.shade.water.style", "photon"));
	static final float CALM = Math.clamp(floatProperty("mcopt.shade.water.calm", 0.55f), 0.05f, 2f);
	static final float RIPPLE = Math.clamp(floatProperty("mcopt.shade.water.ripple", 2.0f), 0f, 3f);
	public static final boolean GLINT_GRAZE = Boolean.parseBoolean(System.getProperty("mcopt.shade.waterGlintGraze", "true"));
	public static float shadowSoft() {
		if (!ON) return 0f;
		String v = System.getProperty("mcopt.shade.waterShadowSoft");
		if (v == null) return SHADOW_SOFT_DEFAULT;
		try {
			return Math.clamp(Float.parseFloat(v.trim()), 0f, 8f);
		} catch (NumberFormatException e) {
			return SHADOW_SOFT_DEFAULT;
		}
	}

	public static float shadowContact() {
		return Math.max(mcopt.metal.shade.vl.VlConfig.live("mcopt.shade.waterShadowContact", 0f), 0f);
	}

	static final float SHADOW_SOFT_DEFAULT = 2f;
	public static final boolean CLOCK = Boolean.parseBoolean(System.getProperty("mcopt.shade.waterClock", "true"));
	public static final String TRANSLUCENT = "minecraft:pipeline/translucent_terrain", TRANSLUCENT_MULTIDRAW = "minecraft:pipeline/translucent_terrain_multidraw";

	public static boolean translucentTerrain(String pipelineName) {
		return TRANSLUCENT.equals(pipelineName) || TRANSLUCENT_MULTIDRAW.equals(pipelineName);
	}

	private static long state;
	private static boolean failed;
	private static final ByteBuffer PARAMS = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
	private static int captures;

	private ShadeWater() {
	}

	private static float floatProperty(String key, float def) {
		try {
			return Float.parseFloat(System.getProperty(key, Float.toString(def)));
		} catch (NumberFormatException e) {
			return def;
		}
	}

	public static String prelude() {
		StringBuilder b = new StringBuilder("#define SHADE_WATER 1\n");
		if (SSR) b.append("#define SHADE_SSR 1\n");
		if (SSR_QUAD) b.append("#define SHADE_SSR_QUAD 1\n");
		b.append("#define SHADE_SSR_STEPS ").append(SSR_STEPS).append('\n');
		if (CAUSTICS) b.append("#define SHADE_CAUSTICS 1\n");
		if (FOAM) b.append("#define SHADE_FOAM 1\n");
		if (GLOSSY) b.append("#define SHADE_GLOSSY 1\n");
		if (SSR_PREV) b.append("#define SHADE_SSR_PREV 1\n");
		if (DETAIL) {
			b.append("#define SHADE_WATER_DETAIL 1\n#define SHADE_WATER_DETAIL_SIZE ").append(DETAIL_SIZE).append("\n#define SHADE_WATER_DETAIL_FAR ")
				.append(DETAIL_FAR).append(".0\n#define SHADE_WATER_PATTERN_FAR ").append(Float.parseFloat(PATTERN_FAR)).append('\n');
			if (DETAIL_EXTRAS) b.append("#define SHADE_WATER_DETAIL_EXTRAS 1\n");
		}
		if (SSR_EDGE) b.append("#define SHADE_SSR_EDGE 1\n");
		b.append("#define SHADE_SSR_THICK_K ").append(SSR_THICK).append("\n#define SHADE_SSR_BEHIND ").append(SSR_BEHIND ? 1 : 0).append('\n');
		if (Boolean.parseBoolean(System.getProperty("mcopt.shade.water.rawz", "true"))) b.append("#define SHADE_SSR_RAWZ 1\n");
		if (Boolean.parseBoolean(System.getProperty("mcopt.shade.water.ssrLean", "true"))) b.append("#define SHADE_SSR_LEAN2 1\n");
		if (Boolean.getBoolean("mcopt.shade.wdev")) b.append("#define SHADE_WDEV_ON 1\n");
		if (EDGE_REFL) b.append("#define SHADE_WATER_EDGE_REFL 1\n");
		if (SMOOTH && SSR_QUAD && Boolean.parseBoolean(System.getProperty("mcopt.shade.water.ssrLane", "true"))) b.append("#define SHADE_SSR_LANE 1\n");
		if (SMOOTH) b.append("#define SHADE_WATER_SMOOTH 1\n#define SHADE_WATER_CALM ").append(CALM).append("\n#define SHADE_WATER_RIPPLE ").append(RIPPLE).append('\n');
		if (GLINT_GRAZE) b.append("#define SHADE_WATER_GLINT_GRAZE 1\n");
		if (BAKE) b.append("#define SHADE_WAVE_BAKE 1\n#define SHADE_WAVE_BAKE_SIZE ").append(BAKE_SIZE).append('\n');
		b.append("#define SHADE_WATER_SUBRECT 1\n");
		if (mcopt.metal.shade.taa.TaaConfig.ENABLED) b.append("#define SHADE_WATER_TAA 1\n");
		return b.append(ShadeMsl.resource("water.metal")).append('\n').toString();
	}

	private static boolean ready(long ctx) {
		if (state != 0) return true;
		if (failed || ctx == 0) return false;
		try {
			state = WaterNative.create(ctx);
			if (SSR) {
				try {
					long t0 = mcopt.metal.shade.ShadeCompile.now();
					WaterNative.setHz(state, mcopt.metal.shade.ShadeRender.pipeline(library(), "shade_fs_vs", "shade_water_hz_fs", mcopt.metal.shade.ShadeRender.R32F,
						mcopt.metal.shade.ShadeRender.BLEND_NONE));
					mcopt.metal.shade.ShadeCompile.setup("water", t0);
				} catch (Throwable e) {
					System.out.println("[mcopt] shade: water coarse depth failed, SSR finds nothing: " + e);
				}
			}
			System.out.println("[mcopt] shade: water on (ssr " + SSR + (SSR_QUAD ? " quad" : "") + ", " + SSR_STEPS + " steps, " + SSR_DIST + " blocks; waves " + WAVES
				+ ", detail " + DETAIL + ", ssr edges " + SSR_EDGE + ", real-time clock " + CLOCK + ", caustics " + CAUSTICS + ", foam " + FOAM + ", glossy " + GLOSSY + ", prev-frame reflections " + SSR_PREV + ", baked waves " + BAKE + ", glint graze " + GLINT_GRAZE + ", edge reflections " + EDGE_REFL + ", style " + (SMOOTH ? "photon (calm " + CALM + ", ripple " + RIPPLE + ")" : "m21") + ")");
		} catch (Throwable e) {
			failed = true;
			System.out.println("[mcopt] shade: water setup failed, M1 water from now on: " + e);
		}
		return state != 0;
	}

	private static long bakePso, bakeDetailPso, library;
	private static boolean bakeFailed, bakedOnce;
	private static int capturesAtBake = -1;

	private static long library() {
		if (library == 0) library = mcopt.metal.shade.ShadeRender.library(librarySource());
		return library;
	}

	public static String librarySource() {
		return mcopt.metal.shade.ShadeRender.prelude() + mcopt.metal.shade.ShadeRender.postSource() + prelude();
	}

	public static void bake(long ctx, long enc) {
		if (!BAKE || bakeFailed || enc == 0 || !ready(ctx)) return;
		boolean recent = captures != capturesAtBake;
		capturesAtBake = captures;
		if (!recent && bakedOnce) return;
		bakedOnce = true;
		try {
			if (bakePso == 0) {
				long t0 = mcopt.metal.shade.ShadeCompile.now();
				bakePso = mcopt.metal.shade.ShadeRender.pipeline(library(), "shade_fs_vs", "shade_water_bake_fs", mcopt.metal.shade.ShadeRender.RGBA16F,
					mcopt.metal.shade.ShadeRender.BLEND_NONE);
				mcopt.metal.shade.ShadeCompile.setup("water", t0);
			}
			long tex = WaterNative.waves(ctx, state, BAKE_SIZE);
			mcopt.metal.shade.ShadeRender.pass(enc, bakePso, tex, mcopt.metal.shade.ShadeRender.DONT_CARE, null, null, "mcopt shade water: wave bake");
			if (DETAIL) {
				if (bakeDetailPso == 0) {
					long t0 = mcopt.metal.shade.ShadeCompile.now();
					bakeDetailPso = mcopt.metal.shade.ShadeRender.pipeline(library(), "shade_fs_vs", "shade_water_bake_detail_fs",
						mcopt.metal.shade.ShadeRender.RGBA16F, mcopt.metal.shade.ShadeRender.BLEND_NONE);
					mcopt.metal.shade.ShadeCompile.setup("water", t0);
				}
				long det = WaterNative.wavesDetail(ctx, state, DETAIL_SIZE);
				mcopt.metal.shade.ShadeRender.pass(enc, bakeDetailPso, det, mcopt.metal.shade.ShadeRender.DONT_CARE, null, null, "mcopt shade water: ripple bake");
			}
			WaterNative.wavesDone(enc, state);
		} catch (Throwable e) {
			bakeFailed = true;
			System.out.println("[mcopt] shade: water wave bake failed, per-pixel waves from now on: " + e);
		}
	}

	public static void bind(long ctx, long enc) {
		if (ready(ctx)) WaterNative.bind(enc);
	}

	public static boolean capture(long ctx, long enc) {
		if (!ready(ctx)) return false;
		boolean done = WaterNative.capture(enc, state) != 0;
		if (done && captures++ == 0) System.out.println("[mcopt] shade: water scene copy active");
		return done;
	}

	public static void frame(long ctx, float[] still, float[] flow, float surfaceY, float wetness) {
		if (!ready(ctx)) return;
		ByteBuffer p = PARAMS;
		for (int i = 0; i < 4; i++) p.putFloat(i * 4, still[i]);
		for (int i = 0; i < 4; i++) p.putFloat(16 + i * 4, flow[i]);
		p.putFloat(32, 0f).putFloat(36, surfaceY).putFloat(40, wetness).putFloat(44, 0f);
		float ssrDist = Math.clamp(mcopt.metal.shade.Live.f("mcopt.shade.ssrDist", SSR_DIST), 8f, 1024f);
		p.putFloat(48, mcopt.metal.shade.ShadeSettings.waves()).putFloat(52, mcopt.metal.shade.ShadeSettings.ssr() ? ssrDist : 0f)
			.putFloat(56, mcopt.metal.shade.ShadeSettings.reflect())
			.putFloat(60, CAUSTICS ? mcopt.metal.shade.Live.f("mcopt.shade.user.caustics", 1f, 0f, 2f) : 0f);
		WaterNative.params(state, MemoryUtil.memAddress(p));
	}
}
