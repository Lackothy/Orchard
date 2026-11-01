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

import java.util.Locale;
import mcopt.metal.shade.ShadeMsl;

public final class DynLightMsl {
	public static final boolean ON = DynLightConfig.ENABLED;
	private static final String CALL = "sample_lightmap(Sampler2, Sampler2Smplr, ";
	private static final String VERTEX_ENTRY = "vertex main0_out main0(";
	private static volatile String prelude, module;

	private DynLightMsl() {
	}

	public static String prelude() {
		if (!ON) return "";
		if (prelude == null) prelude = "#define SHADE_DYNLIGHT\n" + module() + "\n";
		return prelude;
	}

	static String module() {
		if (module == null) module = ShadeMsl.resource("dynlight.metal");
		return module;
	}

	static boolean wants(String pipelineName) {
		String n = pipelineName.toLowerCase(Locale.ROOT);
		return !(n.contains("terrain") || n.contains("oit_") || n.contains("weather") || n.contains("wireframe") || n.contains("gui") || n.contains("lightning"));
	}

	public static String patchVertex(String pipelineName, String msl) {
		if (!ON || !DynLightConfig.ENTITIES || !wants(pipelineName)) return msl;
		return patch(msl);
	}

	public static String patch(String msl) {
		int sig = msl.indexOf(VERTEX_ENTRY);
		if (sig < 0 || msl.indexOf(CALL, sig) < 0 || !msl.contains("in.Position") || msl.contains("mcoptDynLights")) return msl;
		int close = closeParen(msl, sig + VERTEX_ENTRY.length() - 1);
		if (close < 0) return msl;
		String normal = msl.substring(0, sig).matches("(?s).*\\bfloat3 Normal \\[\\[attribute\\(\\d+\\)\\]\\];.*") ? "in.Normal" : "float3(0.0)";
		StringBuilder body = new StringBuilder();
		int from = close, at;
		while ((at = msl.indexOf(CALL, from)) >= 0) {
			int end = closeParen(msl, at + "sample_lightmap".length());
			if (end < 0) return msl;
			String uv = msl.substring(at + CALL.length(), end).trim();
			if (!uv.matches("[A-Za-z_][A-Za-z0-9_.]*")) return msl;
			body.append(msl, from, at);
			body.append("shadeDynLightmap(").append(msl, at, end + 1).append(", ").append(uv).append(", in.Position, ").append(normal)
				.append(", Sampler2, Sampler2Smplr, mcoptDynLights, mcoptDynVis)");
			from = end + 1;
		}
		body.append(msl, from, msl.length());
		return module() + "\n" + msl.substring(0, close) + ", constant ShadeDynLights& mcoptDynLights [[buffer(24)]], texture3d<half> mcoptDynVis [[texture(23)]]"
			+ body;
	}

	private static int closeParen(String s, int open) {
		int depth = 0;
		for (int i = open; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '(') depth++;
			else if (c == ')' && --depth == 0) return i;
		}
		return -1;
	}
}
