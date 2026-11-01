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

import java.nio.ByteBuffer;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

public final class ShadeRender {
	public static final int RGBA8 = 70, RGBA16F = 115, RG11B10F = 92, R16F = 25, RG16F = 65, R8 = 10, R32F = 55;
	public static final int DONT_CARE = 0, LOAD = 1, CLEAR = 2;
	public static final int BLEND_NONE = 0, BLEND_ADD = 1, BLEND_ALPHA = 2, BLEND_PREMULTIPLIED = 3;

	private ShadeRender() {
	}

	public static String prelude() {
		return ShadeMsl.prelude();
	}

	public static String postSource() {
		return ShadeMsl.resource("shade_post.metal");
	}

	public static boolean ready() {
		return ShadeBindings.ready();
	}

	public static long library(String source) {
		if (System.getProperty("mcopt.shade.dev.failCompile") != null) {
			String caller = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(s -> s.skip(1).findFirst())
				.map(f -> f.getDeclaringClass().getSimpleName()).orElse("?");
			ShadeRobust.maybeFail("library:" + caller);
		}
		long ctx = ShadeBindings.ctx();
		ByteBuffer src = MemoryUtil.memUTF8(source);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long err = stack.nmalloc(1, 4096);
			MemoryUtil.memPutByte(err, (byte) 0);
			long lib = ShadeNative.library(ctx, MemoryUtil.memAddress(src), err, 4096);
			if (lib == 0) throw new IllegalStateException("shade MSL: " + MemoryUtil.memUTF8(err));
			return lib;
		} finally {
			MemoryUtil.memFree(src);
		}
	}

	public static long pipeline(long library, String vertex, String fragment, int format, int blend) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long err = stack.nmalloc(1, 4096);
			MemoryUtil.memPutByte(err, (byte) 0);
			long pso = ShadeNative.pso(ShadeBindings.ctx(), library, MemoryUtil.memAddress(stack.UTF8(vertex)), MemoryUtil.memAddress(stack.UTF8(fragment)),
				format, blend, err, 4096);
			if (pso == 0) throw new IllegalStateException("shade pipeline " + fragment + ": " + MemoryUtil.memUTF8(err));
			return pso;
		}
	}

	public static long computePipeline(long library, String kernel) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long err = stack.nmalloc(1, 4096);
			MemoryUtil.memPutByte(err, (byte) 0);
			long pso = ShadeNative.cpso(ShadeBindings.ctx(), library, MemoryUtil.memAddress(stack.UTF8(kernel)), err, 4096);
			if (pso == 0) throw new IllegalStateException("shade kernel " + kernel + ": " + MemoryUtil.memUTF8(err));
			return pso;
		}
	}

	public static long texture(int format, int width, int height, boolean lossy, String label) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long t = ShadeNative.texture(ShadeBindings.ctx(), format, Math.max(1, width), Math.max(1, height), lossy ? 1 : 0, MemoryUtil.memAddress(stack.UTF8(label)));
			if (t == 0) throw new IllegalStateException("shade texture " + label + " " + width + "x" + height);
			return t;
		}
	}

	public static int width(long texture) {
		return ShadeNative.textureWidth(texture);
	}

	public static int height(long texture) {
		return ShadeNative.textureHeight(texture);
	}

	public static void pass(long enc, long pipeline, long target, int load, long @Nullable [] textures, @Nullable ByteBuffer params, String label) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			int n = textures == null ? 0 : textures.length;
			long t = stack.nmalloc(8, Math.max(1, n) * 8);
			for (int i = 0; i < n; i++) MemoryUtil.memPutAddress(t + i * 8L, textures[i]);
			ShadeNative.pass(enc, pipeline, target, load, t, n, params == null ? 0 : MemoryUtil.memAddress(params), params == null ? 0 : params.remaining(),
				MemoryUtil.memAddress(stack.UTF8(label)));
		}
	}

	public static void dispatch(long enc, long pipeline, int width, int height, int tw, int th, long @Nullable [] textures, @Nullable ByteBuffer params,
		String label) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			int n = textures == null ? 0 : textures.length;
			long t = stack.nmalloc(8, Math.max(1, n) * 8);
			for (int i = 0; i < n; i++) MemoryUtil.memPutAddress(t + i * 8L, textures[i]);
			ShadeNative.dispatch(enc, pipeline, width, height, tw, th, t, n, params == null ? 0 : MemoryUtil.memAddress(params),
				params == null ? 0 : params.remaining(), MemoryUtil.memAddress(stack.UTF8(label)));
		}
	}

	public static void resource(int index, long texture) {
		ShadeNative.resource(index, texture);
	}
}
