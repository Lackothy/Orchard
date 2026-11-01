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
package mcopt.metal.shade.clouds;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.invoke.MethodHandle;
import mcopt.metal.MetalBridge;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

final class CloudNative {
	private static final Linker LINKER = Linker.nativeLinker();
	private static final MethodHandle NEW = fn("mc_clouds_new", JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle SIZE = fn("mc_clouds_size", JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
	private static final MethodHandle RENDER = fn("mc_clouds_render", null, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT);
	private static final MethodHandle OUTPUTS = fn("mc_clouds_outputs", null, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle TIMES = fn("mc_clouds_times", null, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle FREE = fn("mc_clouds_free", null, JAVA_LONG);
	private static final MethodHandle VANILLA = fn("mc_clouds_vanilla", JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT);
	private static final MethodHandle OVER = fn("mc_clouds_over", null, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT);
	private static final MethodHandle WARM = fn("mc_clouds_warm", JAVA_INT, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle LEAN = fn("mc_clouds_lean", JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_FLOAT);
	private static final MethodHandle WARM_LEAN = fn("mc_clouds_warm_lean", JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_FLOAT);

	private CloudNative() {
	}

	private static MethodHandle fn(String name, MemoryLayout ret, MemoryLayout... args) {
		var symbol = MetalBridge.library().find(name).orElseThrow(() -> new IllegalStateException("libmcmetal has no " + name));
		return LINKER.downcallHandle(symbol, ret == null ? FunctionDescriptor.ofVoid(args) : FunctionDescriptor.of(ret, args));
	}

	private static RuntimeException rethrow(Throwable t) {
		if (t instanceof RuntimeException r) return r;
		if (t instanceof Error e) throw e;
		return new IllegalStateException(t);
	}

	static int warm(long ctx, long lib) {
		try {
			return (int) WARM.invokeExact(ctx, lib);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static int lean(long state, long ctx, int dev, int mode, float style) {
		try {
			return (int) LEAN.invokeExact(state, ctx, dev, mode, style);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static int warmLean(long ctx, long lib, int dev, int mode, float style) {
		try {
			return (int) WARM_LEAN.invokeExact(ctx, lib, dev, mode, style);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static long create(long ctx, long source, long err, int errCap) {
		try {
			return (long) NEW.invokeExact(ctx, source, err, errCap);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static int size(long state, long ctx, int hw, int hh, int mw, int mh, int dw, int dh) {
		try {
			return (int) SIZE.invokeExact(state, ctx, hw, hh, mw, mh, dw, dh);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static int vanilla(long state, long ctx, long mask, int w, int h) {
		try {
			return (int) VANILLA.invokeExact(state, ctx, mask, w, h);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void render(long enc, long state, long params, int bytes, int flags) {
		try {
			RENDER.invokeExact(enc, state, params, bytes, flags);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void outputs(long state, long out) {
		try {
			OUTPUTS.invokeExact(state, out);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void times(long state, long out) {
		try {
			TIMES.invokeExact(state, out);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void over(long enc, long state, long scene, long depth, int liveW, int liveH, int mode) {
		try {
			OVER.invokeExact(enc, state, scene, depth, liveW, liveH, mode);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void free(long state) {
		try {
			FREE.invokeExact(state);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}
}
