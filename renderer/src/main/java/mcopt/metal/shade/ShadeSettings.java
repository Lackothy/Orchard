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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ShadeSettings {
	public static final String PRESET = "mcopt.shade.preset", LOOK = "mcopt.shade.look", EV = "mcopt.shade.ev",
		SATURATION = "mcopt.shade.user.saturation", BLOOM = "mcopt.shade.user.bloom", GODRAYS = "mcopt.shade.user.godrays",
		HAZE = "mcopt.shade.user.haze", CLOUDS = "mcopt.shade.clouds.mode", AURORA = "mcopt.shade.aurora",
		SSR = "mcopt.shade.user.ssr", WATER_COLOR = "mcopt.shade.water.color", WATER_CLARITY = "mcopt.shade.water.clarity", WAVES = "mcopt.shade.waterWaves",
		REFLECT = "mcopt.shade.waterReflect";

	public static final String[] PRESETS = {"performance", "balanced", "quality", "ultra"};
	public static final String[] LOOKS = {"epic", "storybook", "natural"};
	public static final int CLOUDS_VOLUMETRIC = 0, CLOUDS_BLOCKY = 1, CLOUDS_VANILLA = 2, CLOUDS_OFF = 3;
	public static final String[] CLOUD_MODES = {"volumetric", "blocky", "vanilla", "off"};
	public static final String[] AURORAS = {"off", "rare", "auto", "always"};
	public static final String[] WATER_COLORS = {"natural", "blue", "turquoise", "green", "clear"};
	private static final int[] WATER_RGB = {0, 0x3F76E4, 0x43D5EE, 0x4C9E7A, 0x8CE6F5};

	private static volatile int look = indexOf(LOOKS, System.getProperty(LOOK, "epic"), 0);
	private static volatile float ev = clamp(floatProp(EV, 0f), -2f, 2f);
	private static volatile int saturation = percent(SATURATION, 100, 0, 150), bloom = percent(BLOOM, 100, 0, 200),
		godrays = percent(GODRAYS, 100, 0, 200), haze = percent(HAZE, 100, 0, 200), clarity = percent(WATER_CLARITY, 100, 25, 300);
	private static volatile int clouds = indexOf(CLOUD_MODES, System.getProperty(CLOUDS, ""), defaultCloudMode());
	private static volatile int aurora = indexOf(AURORAS, System.getProperty(AURORA, "auto"), 2);
	private static volatile int waterColor = indexOf(WATER_COLORS, System.getProperty(WATER_COLOR, "natural"), 0);
	private static volatile float waves = clamp(floatProp(WAVES, 1f), 0f, 2f), reflect = clamp(floatProp(REFLECT, 1f), 0f, 2f);
	private static volatile boolean ssr = !"false".equalsIgnoreCase(System.getProperty(SSR, "true").trim());

	private static final Map<String, String> CHANGED = new LinkedHashMap<>();

	private ShadeSettings() {
	}

	public static void sync() {
		look = indexOf(LOOKS, System.getProperty(LOOK, "epic"), 0);
		ev = clamp(floatProp(EV, 0f), -2f, 2f);
		saturation = percent(SATURATION, 100, 0, 150);
		bloom = percent(BLOOM, 100, 0, 200);
		godrays = percent(GODRAYS, 100, 0, 200);
		haze = percent(HAZE, 100, 0, 200);
		clarity = percent(WATER_CLARITY, 100, 25, 300);
		clouds = indexOf(CLOUD_MODES, System.getProperty(CLOUDS, ""), defaultCloudMode());
		aurora = indexOf(AURORAS, System.getProperty(AURORA, "auto"), 2);
		waterColor = indexOf(WATER_COLORS, System.getProperty(WATER_COLOR, "natural"), 0);
		waves = clamp(floatProp(WAVES, 1f), 0f, 2f);
		reflect = clamp(floatProp(REFLECT, 1f), 0f, 2f);
		ssr = !"false".equalsIgnoreCase(System.getProperty(SSR, "true").trim());
	}

	public static int look() {
		return look;
	}

	public static float ev() {
		return ev;
	}

	public static float saturation() {
		return saturation / 100f;
	}

	public static float bloom() {
		return bloom / 100f;
	}

	public static float godrays() {
		return godrays / 100f;
	}

	public static float haze() {
		return haze / 100f;
	}

	public static int clouds() {
		return clouds;
	}

	public static int aurora() {
		return switch (aurora) {
			case 0 -> 0;
			case 1 -> 3;
			case 3 -> 2;
			default -> 1;
		};
	}

	public static void waterTint(float[] out) {
		int c = WATER_RGB[waterColor];
		out[0] = toLinear(c >> 16 & 0xFF);
		out[1] = toLinear(c >> 8 & 0xFF);
		out[2] = toLinear(c & 0xFF);
		out[3] = waterColor == 0 ? 0f : 1f;
	}

	public static float clarity() {
		return clarity / 100f;
	}

	public static float waves() {
		return waves;
	}

	public static float reflect() {
		return reflect;
	}

	public static boolean ssr() {
		return ssr;
	}

	static synchronized void changed(String key, String value) {
		CHANGED.put(key, value);
	}

	public static synchronized void save(Path gameDir) {
		if (CHANGED.isEmpty()) return;
		Path file = gameDir.resolve("config").resolve("mcopt.properties");
		try {
			List<String> lines = Files.exists(file) ? new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8)) : new ArrayList<>();
			boolean header = false;
			for (Map.Entry<String, String> e : CHANGED.entrySet()) {
				boolean found = false;
				for (int i = lines.size() - 1; i >= 0; i--) {
					String t = lines.get(i).trim();
					if (t.startsWith(e.getKey() + "=") || t.startsWith(e.getKey() + " ")) {
						if (e.getValue() == null) lines.remove(i);
						else lines.set(i, e.getKey() + "=" + e.getValue());
						found = true;
					}
				}
				if (!found && e.getValue() != null) {
					if (!header && lines.stream().noneMatch(l -> l.startsWith("# Ajustes de shaders"))) {
						lines.add("# Ajustes de shaders (también desde el menú de pausa: Shaders...)");
						header = true;
					}
					lines.add(e.getKey() + "=" + e.getValue());
				}
			}
			Files.createDirectories(file.getParent());
			Files.write(file, lines, StandardCharsets.UTF_8);
			System.out.println("[mcopt] shade: settings saved: " + CHANGED);
			CHANGED.clear();
		} catch (IOException e) {
			System.out.println("[mcopt] shade: could not save the shader settings: " + e);
		}
	}

	static int defaultCloudMode() {
		String style = System.getProperty("mcopt.shade.clouds.style");
		if (style != null) return style.trim().equalsIgnoreCase("blocky") ? CLOUDS_BLOCKY : CLOUDS_VOLUMETRIC;
		return "storybook".equalsIgnoreCase(System.getProperty(LOOK, "").trim()) ? CLOUDS_BLOCKY : CLOUDS_VOLUMETRIC;
	}

	private static int indexOf(String[] names, String v, int fallback) {
		String s = v.trim().toLowerCase(Locale.ROOT);
		for (int i = 0; i < names.length; i++) if (names[i].equals(s)) return i;
		if (names == AURORAS) {
			if (s.equals("false")) return 0;
			if (s.equals("on") || s.equals("true")) return 3;
		}
		return fallback;
	}

	private static float floatProp(String key, float fallback) {
		try {
			String v = System.getProperty(key);
			return v == null ? fallback : Float.parseFloat(v.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static int percent(String key, int fallback, int min, int max) {
		return Math.round(clamp(floatProp(key, fallback), min, max));
	}

	private static float clamp(float v, float min, float max) {
		return Math.max(min, Math.min(max, v));
	}

	private static float toLinear(int c8) {
		double c = c8 / 255.0;
		return (float) (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
	}
}
