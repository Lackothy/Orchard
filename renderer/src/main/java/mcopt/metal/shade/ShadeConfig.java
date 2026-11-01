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

import java.util.Locale;

public final class ShadeConfig {
	public static final boolean LOD = Boolean.parseBoolean(System.getProperty("mcopt.lod", "false"));
	public static final boolean HDR = Shade.ENABLED && flag("mcopt.shade.hdr", true) && !LOD;
	public static final int HDR_FORMAT = hdrFormat();
	public static final boolean BLOOM = HDR && flag("mcopt.shade.bloom", true);
	public static final boolean AUTO_EXPOSURE = HDR && !"fixed".equalsIgnoreCase(System.getProperty("mcopt.shade.exposure", "auto").trim());
	public static final boolean PCSS = flag("mcopt.shade.pcss", true);
	public static final boolean STARS = flag("mcopt.shade.stars", true);
	public static final boolean HAZE = flag("mcopt.shade.haze", true);
	public static final boolean LIGHT2 = flag("mcopt.shade.light2", true);
	public static final boolean DH_BRIDGE = HDR && flag("mcopt.shade.dhBridge", true);
	public static final float EV = floatFlag("mcopt.shade.ev", 0f, -4f, 4f);
	public static final int LOOK = switch (System.getProperty("mcopt.shade.look", "epic").trim().toLowerCase(Locale.ROOT)) {
		case "storybook" -> 1;
		case "natural" -> 2;
		default -> 0;
	};
	public static final int TIER = Shade.TIER;
	public static final int BLOOM_LEVELS = TIER >= 2 ? 7 : 6;

	private ShadeConfig() {
	}

	private static int hdrFormat() {
		String v = System.getProperty("mcopt.shade.hdrFormat", "auto").trim().toLowerCase(Locale.ROOT);
		if (v.equals("rg11b10f")) return 92;
		if (v.equals("rgba16f")) return 115;
		return Shade.TIER >= 2 ? 115 : 92;
	}

	static boolean flag(String key, boolean fallback) {
		String v = System.getProperty(key);
		return v == null ? fallback : Boolean.parseBoolean(v.trim());
	}

	static float floatFlag(String key, float fallback, float min, float max) {
		try {
			String v = System.getProperty(key);
			return v == null ? fallback : Math.max(min, Math.min(max, Float.parseFloat(v.trim())));
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	static String describe() {
		return "hdr " + (HDR ? (HDR_FORMAT == 92 ? "rg11b10f" : "rgba16f") : "off") + ", bloom " + BLOOM + ", exposure " + (AUTO_EXPOSURE ? "auto" : "fixed")
			+ ", pcss " + PCSS + ", sky " + STARS + ", haze " + HAZE + ", light v2 " + LIGHT2 + ", look " + new String[] {"epic", "storybook", "natural"}[LOOK]
			+ ", preset " + new String[] {"performance", "balanced", "quality", "ultra"}[TIER] + (LOD ? " (mcopt.lod on: no HDR)" : "");
	}
}
