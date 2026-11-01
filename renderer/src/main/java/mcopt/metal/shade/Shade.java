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

public final class Shade {
	public static final boolean ENABLED = Boolean.getBoolean("mcopt.shade") && Boolean.parseBoolean(System.getProperty("mcopt.metal", "true"));
	public static final boolean SHADOWS = ENABLED && Boolean.parseBoolean(System.getProperty("mcopt.shade.shadows", "true"));
	public static final int DEBUG = Integer.getInteger("mcopt.shade.debug", 0);
	public static final String PRESET_DEFAULT = "balanced";
	public static final int TIER = tier(System.getProperty("mcopt.shade.preset", PRESET_DEFAULT));
	public static final String PRESET = new String[] {"performance", "balanced", "quality", "ultra"}[TIER];

	private static int tier(String name) {
		return switch (name.trim().toLowerCase(java.util.Locale.ROOT)) {
			case "performance", "p" -> 0;
			case "balanced", "b" -> 1;
			case "quality", "q" -> 2;
			case "ultra", "u" -> 3;
			default -> {
				System.out.println("[mcopt] shade: unknown mcopt.shade.preset '" + name + "', using " + PRESET_DEFAULT);
				yield tier(PRESET_DEFAULT);
			}
		};
	}
	public static final float SUN_TILT = ShadeSky.SUN_TILT;

	public static void celestial(double angle, double[] out) {
		ShadeSky.celestial(angle, out);
	}

	private static volatile boolean faceShadeOff = ENABLED;
	private static boolean initialized;

	private Shade() {
	}

	public static synchronized void init() {
		if (initialized || !ENABLED) return;
		initialized = true;
		ShadeMsl.register();
		if (System.getProperty("mcopt.metal.presentQueue") == null) System.setProperty("mcopt.metal.presentQueue", "acquire");
		if (System.getProperty("mcopt.metal.paceSync") == null) System.setProperty("mcopt.metal.paceSync", "true");
		if (System.getProperty("mcopt.metal.paceFollow") == null) System.setProperty("mcopt.metal.paceFollow", "true");
		System.out.println("[mcopt] shade: native shading on (shadows " + SHADOWS + ", " + mcopt.metal.shade.shadow.ShadowConfig.RES + " px, "
			+ mcopt.metal.shade.shadow.ShadowConfig.DIST + " blocks"
			+ (DEBUG != 0 ? ", debug view " + DEBUG : "") + ")");
	}

	public static boolean faceShadeOff() {
		return faceShadeOff;
	}

	public static void setFaceShadeOff(boolean off) {
		if (faceShadeOff == off) return;
		faceShadeOff = off;
		ShadeFrameUpdater.rebuildChunks();
	}

	public static ShadeFrame frame() {
		return ShadeFrameUpdater.FRAME;
	}
}
