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
package mcopt.metal.shade.dims;

import java.util.Locale;
import mcopt.metal.shade.Live;
import mcopt.metal.shade.Shade;
import mcopt.metal.shade.ShadeSettings;
import mcopt.metal.shade.ShadeConfig;

public final class DimsConfig {
	public static final boolean MODULE = Shade.ENABLED && flag("mcopt.shade.skydims", true);
	public static final boolean DIMS = MODULE && ShadeConfig.HDR && flag("mcopt.shade.dims", true);
	public static final int AURORA = MODULE ? switch (System.getProperty("mcopt.shade.aurora", "auto").trim().toLowerCase(Locale.ROOT)) {
		case "off", "false" -> 0;
		case "always", "on", "true" -> 2;
		case "rare" -> 3;
		default -> 1;
	} : 0;
	public static int aurora() {
		return MODULE ? ShadeSettings.aurora() : 0;
	}
	public static final boolean MEDIA = MODULE && ShadeConfig.HDR && flag("mcopt.shade.media", true);
	public static final boolean END_SHADOWS = DIMS && flag("mcopt.shade.endShadows", true);
	public static final boolean NETHER_SHIMMER = DIMS && flag("mcopt.shade.netherShimmer", Shade.TIER >= 2);
	public static final int XDR = MODULE && ShadeConfig.HDR ? switch (System.getProperty("mcopt.shade.xdr", "auto").trim().toLowerCase(Locale.ROOT)) {
		case "off", "false" -> 0;
		case "on", "true" -> 2;
		default -> 1;
	} : 0;
	public static final float XDR_PEAK = floatFlag("mcopt.shade.xdrPeak", 4f, 1f, 16f);

	public static boolean media() {
		return (DIMS || MEDIA) && Live.b("mcopt.shade.media", true);
	}

	public static boolean endShadows() {
		return DIMS && Live.b("mcopt.shade.endShadows", true);
	}

	public static boolean netherShimmer() {
		return DIMS && Live.b("mcopt.shade.netherShimmer", Shade.TIER >= 2);
	}

	static float user(String key) {
		return Live.f(key, 1f, 0f, 3f);
	}

	private DimsConfig() {
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

	public static String describe() {
		return (MODULE ? "" : "module off (mcopt.shade.skydims=false): ") + "dims " + DIMS + ", aurora " + new String[] {"off", "auto", "always", "rare"}[AURORA] + ", media " + MEDIA + ", end shadows " + END_SHADOWS
			+ ", nether shimmer " + NETHER_SHIMMER + ", xdr " + new String[] {"off", "auto", "on"}[XDR] + " (peak cap " + XDR_PEAK + ")";
	}
}
