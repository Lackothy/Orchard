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
package mcopt.metal.shade.weather;

import java.nio.ByteBuffer;
import java.util.Locale;
import mcopt.metal.shade.Live;
import mcopt.metal.shade.Shade;
import mcopt.metal.shade.ShadeLook;
import mcopt.metal.shade.ShadeMsl;

public final class ShadeWeather {
	public static final boolean MODULE = flag("mcopt.shade.weather", true);
	public static final boolean ON = Shade.ENABLED && MODULE;
	public static final boolean PUDDLES = flag("mcopt.shade.puddles", true);
	public static final boolean PUDDLE_SSR = PUDDLES && flag("mcopt.shade.puddleSsr", Shade.TIER >= 1);
	public static final boolean PUDDLE_QUAD = flag("mcopt.shade.puddleQuad", Shade.TIER < 3);
	public static final int PUDDLE_STEPS = Math.clamp(Integer.getInteger("mcopt.shade.puddleSteps", PUDDLE_QUAD ? new int[] {12, 12, 20, 24}[Shade.TIER]
		: new int[] {8, 10, 14, 20}[Shade.TIER]), 4, 64);
	public static final float PUDDLE_DIST = Math.clamp(floatFlag("mcopt.shade.puddleDist", 64f), 8f, 256f);
	public static final boolean RIPPLES = flag("mcopt.shade.ripples", true);
	public static final int RIPPLE_LAYERS = Integer.getInteger("mcopt.shade.rippleLayers", Shade.TIER >= 2 ? 2 : 1) >= 2 ? 2 : 1;
	public static final boolean LITE = flag("mcopt.shade.weatherLite", Shade.TIER <= 1);
	public static final String RAIN_BUDGET = System.getProperty("mcopt.shade.rainBudget", "").trim().toLowerCase(Locale.ROOT);
	public static final boolean B_GATE = budget("gate"), B_RIPPLE = budget("ripples"), B_STEPS = budget("steps");
	public static final boolean B_DEV = flag("mcopt.shade.rainBudget.dev", false);
	public static final boolean SNOW = flag("mcopt.shade.snowCover", true);
	public static final boolean SNOW_LINE = flag("mcopt.shade.snowLine", true);
	public static final boolean SNOW_GRAIN = flag("mcopt.shade.snowGrain", true);
	public static final boolean SNOW_LINE_COVER = flag("mcopt.shade.snowLine.cover", false);
	static final float LINE_BAND = 8f;
	public static final boolean SLANT = flag("mcopt.shade.rainSlant", true);
	public static final boolean LIGHTNING = flag("mcopt.shade.lightning", true);
	public static final boolean COLOR_SHADOWS = ON && Shade.SHADOWS && mcopt.metal.shade.ShadeConfig.HDR && flag("mcopt.shade.colorShadows", true);
	public static final int COLOR_SHADOW_RES = Integer.getInteger("mcopt.shade.colorShadowRes", Shade.TIER >= 3 ? 2048 : 1024) >= 2048 ? 2048 : 1024;
	public static final float TIME_SCALE = Math.clamp(floatFlag("mcopt.shade.weatherTime", 1f), 0.1f, 100f);
	public static final float FORCE_WET = floatFlag("mcopt.shade.wetness", -1f);
	public static final float FORCE_SNOW = floatFlag("mcopt.shade.snowAmount", -1f);
	public static final boolean WET_ALL = flag("mcopt.shade.wetAll", true);
	static final float WET_SSR = PUDDLE_SSR ? 0.06f : 0f;

	public static final int RES_PREV_DEPTH = 5, RES_SHADOW_TINT = 6, RES_NOISE = 4;

	private static long noiseTexture;
	private static boolean noiseFailed;

	static float wetness, puddles, snow, rain;
	static float windAngle, slant, gust, snowfall;
	static float snowFloor = -1e4f, rainTop = 1e4f;
	static final float[] flashDir = {0f, 1f, 0f};
	static float flash;

	private ShadeWeather() {
	}

	public static boolean dry() {
		return wetNow() < 0.003f && puddlesNow() < 0.003f && snowNow() < 0.003f && rain < 0.003f && snowfall < 0.003f && flash <= 0f;
	}

	static boolean flag(String key, boolean fallback) {
		String v = System.getProperty(key);
		return v == null ? fallback : Boolean.parseBoolean(v.trim());
	}

	static float floatFlag(String key, float fallback) {
		try {
			String v = System.getProperty(key);
			return v == null ? fallback : Float.parseFloat(v.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	static void step(float dt, float rainTarget, float snowTarget, boolean snowing, float line, float camY, float thunder, float dayIndex,
	                 float seconds, boolean snap) {
		float s = dt * timeScale();
		float wetTarget = rainTarget;
		float puddleTarget = rainTarget > 0.05f ? Math.min(Math.max((wetness - 0.35f) / 0.65f, 0f), rainTarget) : 0f;
		if (snap) {
			wetness = wetTarget;
			puddles = wetTarget;
			snow = snowTarget;
			rain = rainTarget;
			snowfall = snowing && snowTarget > 0.05f ? 1f : 0f;
		} else {
			wetness = dryTo(wetness, wetTarget, s, 14f, 75f);
			puddles = dryTo(puddles, puddleTarget, s, 10f, 110f);
			snow = dryTo(snow, snowTarget, s, 30f, 240f);
			rain = approach(rain, rainTarget, dt, 1.5f, 3f);
			snowfall = approach(snowfall, snowing && snowTarget > 0.05f ? 1f : 0f, dt, 1.5f, 1.5f);
		}
		if (snowLine() && !Float.isNaN(line)) {
			float lo = camY - 400f, hi = camY + 400f, lt = Math.clamp(line, lo, hi);
			float k = snap ? 1f : 1f - (float) Math.exp(-dt / 1.5f);
			if (snowTarget > 0f) {
				float v = Math.clamp(snowFloor, lo, hi);
				snowFloor = v + (lt - v) * k;
			}
			if (rainTarget > 0f) {
				float v = Math.clamp(rainTop, lo, hi);
				rainTop = v + (lt - v) * k;
			}
		}
		float forceWet = floatFlag("mcopt.shade.wetness", FORCE_WET);
		if (forceWet >= 0f) {
			wetness = Math.min(forceWet, 1f);
			puddles = Math.min(forceWet, 1f);
		}
		float forceSnow = Live.f("mcopt.shade.snowAmount", FORCE_SNOW);
		if (forceSnow >= 0f) snow = Math.min(forceSnow, 1f);
		double h = Math.sin(dayIndex * 12.9898 + 78.233) * 43758.5453;
		windAngle = (float) ((h - Math.floor(h)) * Math.PI * 2 + 0.35 * Math.sin(seconds / 97.0));
		gust = (float) (0.5 + 0.5 * Math.sin(seconds * 0.71) * Math.sin(seconds * 0.23 + 1.3));
		slant = rainSlant() ? (0.12f + 0.22f * thunder) * (0.75f + 0.5f * gust) : 0f;
	}

	static float timeScale() {
		return Live.f("mcopt.shade.weatherTime", TIME_SCALE, 0.1f, 100f);
	}

	static boolean snowLine() {
		return Live.b("mcopt.shade.snowLine", SNOW_LINE);
	}

	static boolean rainSlant() {
		return SLANT && Live.b("mcopt.shade.rainSlant", true);
	}

	static boolean lightning() {
		return LIGHTNING && Live.b("mcopt.shade.lightning", true);
	}

	public static boolean colorShadows() {
		return COLOR_SHADOWS && Live.b("mcopt.shade.colorShadows", true);
	}

	private static float amount(float v, String key) {
		float k = Live.f(key, 1f, 0f, 2f);
		return k == 1f ? v : Math.min(Math.max(v * k, 0f), 1f);
	}

	private static float wetNow() {
		return amount(wetness, "mcopt.shade.user.wetness");
	}

	private static float puddlesNow() {
		return amount(puddles, "mcopt.shade.user.puddles");
	}

	private static float snowNow() {
		return amount(snow, "mcopt.shade.user.snow");
	}

	private static float dryTo(float v, float target, float dt, float rise, float fall) {
		return target > 0f ? approach(v, target, dt, rise, fall) : Math.max(approach(v, -0.02f, dt, rise, fall), 0f);
	}

	private static float approach(float v, float target, float dt, float rise, float fall) {
		float tau = target > v ? rise : fall;
		return target + (v - target) * (float) Math.exp(-dt / tau);
	}

	public static void pack(ByteBuffer b) {
		if (!ON) {
			put4(b, ShadeLook.WET, 0f, 0f, 0f, 0f);
			put4(b, ShadeLook.WIND, 1f, 0f, 0f, 0f);
			put4(b, ShadeLook.FLASH, 0f, 1f, 0f, 0f);
			put4(b, ShadeLook.WATER, 0f, 0f, 0f, 0f);
			put4(b, ShadeLook.WATER_ABS, 0f, 0.55f, 0f, 0.12f);
			return;
		}
		boolean wetAll = WET_ALL && flag("mcopt.shade.wetAll", true);
		put4(b, ShadeLook.WATER, wetAll ? 1f : 0f, floatFlag("mcopt.shade.wetRough", 0.2f), floatFlag("mcopt.shade.wetVeg", 0.18f),
			wetAll ? Math.max(floatFlag("mcopt.shade.wetSsr", WET_SSR), 0f) : 0f);
		put4(b, ShadeLook.WATER_ABS, floatFlag("mcopt.shade.wetDark", 0.2f), floatFlag("mcopt.shade.wetVegFilm", 0.55f), floatFlag("mcopt.shade.wetTex", 0f),
			Math.max(floatFlag("mcopt.shade.wetRim", 0.12f), 0.005f));
		put4(b, ShadeLook.WET, wetNow(), PUDDLES ? puddlesNow() : 0f, SNOW ? snowNow() : 0f, rain);
		put4(b, ShadeLook.WIND, (float) Math.cos(windAngle), (float) Math.sin(windAngle), slant, snowfall);
		put4(b, ShadeLook.FLASH, flashDir[0], flashDir[1], flashDir[2], lightning() ? flash : 0f);
		b.putFloat(ShadeLook.SSR + 8, noiseTexture != 0 ? 1f : 0f);
		b.putFloat(ShadeLook.SSR + 12, 0f);
		put4(b, ShadeLook.TINT_RECT, 1f, 1f, -1f, -1f);
	}

	public static void bakeNoise(long enc) {
		if (!ON || noiseTexture != 0 || noiseFailed || enc == 0 || !(PUDDLES || SNOW)) return;
		try {
			long t0 = mcopt.metal.shade.ShadeCompile.now();
			long lib = mcopt.metal.shade.ShadeRender.library(noiseSource());
			long pso = mcopt.metal.shade.ShadeRender.pipeline(lib, "shade_fs_vs", "shade_wx_noise_fs", mcopt.metal.shade.ShadeRender.RG16F,
				mcopt.metal.shade.ShadeRender.BLEND_NONE);
			mcopt.metal.shade.ShadeCompile.setup("weather noise", t0);
			long tex = mcopt.metal.shade.ShadeRender.texture(mcopt.metal.shade.ShadeRender.RG16F, 1024, 1024, false, "mcopt shade weather noise");
			mcopt.metal.shade.ShadeRender.pass(enc, pso, tex, mcopt.metal.shade.ShadeRender.DONT_CARE, null, null, "mcopt shade weather: noise bake");
			mcopt.metal.shade.ShadeRender.resource(RES_NOISE, tex);
			noiseTexture = tex;
		} catch (Throwable e) {
			noiseFailed = true;
			System.out.println("[mcopt] shade: weather noise bake failed, per-pixel noise from now on: " + e);
		}
	}

	public static void packLine(ByteBuffer b, int at) {
		boolean on = ON && snowLine();
		int bits = (on ? 1 : 0) | (flag("mcopt.shade.wetWalls", true) ? 2 : 0) | (flag("mcopt.shade.wetVegLight", true) ? 4 : 0)
			| (flag("mcopt.shade.wetVegPuddles", true) ? 8 : 0);
		put4(b, at, on ? snowFloor : -1e4f, on ? rainTop : 1e4f, LINE_BAND, bits);
	}

	private static void put4(ByteBuffer b, int at, float x, float y, float z, float w) {
		b.putFloat(at, x).putFloat(at + 4, y).putFloat(at + 8, z).putFloat(at + 12, w);
	}

	public static boolean tintHidden() {
		return ON && rain >= 0.8f && (LITE || (Integer.getInteger("mcopt.shade.dev", 0) & 256) != 0);
	}

	public static float rainIntensity() {
		return rain;
	}

	public static String describeState() {
		return String.format(Locale.ROOT, "wet %.2f puddles %.2f snow %.2f rain %.2f flash %.2f", wetness, puddles, snow, rain, flash)
			+ (snowLine() ? String.format(Locale.ROOT, ", snow from y %.0f, rain up to y %.0f", snowFloor, rainTop) : "");
	}

	public static String prelude() {
		return prelude(false);
	}

	public static String noiseSource() {
		return mcopt.metal.shade.ShadeRender.prelude() + mcopt.metal.shade.ShadeRender.postSource() + "#define SHADE_WX_NOISE_BAKE\n" + prelude();
	}

	public static String prelude(boolean translucent) {
		if (!MODULE) return "";
		StringBuilder s = new StringBuilder("#define SHADE_WEATHER\n");
		if (LITE) s.append("#define SHADE_WX_LITE\n");
		if (B_GATE) s.append("#define SHADE_WX_B_GATE\n");
		if (B_RIPPLE) s.append("#define SHADE_WX_B_RIPPLE\n");
		if (B_STEPS) s.append("#define SHADE_WX_B_STEPS\n");
		if (B_DEV) s.append("#define SHADE_WX_B_DEV\n");
		if (WET_ALL && !translucent) s.append("#define SHADE_WET_ALL\n");
		if (PUDDLES && !translucent) s.append("#define SHADE_PUDDLES\n");
		if (PUDDLE_SSR && !translucent) s.append("#define SHADE_PUDDLE_SSR\n#define SHADE_PUDDLE_STEPS ").append(PUDDLE_STEPS).append('\n')
			.append("#define SHADE_PUDDLE_DIST ").append(String.format(Locale.ROOT, "%.1f", PUDDLE_DIST)).append('\n')
			.append(PUDDLE_QUAD ? "#define SHADE_PUDDLE_QUAD\n" : "");
		if (RIPPLES) s.append("#define SHADE_RIPPLES\n#define SHADE_RIPPLE_LAYERS ").append(RIPPLE_LAYERS).append('\n');
		if (SNOW) s.append("#define SHADE_SNOW_COVER\n");
		if (SNOW && SNOW_GRAIN) s.append("#define SHADE_SNOW_GRAIN\n");
		if (LIGHTNING) s.append("#define SHADE_LIGHTNING\n");
		if (COLOR_SHADOWS || (!Shade.ENABLED && flag("mcopt.shade.colorShadows", true))) s.append("#define SHADE_COLOR_SHADOW\n");
		return s.append(ShadeMsl.resource("weather_surface.metal")).append('\n').toString();
	}

	private static boolean budget(String option) {
		for (String o : RAIN_BUDGET.split(",")) if (o.trim().equals(option)) return true;
		return false;
	}

	static String describe() {
		return (LITE ? "lite, " : "") + (WET_ALL ? "wet all, " : "") + (RAIN_BUDGET.isEmpty() ? "" : "rain budget " + RAIN_BUDGET + ", ") + "puddles " + PUDDLES + (PUDDLE_SSR ? " (ssr " + PUDDLE_STEPS + " steps" + (PUDDLE_QUAD ? ", quad" : "") + ")" : "") + ", ripples " + RIPPLES + ", snow " + SNOW + ", slant " + SLANT
			+ ", lightning " + LIGHTNING + ", coloured shadows " + (COLOR_SHADOWS ? COLOR_SHADOW_RES + "^2" : "off");
	}
}
