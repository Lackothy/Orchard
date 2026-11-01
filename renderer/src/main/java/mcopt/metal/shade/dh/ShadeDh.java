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
package mcopt.metal.shade.dh;

import java.lang.reflect.Method;
import java.util.List;
import mcopt.metal.MetalShaderRegistry;
import mcopt.metal.shade.ShadeMsl;

public final class ShadeDh {
	public static final boolean LOADED = mcopt.metal.DhCompat.loaded();
	public static final boolean LIGHT = LOADED && !"false".equals(System.getProperty("mcopt.shade.dh.light"));
	public static final List<String> PIPELINES = List.of("distanthorizons:opaque_terrain", "distanthorizons:transparent_terrain");
	private static final String CFG = "com.seibel.distanthorizons.core.config.Config$Client$Advanced$Graphics";
	private static final String USER = "mcopt-shade";

	private static final class Entry {
		final String cls, field;
		Object entry, applied;

		Entry(String cls, String field) {
			this.cls = cls;
			this.field = field;
		}
	}

	private static final Entry SSAO = new Entry(CFG, "enableSsao"), AA = new Entry(CFG, "enableAntiAliasing"),
		FADE = new Entry(CFG + "$Quality", "vanillaFadeMode"), FOG = new Entry(CFG + "$Fog", "enableDhFog"),
		SHADING = new Entry(CFG + "$Quality", "lodShading"), OVERDRAW = new Entry(CFG + "$Culling", "overdrawPrevention"),
		TRANSPARENCY = new Entry(CFG + "$Quality", "transparency"), RADIUS = new Entry(CFG + "$Quality", "lodChunkRenderDistanceRadius"),
		HQUALITY = new Entry(CFG + "$Quality", "horizontalQuality"), VQUALITY = new Entry(CFG + "$Quality", "verticalQuality"),
		MAXRES = new Entry(CFG + "$Quality", "maxHorizontalResolution");
	private static final Entry[] ALL = {SSAO, AA, FADE, FOG, SHADING, OVERDRAW, TRANSPARENCY, RADIUS, HQUALITY, VQUALITY, MAXRES};
	private static final String[][] PRESETS = {
		{"performance", "32", "LOW", "LOW", "TWO_BLOCKS"},
		{"balanced", "64", "MEDIUM", "MEDIUM", "BLOCK"},
		{"quality", "96", "HIGH", "MEDIUM", "BLOCK"},
		{"ultra", "128", "HIGH", "HIGH", "BLOCK"}};
	private static final java.util.Map<String, Object[]> PRESET_VALUES = new java.util.HashMap<>();
	private static Object transparencyComplete;
	private static Method setApi, getTrue;
	private static boolean warnedFade;
	private static Class<?> fadeEnum, shadingEnum;
	private static boolean failed, resolved, logged;
	public static final boolean DIRECT = LIGHT && !"false".equals(System.getProperty("mcopt.shade.dh.direct"));
	private static volatile boolean directFrame;

	public static boolean directFrame() {
		return directFrame;
	}

	public static float directFlag(boolean hdrFrame) {
		directFrame = DIRECT && hdrFrame && !"false".equals(System.getProperty("mcopt.shade.dh.direct"));
		return directFrame ? 1f : 0f;
	}

	private ShadeDh() {
	}

	public static void register() {
		if (!LIGHT) return;
		String defines = (Boolean.getBoolean("mcopt.shade.dh.jitter") ? "#define SHADE_DH_JITTER\n" : "")
			+ (Boolean.getBoolean("mcopt.shade.dh.skyHigh") ? "#define SHADE_DH_SKY_HIGH\n" : "")
			+ (Boolean.getBoolean("mcopt.shade.dh.noclip") ? "#define SHADE_DH_NOCLIP\n" : "");
		String vertex = ShadeMsl.prelude() + defines + ShadeMsl.resource("dh_terrain.vs.metal");
		for (String name : PIPELINES) {
			boolean water = name.endsWith("transparent_terrain") && !"false".equals(System.getProperty("mcopt.shade.dh.water"));
			String fragment = ShadeMsl.prelude() + defines + (water ? "#define SHADE_DH_TRANSLUCENT\n" : "") + ShadeMsl.resource("dh_terrain.fs.metal");
			MetalShaderRegistry.register(name, new MetalShaderRegistry.Variant(generated -> vertex, generated -> fragment,
				List.of("vertSharedUniformBlock", "vertUniqueUniformBlock", "fragUniformBlock", "uBlockAtlas", "uLightMap")));
		}
		if (DIRECT) {
			String fade = ShadeMsl.prelude() + ShadeMsl.resource("dh_far_fade.fs.metal");
			String copy = ShadeMsl.prelude() + ShadeMsl.resource("dh_copy.fs.metal");
			MetalShaderRegistry.register("distanthorizons:far_fade", new MetalShaderRegistry.Variant(null, generated -> fade,
				List.of("fragUniformBlock", "uMcColorTexture", "uDhColorTexture", "uDhDepthTexture")));
			MetalShaderRegistry.register("distanthorizons:copy", new MetalShaderRegistry.Variant(null, generated -> copy, List.of("uCopyTexture")));
		}
		System.out.println("[mcopt] shade dh: Distant Horizons' LOD terrain lit by the shade model (mcopt.shade.dh.light=false: DH's own look)");
	}

	public static void frame(boolean shading) {
		if (!LOADED || failed) return;
		if (!resolved && !resolve()) return;
		try {
			apply(SSAO, shading ? off("mcopt.shade.dh.ssao") : null);
			apply(AA, shading ? off("mcopt.shade.dh.aa") : null);
			apply(FADE, shading ? named(fadeEnum, "mcopt.shade.dh.fade", "dh") : null);
			if (shading && DIRECT && !warnedFade && "DOUBLE_PASS".equals(String.valueOf(getTrue.invoke(FADE.entry)))) {
				warnedFade = true;
				System.out.println("[mcopt] shade dh: WARNING: Distant Horizons' vanilla fade is DOUBLE_PASS, which hides the near world with mcopt-shade's"
					+ " direct composite. Set it to SINGLE_PASS or NONE (DH settings > Graphics > Quality > Vanilla fade) and restart the game.");
			}
			apply(FOG, shading && LIGHT ? off("mcopt.shade.dh.fog") : null);
			apply(SHADING, shading && LIGHT && off("mcopt.shade.dh.shading") != null ? named(shadingEnum, "-", "DISABLED") : null);
			apply(OVERDRAW, shading ? number("mcopt.shade.dh.overdraw", "0.5") : null);
			apply(TRANSPARENCY, shading && !"dh".equalsIgnoreCase(System.getProperty("mcopt.shade.dh.transparency", "complete")) ? transparencyComplete : null);
			Object[] preset = shading ? PRESET_VALUES.get(System.getProperty("mcopt.shade.dh.preset", "balanced")) : null;
			apply(RADIUS, preset == null ? null : preset[0]);
			apply(HQUALITY, preset == null ? null : preset[1]);
			apply(VQUALITY, preset == null ? null : preset[2]);
			apply(MAXRES, preset == null ? null : preset[3]);
		} catch (Throwable t) {
			fail(t);
			return;
		}
		if (!logged && shading) {
			logged = true;
			StringBuilder b = new StringBuilder("[mcopt] shade dh: while the shade frame runs, Distant Horizons'");
			for (Entry e : ALL) b.append(' ').append(e.field).append('=').append(e.applied == null ? "(DH's)" : e.applied);
			System.out.println(b);
		}
	}

	private static void apply(Entry e, Object want) throws ReflectiveOperationException {
		if (want != e.applied) setApi.invoke(e.entry, e.applied = want, USER);
	}

	private static Object off(String key) {
		return "dh".equalsIgnoreCase(System.getProperty(key, "off")) ? null : Boolean.FALSE;
	}

	private static Object number(String key, String def) {
		String v = System.getProperty(key, def);
		if ("dh".equalsIgnoreCase(v)) return null;
		try {
			float f = Float.parseFloat(v);
			return OVERDRAW.applied instanceof Float a && a == f ? a : Float.valueOf(f);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static Object named(Class<?> type, String key, String def) {
		String v = System.getProperty(key, def);
		if ("dh".equalsIgnoreCase(v)) return null;
		try {
			return Enum.valueOf((Class<? extends Enum>) type, v.toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return "dh".equalsIgnoreCase(def) ? null : Enum.valueOf((Class<? extends Enum>) type, def);
		}
	}

	private static boolean resolve() {
		try {
			ClassLoader cl = ShadeDh.class.getClassLoader();
			for (Entry e : ALL) {
				e.entry = Class.forName(e.cls, true, cl).getField(e.field).get(null);
				if (e.entry == null) return false;
			}
			setApi = SSAO.entry.getClass().getMethod("setApiValue", Object.class, String.class);
			getTrue = SSAO.entry.getClass().getMethod("getTrueValue");
			fadeEnum = Class.forName("com.seibel.distanthorizons.api.enums.config.EDhApiMcRenderingFadeMode", true, cl);
			shadingEnum = Class.forName("com.seibel.distanthorizons.api.enums.config.EDhApiLodShading", true, cl);
			transparencyComplete = constant(cl, "com.seibel.distanthorizons.api.enums.rendering.EDhApiTransparency", "COMPLETE");
			for (String[] p : PRESETS) {
				PRESET_VALUES.put(p[0], new Object[] {Integer.valueOf(p[1]),
					constant(cl, "com.seibel.distanthorizons.api.enums.config.EDhApiHorizontalQuality", p[2]),
					constant(cl, "com.seibel.distanthorizons.api.enums.config.EDhApiVerticalQuality", p[3]),
					constant(cl, "com.seibel.distanthorizons.api.enums.config.EDhApiMaxHorizontalResolution", p[4])});
			}
			resolved = true;
			return true;
		} catch (Throwable t) {
			fail(t);
			return false;
		}
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static Object constant(ClassLoader cl, String type, String name) throws ClassNotFoundException {
		return Enum.valueOf((Class<? extends Enum>) Class.forName(type, true, cl), name);
	}

	private static void fail(Throwable t) {
		failed = true;
		System.out.println("[mcopt] shade dh: could not switch Distant Horizons' settings (" + t + "); its own settings stay");
	}
}
