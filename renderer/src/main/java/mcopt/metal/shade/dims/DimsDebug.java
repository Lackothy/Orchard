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

import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import org.jspecify.annotations.Nullable;

public final class DimsDebug {
	private static String lastTag = "";
	private static boolean logging;
	private static int passes;

	private DimsDebug() {
	}

	public static void beginFrame() {
		String t = System.getProperty("mcopt.shade.passLog", "");
		logging = !t.isEmpty() && !t.equals(lastTag);
		if (!logging) return;
		lastTag = t;
		passes = 0;
		System.out.println("[mcopt] passLog " + t + ": world phase starts");
	}

	public static boolean logging() {
		return logging;
	}

	public static void pass(RenderPassDescriptor d, @Nullable GpuTexture main) {
		if (!logging) return;
		StringBuilder s = new StringBuilder("[mcopt] passLog   #").append(passes++).append(" '").append(d.label().get()).append("' color:");
		for (var a : d.colorAttachments()) {
			if (a == null) {
				s.append(" -");
				continue;
			}
			GpuTexture t = a.textureView().texture();
			s.append(' ').append(t == main ? "MAIN" : t.getLabel()).append('[').append(t.getFormat()).append(' ').append(t.getWidth(0)).append('x')
				.append(t.getHeight(0)).append(" mip").append(a.textureView().baseMipLevel()).append(']');
			if (a.clearValue().isPresent()) s.append(" clear=").append(a.clearValue().get());
		}
		var dep = d.depthAttachment();
		if (dep != null) {
			s.append(" depth: ").append(dep.textureView().texture().getLabel());
			if (dep.clearValue().isPresent()) s.append(" clear=").append(dep.clearValue().getAsDouble());
		}
		System.out.println(s);
	}

	public static void note(String what) {
		if (logging) System.out.println("[mcopt] passLog   " + what);
	}

	public static void endFrame() {
		if (!logging) return;
		System.out.println("[mcopt] passLog   world phase ends (" + passes + " passes)");
		logging = false;
	}

	public static boolean dhClear() {
		return !"false".equals(System.getProperty("mcopt.shade.dhClear"));
	}

	public static boolean noBridge() {
		return (Integer.getInteger("mcopt.shade.dhDebug", 0) & 1) != 0;
	}
}
