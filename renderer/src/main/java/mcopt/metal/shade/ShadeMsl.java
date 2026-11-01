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
package mcopt.metal.shade;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mcopt.metal.MetalShaderRegistry;
import mcopt.metal.shade.water.ShadeWater;
import mcopt.metal.shade.weather.ShadeWeather;

public final class ShadeMsl {
	public static final int LUT_WIDTH = 128, LUT_HEIGHT = 80;
	public static final String SKY = "minecraft:pipeline/sky", CELESTIAL = "minecraft:pipeline/celestial", SUNRISE = "minecraft:pipeline/sunrise_sunset";
	public static final List<String> TERRAIN = List.of();

	public static final String WEATHER = "minecraft:pipeline/weather", STARS = "minecraft:pipeline/stars", LIGHTNING = "minecraft:pipeline/lightning";
	public static final String END_SKY = "minecraft:pipeline/end_sky";
	public static final List<String> CLOUDS = List.of("minecraft:pipeline/clouds", "minecraft:pipeline/flat_clouds");

	private static volatile String prelude, look, skyVertex, skyFragment, celestialVertex, sunriseVertex, starsVertex, cloudFragment, endSkyVertex, endSkyFragment;

	private ShadeMsl() {
	}

	public static String resource(String name) {
		try (InputStream in = ShadeMsl.class.getResourceAsStream("/mcopt/shade/" + name)) {
			return new String(Objects.requireNonNull(in, name).readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	static final String HAZE_DEFINES = (Boolean.parseBoolean(System.getProperty("mcopt.shade.hazeEdge", "true")) ? "#define SHADE_HAZE_EDGE 1\n" : "")
		+ "#define SHADE_HAZE_MIE_G " + Math.clamp(Float.parseFloat(System.getProperty("mcopt.shade.hazeMieG", "0.5")), 0f, 0.95f) + "\n"
		+ (mcopt.metal.shade.taa.TaaConfig.ENABLED && Boolean.getBoolean("mcopt.shade.pcssNoise") ? "#define SHADE_PCSS_TAA 1\n" : "")
		+ (Boolean.parseBoolean(System.getProperty("mcopt.shade.rgssGate", "true")) ? "#define SHADE_RGSS_GATE 1\n" : "")
		+ (!"false".equals(System.getProperty("mcopt.shade.rgssOne")) ? "#define SHADE_RGSS_ONE 1\n" : "");

	public static String prelude() {
		if (prelude == null) {
			prelude = "#define MCOPT_SHADE\n" + HAZE_DEFINES + resource("shade_look.metal") + "\n" + resource("shade_common.metal") + "\n" + resource("shade_clouds.metal") + "\n"
				+ resource("shade_m2.metal") + "\n"
				+ resource("shade_dims.metal") + "\n"
				+ mcopt.metal.shade.light.DynLightMsl.prelude()
				+ resource("shade_dynlight.metal") + "\n";
		}
		return prelude;
	}

	public static String ownPrefix() {
		return "#define MCOPT_SHADE\n" + look();
	}

	public static final int PBR_MATERIALS = 1 << 8;

	public static String ownFragmentSource(boolean translucent, int leanBits) {
		return (ShadeConfig.HDR ? "#define MCOPT_HDR\n" : "") + (OWN_NONCE != null ? "#define SHADE_OWN_NONCE " + OWN_NONCE + "\n" : "")
			+ ((leanBits & PBR_MATERIALS) != 0 ? "#define SHADE_PBR\n" : "")
			+ leanDefines((leanBits & ~PBR_MATERIALS) << LEAN_SHIFT) + LEAN_TEST
			+ (translucent ? "#define SHADE_OWN_TRANSLUCENT\n" : "") + prelude() + ShadeWeather.prelude(translucent)
			+ (translucent && ShadeWater.MODULE ? ShadeWater.prelude() : "") + resource("terrain_own.metal");
	}

	private static final Integer OWN_NONCE = Integer.getInteger("mcopt.shade.terrain.nonce");

	public static String look() {
		if (look == null) look = resource("shade_look.metal") + "\n";
		return look;
	}

	public static String post() {
		return prelude() + resource("shade_post.metal");
	}

	public static String terrain(String source) {
		return terrain(source, ShadeWater.MODULE);
	}

	public static String terrain(String source, boolean water) {
		String weather = source.contains("fragment main0_out main0(") ? ShadeWeather.prelude(source.startsWith(TRANSLUCENT_DEFINE)) : "";
		String lean = source.contains("fragment main0_out main0(") ? LEAN_TEST : "";
		if (water && source.startsWith(TRANSLUCENT_DEFINE) && source.contains("fragment main0_out main0(")) return lean + prelude() + weather + ShadeWater.prelude() + source;
		return lean + prelude() + weather + source;
	}

	private static final String TRANSLUCENT_DEFINE = "#define ALPHA_CUTOUT 0.01";
	public static final int LEAN_SHIFT = mcopt.metal.MetalHooks.TAG_SHIFT + 1, LEAN_OW = 1, LEAN_DRY = 2, LEAN_NODL = 4, LEAN_NOAURORA = 8,
		LEAN_NETHER = 16,
		LEAN_ABOVE = 32, LEAN_DEV = 64, LEAN_NOFALLS = 128, LEAN_WATER = LEAN_ABOVE | LEAN_DEV | LEAN_NOFALLS,
		LEAN_TERRAIN = LEAN_OW | LEAN_DRY | LEAN_NODL | LEAN_NETHER, LEAN_MASK = 255 << LEAN_SHIFT;

	static final String TRANSLUCENT_TERRAIN = "sodium:pipeline/translucent_terrain";

	static boolean leanTerrain(String pipelineName) {
		return pipelineName.startsWith("sodium:pipeline/") && pipelineName.endsWith("_terrain");
	}

	static String leanDefines(int format) {
		int t = (format & LEAN_MASK) >>> LEAN_SHIFT;
		return ((t & LEAN_OW) != 0 ? "#define SHADE_LEAN_OW\n" : "") + ((t & LEAN_DRY) != 0 ? "#define SHADE_LEAN_DRY\n" : "")
			+ ((t & LEAN_NODL) != 0 ? "#define SHADE_LEAN_NODL\n" : "") + ((t & LEAN_NOAURORA) != 0 ? "#define SHADE_LEAN_NOAURORA\n" : "")
			+ ((t & LEAN_NETHER) != 0 ? "#define SHADE_LEAN_NETHER\n" : "") + ((t & LEAN_ABOVE) != 0 ? "#define SHADE_LEAN_ABOVE\n" : "")
			+ ((t & LEAN_NOFALLS) != 0 ? "#define SHADE_LEAN_NOFALLS\n" : "")
			+ ((t & LEAN_DEV) != 0 ? ShadeLean.DEV_DEFINES : "");
	}
	private static final String LEAN_TEST = leanTest(System.getProperty("mcopt.shade.leanTest", ""));

	private static String leanTest(String v) {
		StringBuilder b = new StringBuilder();
		for (String t : v.split(",")) {
			switch (t.trim()) {
				case "ow" -> b.append("#define SHADE_LEAN_OW\n");
				case "dry" -> b.append("#define SHADE_LEAN_DRY\n");
				case "nodl" -> b.append("#define SHADE_LEAN_NODL\n");
				default -> { }
			}
		}
		return b.toString();
	}

	public static String skyStage(String file) {
		return prelude() + resource("vanilla_uniforms.metal") + "\n" + resource(file);
	}

	public static String lut() {
		return resource("shade_look.metal") + "\n" + resource("shade_common.metal") + "\n" + resource("sky_lut.metal");
	}

	private static final String WEATHER_SIGNATURE = "fragment main0_out main0(main0_in in [[stage_in]]";
	private static final String WEATHER_RESULT = "out.fragColor = calculateFinalColor(";

	public static String weather(String generated) {
		int at = generated.indexOf(WEATHER_RESULT);
		if (!generated.contains(WEATHER_SIGNATURE) || at < 0) return generated;
		int end = generated.indexOf(';', at) + 1;
		boolean m3 = ShadeWeather.MODULE && !generated.contains("[[position]]");
		String call = m3 ? "shadeWeather3(out.fragColor, mcoptFragCoord, mcoptShadeFrame, mcoptShadeLook, mcoptShadeGpu, mcoptSkyLut, mcoptSkyLutSmplr)"
			: "shadeWeather(out.fragColor, mcoptShadeFrame, mcoptShadeLook, mcoptShadeGpu)";
		String patched = generated.substring(0, end) + "\n    out.fragColor = " + call + ";" + generated.substring(end);
		patched = patched.replace(WEATHER_SIGNATURE, WEATHER_SIGNATURE
			+ ", constant ShadeFrame& mcoptShadeFrame [[buffer(16)]], constant ShadeLook& mcoptShadeLook [[buffer(17)]], constant ShadeGpu& mcoptShadeGpu [[buffer(18)]]"
			+ (m3 ? ", float4 mcoptFragCoord [[position]], texture2d<half> mcoptSkyLut [[texture(17)]], sampler mcoptSkyLutSmplr [[sampler(13)]]" : ""));
		return prelude() + (m3 ? "#define SHADE_WEATHER\n" + (ShadeWeather.LITE ? "#define SHADE_WX_LITE\n" : "") : "") + resource("weather.metal") + "\n" + patched;
	}

	private static final String VERTEX_SIGNATURE = "vertex main0_out main0(main0_in in [[stage_in]]";
	private static final String VERTEX_BODY = "main0_out out = {};";

	public static String weatherVertex(String generated) {
		if (!ShadeWeather.MODULE || !ShadeWeather.SLANT || !generated.contains(VERTEX_SIGNATURE) || !generated.contains(VERTEX_BODY)
			|| !generated.contains("in.Position")) return generated;
		String patched = generated.replace(VERTEX_BODY, VERTEX_BODY + "\n    in.Position = shadeRainSlant(in.Position, mcoptShadeLook);")
			.replace(VERTEX_SIGNATURE, VERTEX_SIGNATURE + ", constant ShadeLook& mcoptShadeLook [[buffer(17)]]");
		return prelude() + resource("weather.metal") + "\n" + patched;
	}

	private static final String LIGHTNING_RESULT = "out.fragColor = calculateFinalColor(";

	public static String lightning(String generated) {
		int at = generated.indexOf(LIGHTNING_RESULT);
		if (!ShadeWeather.MODULE || !ShadeWeather.LIGHTNING || !generated.contains(FRAGMENT_ENTRY) || at < 0) return generated;
		int end = generated.indexOf(';', at) + 1;
		String patched = generated.substring(0, end)
			+ "\n#ifdef MCOPT_HDR\n    out.fragColor.rgb = min(out.fragColor.rgb * float3(30.0, 33.0, 40.0), float3(6.0e4));\n#endif" + generated.substring(end);
		return patched;
	}

	private static final String FRAGMENT_ENTRY = "fragment main0_out main0(";
	private static final Pattern COLOR0 = Pattern.compile("(?:float4|half4)\\s+(\\w+)\\s*\\[\\[color\\(0\\)\\]\\]");

	public static String hdrFragment(String msl) {
		int sig = msl.indexOf(FRAGMENT_ENTRY);
		if (sig < 0) return msl;
		Matcher m = COLOR0.matcher(msl);
		if (!m.find() || m.start() > sig) return msl;
		String member = m.group(1);
		int open = sig + FRAGMENT_ENTRY.length() - 1, depth = 0, close = -1;
		for (int i = open; i < msl.length(); i++) {
			char c = msl.charAt(i);
			if (c == '(') depth++;
			else if (c == ')' && --depth == 0) {
				close = i;
				break;
			}
		}
		if (close < 0) return msl;
		String head = msl.substring(0, close) + ", constant ShadeLook& mcoptShadeLook [[buffer(17)]]";
		String body = msl.substring(close).replace("return out;", "{ out." + member + " = shadeLdrToHdr(out." + member + ", mcoptShadeLook); return out; }");
		return look() + head + body;
	}

	static boolean handTarget(int[] colorFormats) {
		return colorFormats.length == 1 && colorFormats[0] == ShadeEntity.HAND_FORMAT;
	}

	static boolean hdrTarget(int[] colorFormats) {
		if (colorFormats.length != 1) return false;
		int f = colorFormats[0] & ~LEAN_MASK;
		return f == ShadeRender.RGBA16F || f == ShadeRender.RG11B10F;
	}

	static final MetalShaderRegistry.FormatAdapter FORMAT_ADAPTER = new MetalShaderRegistry.FormatAdapter() {
		@Override
		public String vertex(String pipelineName, String msl, boolean registry, int[] colorFormats) {
			ShadeEntity.beginVariant();
			if (handTarget(colorFormats)) {
				String lit = registry ? null : ShadeEntity.vertex(pipelineName, msl);
				return lit != null ? lit : msl;
			}
			if (!hdrTarget(colorFormats)) return msl;
			if (registry) return "#define MCOPT_HDR\n" + msl;
			String dyn = mcopt.metal.shade.light.DynLightMsl.patchVertex(pipelineName, msl);
			String lit = ShadeEntity.vertex(pipelineName, dyn);
			return lit != null ? lit : dyn;
		}

		@Override
		public String fragment(String pipelineName, String msl, boolean registry, int[] colorFormats) {
			if (handTarget(colorFormats)) {
				String lit = registry ? null : ShadeEntity.fragment(pipelineName, msl, true);
				return lit != null ? lit : msl;
			}
			if (!hdrTarget(colorFormats)) return msl;
			if (registry) return "#define MCOPT_HDR\n" + leanDefines(colorFormats[0]) + msl;
			String lit = ShadeEntity.fragment(pipelineName, msl, false);
			return lit != null ? lit : ShadeBeacon.fragment(pipelineName, msl);
		}

		@Override
		public int[] canonical(String pipelineName, int[] colorFormats) {
			if (colorFormats.length != 1 || (colorFormats[0] & LEAN_MASK) == 0) return colorFormats;
			int keep = leanTerrain(pipelineName) ? LEAN_TERRAIN | (TRANSLUCENT_TERRAIN.equals(pipelineName) ? LEAN_WATER : 0)
				: SKY.equals(pipelineName) ? LEAN_NOAURORA : 0;
			int f = colorFormats[0] & ~LEAN_MASK | colorFormats[0] & keep << LEAN_SHIFT;
			return f == colorFormats[0] ? colorFormats : new int[] {f};
		}
	};

	static int @org.jspecify.annotations.Nullable [] @org.jspecify.annotations.Nullable [] prewarm(String name) {
		if (!ShadeConfig.HDR) return null;
		String n = name.toLowerCase(java.util.Locale.ROOT);
		if (n.contains("gui") || n.contains("panorama") || n.contains("lightmap") || n.contains("blit") || n.contains("blur") || n.contains("oit_")
			|| n.contains("animate_sprite") || n.contains("crosshair") || n.contains("distanthorizons") || n.contains("post/")) return null;
		if (ShadeEntity.HAND && (ShadeEntity.PIPELINES.containsKey(name) || n.contains("glint"))) return new int[][] {{ShadeConfig.HDR_FORMAT}, {ShadeEntity.HAND_FORMAT}};
		if (ShadeLean.SKY && SKY.equals(name)) return new int[][] {{ShadeConfig.HDR_FORMAT}, {ShadeConfig.HDR_FORMAT | LEAN_NOAURORA << LEAN_SHIFT}};
		if (ShadeLean.ON && leanTerrain(name)) {
			boolean water = TRANSLUCENT_TERRAIN.equals(name);
			int extra = water ? (ShadeLean.DEV_DEFINES.isEmpty() ? 4 : 8) : 0;
			int[][] sets = new int[12 + extra + (water ? 4 + extra : 0)][];
			for (int t = 0; t < 8; t++) sets[t] = new int[] {ShadeConfig.HDR_FORMAT | t << LEAN_SHIFT};
			for (int t = 0; t < 4; t++) sets[8 + t] = new int[] {ShadeConfig.HDR_FORMAT | (LEAN_NETHER | t << 1) << LEAN_SHIFT};
			for (int t = 0; t < extra; t++) {
				int bits = LEAN_OW | (t & 3) << 1 | LEAN_ABOVE | (t >= 4 ? LEAN_DEV : 0);
				sets[12 + t] = new int[] {ShadeConfig.HDR_FORMAT | bits << LEAN_SHIFT};
			}
			if (water) {
				for (int t = 0; t < 4; t++) sets[12 + extra + t] = new int[] {ShadeConfig.HDR_FORMAT | (LEAN_OW | t << 1 | LEAN_NOFALLS) << LEAN_SHIFT};
				for (int t = 0; t < extra; t++) {
					int bits = LEAN_OW | (t & 3) << 1 | LEAN_ABOVE | (t >= 4 ? LEAN_DEV : 0) | LEAN_NOFALLS;
					sets[16 + extra + t] = new int[] {ShadeConfig.HDR_FORMAT | bits << LEAN_SHIFT};
				}
			}
			return sets;
		}
		return new int[][] {{ShadeConfig.HDR_FORMAT}};
	}

	static void register() {
		skyVertex = skyStage("sky.vs.metal");
		skyFragment = skyStage("sky.fs.metal");
		celestialVertex = skyStage("celestial.vs.metal");
		sunriseVertex = skyStage("sunrise_sunset.vs.metal");
		starsVertex = skyStage("stars.vs.metal");
		cloudFragment = prelude() + resource("clouds.fs.metal");
		endSkyVertex = skyStage("end_sky.vs.metal");
		endSkyFragment = skyStage("end_sky.fs.metal");
		for (String name : TERRAIN) MetalShaderRegistry.register(name, new MetalShaderRegistry.Variant(ShadeMsl::terrain, ShadeMsl::terrain, List.of()));
		List<String> matrices = List.of("DynamicTransforms", "Projection");
		MetalShaderRegistry.register(SKY, new MetalShaderRegistry.Variant(generated -> skyVertex, generated -> skyFragment,
			List.of("DynamicTransforms", "Projection", "Fog")));
		MetalShaderRegistry.register(CELESTIAL, new MetalShaderRegistry.Variant(generated -> celestialVertex, null, matrices));
		MetalShaderRegistry.register(SUNRISE, new MetalShaderRegistry.Variant(generated -> sunriseVertex, null, matrices));
		for (String name : CLOUDS) MetalShaderRegistry.register(name, new MetalShaderRegistry.Variant(null, generated -> cloudFragment, List.of("CloudInfo")));
		MetalShaderRegistry.register(WEATHER, new MetalShaderRegistry.Variant(ShadeWeather.MODULE && ShadeWeather.SLANT ? ShadeMsl::weatherVertex : null,
			ShadeMsl::weather, List.of()));
		if (ShadeWeather.MODULE && ShadeWeather.LIGHTNING) MetalShaderRegistry.register(LIGHTNING, new MetalShaderRegistry.Variant(null, ShadeMsl::lightning, List.of()));
		MetalShaderRegistry.register(STARS, new MetalShaderRegistry.Variant(generated -> starsVertex, null, matrices));
		if (mcopt.metal.shade.dims.DimsConfig.DIMS) {
			MetalShaderRegistry.register(END_SKY, new MetalShaderRegistry.Variant(generated -> endSkyVertex, generated -> endSkyFragment,
				List.of("DynamicTransforms", "Projection", "Sampler0")));
		}
		if (ShadeConfig.HDR) {
			mcopt.metal.shade.dh.ShadeDh.register();
			MetalShaderRegistry.setFormatAdapter(FORMAT_ADAPTER);
			MetalShaderRegistry.setPrewarm(ShadeMsl::prewarm);
		}
	}
}
