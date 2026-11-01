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

import java.util.ArrayList;
import java.util.List;
import mcopt.metal.MetalBridge;
import mcopt.metal.shade.ShadeConfig;
import mcopt.metal.shade.ShadeMsl;
import mcopt.metal.shade.ShadeRender;
import net.minecraft.client.Minecraft;

public final class ShadeAurora {
	static final int RESOURCE = 11;
	static final float SCALE = Math.max(0.1f, Math.min(1f, Float.parseFloat(System.getProperty("mcopt.shade.aurora.scale", "0.5"))
		* (mcopt.metal.shade.taa.TaaConfig.ENABLED ? mcopt.metal.shade.taa.TaaConfig.SCALE : 1f)));

	static float scale() {
		float user = mcopt.metal.shade.Live.f("mcopt.shade.aurora.scale", Float.NaN);
		return Float.isNaN(user) ? SCALE
			: Math.max(0.1f, Math.min(1f, user * (mcopt.metal.shade.taa.TaaConfig.ENABLED ? mcopt.metal.shade.taa.TaaConfig.SCALE : 1f)));
	}

	private static long library, pipeline, texture;
	private static int width, height;
	private static boolean failed, want;
	private static long frame;
	private static final List<long[]> retired = new ArrayList<>();

	private ShadeAurora() {
	}

	public static String source() {
		return ShadeRender.prelude() + "\n" + ShadeRender.postSource() + "\n" + ShadeMsl.resource("aurora.metal");
	}

	static boolean enabled() {
		return !"false".equals(System.getProperty("mcopt.shade.aurora.half"));
	}

	static void decide(float aurora) {
		want = aurora > 0.002f && !failed && enabled() && ShadeConfig.HDR && !mcopt.metal.shade.ShadeBindings.fallback();
	}

	static void off() {
		want = false;
	}

	public static boolean active() {
		return want;
	}

	public static void render(long enc) {
		frame++;
		retire(false);
		if (!want) return;
		try {
			if (pipeline == 0) {
				long t0 = mcopt.metal.shade.ShadeCompile.now();
				library = ShadeRender.library(source());
				pipeline = ShadeRender.pipeline(library, "shade_fs_vs", "shade_aurora_pass", ShadeRender.RGBA16F, ShadeRender.BLEND_NONE);
				mcopt.metal.shade.ShadeCompile.setup("aurora", t0);
			}
			var window = Minecraft.getInstance().getWindow();
			int[] outSize = mcopt.metal.shade.ShadeOutput.current(window.getWidth(), window.getHeight());
			float scale = scale();
			int w = Math.max(16, Math.round(outSize[0] * scale)), h = Math.max(16, Math.round(outSize[1] * scale));
			if (texture == 0 || w != width || h != height) {
				if (texture != 0) retired.add(new long[] {texture, frame + 8});
				texture = ShadeRender.texture(ShadeRender.RGBA16F, w, h, false, "mcopt shade: aurora");
				width = w;
				height = h;
			}
			ShadeRender.pass(enc, pipeline, texture, ShadeRender.DONT_CARE, null, null, "mcopt shade: aurora");
			ShadeRender.resource(RESOURCE, texture);
		} catch (Throwable t) {
			failed = true;
			want = false;
			System.out.println("[mcopt] shade: aurora pass off (the sky pass evaluates it per pixel): " + t);
		}
	}

	private static void retire(boolean force) {
		retired.removeIf(r -> {
			if (!force && r[1] > frame) return false;
			MetalBridge.release(r[0]);
			return true;
		});
	}
}
