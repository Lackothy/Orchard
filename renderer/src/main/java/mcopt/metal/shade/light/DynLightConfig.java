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
package mcopt.metal.shade.light;

import mcopt.metal.shade.Live;
import mcopt.metal.shade.Shade;

public final class DynLightConfig {
	public static final boolean ENABLED = Shade.ENABLED && flag("mcopt.shade.dynLights", true);
	public static final int MAX = clamp(Integer.getInteger("mcopt.shade.dynLights.max", 64), 1, 64);
	public static final float RANGE = floatFlag("mcopt.shade.dynLights.range", 48f, 8f, 60f);
	public static final boolean OCCLUSION = flag("mcopt.shade.dynLights.occlusion", true);
	public static final int VOLUMES = OCCLUSION ? clamp(Integer.getInteger("mcopt.shade.dynLights.volumes", new int[] {8, 16, 24, 32}[Shade.TIER]), 1, 64) : 0;
	public static final float VOLUME_RANGE = 40f;
	public static final int OCC_BUDGET_US = clamp(Integer.getInteger("mcopt.shade.dynLights.occBudgetUs", 300), 20, 20000);
	public static final float STRENGTH = floatFlag("mcopt.shade.dynLights.strength", 1f, 0f, 4f);
	public static final float SPEC = floatFlag("mcopt.shade.dynLights.spec", 1f, 0f, 4f);
	public static final float ENTITY = floatFlag("mcopt.shade.dynLights.entity", 1f, 0f, 4f);
	public static final boolean ENTITIES = flag("mcopt.shade.dynLights.entities", true);
	public static final boolean HAND = flag("mcopt.shade.dynLights.hand", true);
	public static final boolean OTHERS = flag("mcopt.shade.dynLights.others", true);
	public static final boolean FLICKER = flag("mcopt.shade.dynLights.flicker", true);
	public static final boolean LOG = flag("mcopt.shade.dynLights.log", false);

	private DynLightConfig() {
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

	private static int clamp(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}

	static int max() {
		return clamp(Live.i("mcopt.shade.dynLights.max", MAX), 1, 64);
	}

	static float range() {
		return Live.f("mcopt.shade.dynLights.range", RANGE, 8f, 60f);
	}

	static float strength() {
		return Live.f("mcopt.shade.dynLights.strength", STRENGTH, 0f, 4f);
	}

	static float spec() {
		return Live.f("mcopt.shade.dynLights.spec", SPEC, 0f, 4f);
	}

	static float entity() {
		return Live.f("mcopt.shade.dynLights.entity", ENTITY, 0f, 4f);
	}

	static boolean hand() {
		return Live.b("mcopt.shade.dynLights.hand", HAND);
	}

	static boolean others() {
		return Live.b("mcopt.shade.dynLights.others", OTHERS);
	}

	static boolean flicker() {
		return Live.b("mcopt.shade.dynLights.flicker", FLICKER);
	}

	static String describe() {
		return "max " + MAX + ", range " + RANGE + ", volumes " + VOLUMES + ", entities " + ENTITIES + ", hand " + HAND + ", others " + OTHERS
			+ (STRENGTH != 1f ? ", strength " + STRENGTH : "");
	}

	public static String source() {
		return "#define SHADE_DL_KERNELS\n" + mcopt.metal.shade.ShadeMsl.resource("dynlight.metal") + "\n" + mcopt.metal.shade.ShadeMsl.resource("dynlight_vis.metal");
	}
}
