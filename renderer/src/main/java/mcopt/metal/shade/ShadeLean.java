/*
 * Copyright 2026 mcopt-shade contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
 * law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package mcopt.metal.shade;

public final class ShadeLean {
	public static final boolean ON = ShadeConfig.flag("mcopt.shade.lean", true);
	static boolean above() {
		return ON && !"false".equals(System.getProperty("mcopt.shade.leanAbove"));
	}
	public static final boolean SKY = ShadeConfig.flag("mcopt.shade.leanSky", true);
	public static final String DEV_DEFINES = devDefines(System.getProperty("mcopt.shade.leanDev", ""));
	private static volatile int tag;

	private static String devDefines(String v) {
		StringBuilder b = new StringBuilder();
		for (String d : v.split(",")) {
			String t = d.trim();
			if (t.matches("[A-Z][A-Z0-9_]*")) b.append("#define ").append(t).append(" 1\n");
		}
		return b.toString();
	}

	private ShadeLean() {
	}

	public static int tag() {
		return (ON ? tag & (ShadeMsl.LEAN_TERRAIN | ShadeMsl.LEAN_WATER) : 0) | (SKY ? tag & ShadeMsl.LEAN_NOAURORA : 0);
	}

	static void update(int dimension, boolean underwater) {
		int t = 0;
		if (dimension == ShadeFrame.DIM_OVERWORLD) t |= ShadeMsl.LEAN_OW;
		if (dimension == ShadeFrame.DIM_OVERWORLD && !underwater && above()) {
			t |= ShadeMsl.LEAN_ABOVE;
			if (!DEV_DEFINES.isEmpty() && Boolean.getBoolean("mcopt.shade.leanDev.on")) t |= ShadeMsl.LEAN_DEV;
		}
		if (dimension == ShadeFrame.DIM_OVERWORLD && (!mcopt.metal.shade.water.WaterFlowScan.near()
			|| mcopt.metal.shade.vl.VlConfig.live("mcopt.shade.water.falls", 1f) <= 0f)) t |= ShadeMsl.LEAN_NOFALLS;
		if (dimension == ShadeFrame.DIM_NETHER && mcopt.metal.shade.dims.ShadeDims.netherLean()) t |= ShadeMsl.LEAN_NETHER;
		if (!mcopt.metal.shade.weather.ShadeWeather.ON || mcopt.metal.shade.weather.ShadeWeather.dry()) t |= ShadeMsl.LEAN_DRY;
		if (!mcopt.metal.shade.light.DynLights.ON || mcopt.metal.shade.light.DynLights.activeCount() == 0) t |= ShadeMsl.LEAN_NODL;
		if (mcopt.metal.shade.dims.DimsConfig.aurora() == 0 || mcopt.metal.shade.dims.ShadeDims.aurora() <= 0.002f
			|| mcopt.metal.shade.dims.ShadeAurora.active()) t |= ShadeMsl.LEAN_NOAURORA;
		tag = t;
	}
}
