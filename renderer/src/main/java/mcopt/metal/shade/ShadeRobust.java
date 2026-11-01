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

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import mcopt.metal.MetalBridge;

public final class ShadeRobust {
	private ShadeRobust() {
	}

	public static boolean failCompile(String what) {
		String v = System.getProperty("mcopt.shade.dev.failCompile");
		if (v == null || v.isBlank()) return false;
		for (String k : v.split(",")) {
			k = k.trim();
			if (!k.isEmpty() && (k.equals("*") || what.contains(k))) return true;
		}
		return false;
	}

	public static void maybeFail(String what) {
		if (failCompile(what)) throw new IllegalStateException("injected shader build failure for " + what + " (-Dmcopt.shade.dev.failCompile)");
	}

	private static volatile boolean skyFailed, postFailed;

	public static void postFailed() {
		if ("false".equals(System.getProperty("mcopt.shade.robustFallback"))) return;
		postFailed = true;
		System.setProperty("mcopt.shade.clouds.pause", "true");
		System.out.println("[mcopt] shade: no HDR post chain: volumetric clouds off, vanilla clouds instead");
	}

	public static void registryBuilt(String name, boolean ok) {
		if (!name.equals("minecraft:pipeline/sky") || "false".equals(System.getProperty("mcopt.shade.robustFallback"))) return;
		if (!ok) {
			skyFailed = true;
			System.setProperty("mcopt.shade.clouds.pause", "true");
			System.out.println("[mcopt] shade: the shaded sky did not build: volumetric clouds off, vanilla clouds instead");
		} else if (skyFailed) {
			skyFailed = false;
			if (!postFailed) System.clearProperty("mcopt.shade.clouds.pause");
			System.out.println("[mcopt] shade: the shaded sky built again" + (postFailed ? " (no HDR post chain: vanilla clouds stay)" : ": volumetric clouds back"));
		}
	}

	public static long deviceAllocatedBytes() {
		Object encoder = MetalBridge.encoder(((FrontendCommandEncoder) RenderSystem.getDevice().createCommandEncoder()).backend());
		return encoder == null ? -1 : MetalBridge.deviceAllocated(MetalBridge.ctx(encoder));
	}
}
