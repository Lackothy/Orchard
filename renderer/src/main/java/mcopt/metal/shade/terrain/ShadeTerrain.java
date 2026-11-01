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
package mcopt.metal.shade.terrain;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import mcopt.metal.MetalBridge;
import mcopt.metal.shade.Shade;
import mcopt.metal.shade.ShadeConfig;
import mcopt.metal.shade.ShadeLean;
import mcopt.metal.shade.ShadeMsl;
import mcopt.metal.shade.ShadeRender;
import mcopt.metal.shade.taa.ShadeTaa;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

public final class ShadeTerrain {
	public static final boolean ON = Shade.ENABLED;
	public static final boolean LIT = true;
	private static final int TRANSLUCENT = 1 << 16;
	private static final Map<Integer, CompletableFuture<Long>> LIBRARIES = new ConcurrentHashMap<>();
	private static final ExecutorService COMPILER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "mcopt-shade terrain compile");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		return t;
	});
	private static long own;
	private static MethodHandle shadeFs, shadeBuilt, shadePrewarm;
	private static final boolean PREWARM_PIPELINES = !"false".equals(System.getProperty("mcopt.shade.terrain.prewarmPipelines"));
	private static long frames;
	private static double builtSeen;
	private static boolean said, failedSaid, prewarmed;

	private ShadeTerrain() {
	}

	public static long ownHandle() {
		return own;
	}

	public static String prefix() {
		return ShadeMsl.ownPrefix();
	}

	public static void created(long own) {
		try {
			Linker linker = Linker.nativeLinker();
			MethodHandle shade = linker.downcallHandle(native_("mco_shade"), FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_INT));
			shade.invokeExact(own, 1);
			shadeFs = linker.downcallHandle(native_("mco_shade_fs"), FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT));
			shadeBuilt = linker.downcallHandle(native_("mco_shade_built"), FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG));
			shadePrewarm = linker.downcallHandle(native_("mco_shade_prewarm"), FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT));
		} catch (Throwable t) {
			throw new IllegalStateException("mcopt-shade: own terrain's shade slots", t);
		}
		ShadeTerrain.own = own;
		prewarm();
	}

	private static java.lang.foreign.MemorySegment native_(String name) {
		return MetalBridge.library().find(name).orElseThrow(() -> new IllegalStateException("missing native symbol " + name));
	}

	public static void frame() {
		if (own == 0 || shadeFs == null) return;
		if (!prewarmed) prewarm();
		int tag = ShadeLean.tag();
		int pbr = mcopt.metal.shade.ShadePbr.active() ? ShadeMsl.PBR_MATERIALS : 0;
		int opaqueBits = (tag & ShadeMsl.LEAN_TERRAIN) | pbr, translucentBits = (tag & (ShadeMsl.LEAN_TERRAIN | ShadeMsl.LEAN_WATER)) | pbr;
		long opaque = library(false, opaqueBits), translucent = library(true, translucentBits);
		if (opaque == 0 && opaqueBits != 0) opaque = library(false, opaqueBits = 0);
		if (translucent == 0 && translucentBits != 0) translucent = library(true, translucentBits = 0);
		int key = (opaqueBits & 255) | (translucentBits & 255) << 8 | (opaque != 0 ? 1 << 16 : 0)
			| (translucent != 0 ? 1 << 17 : 0) | (pbr != 0 ? 1 << 18 : 0);
		try {
			shadeFs.invokeExact(own, opaque, translucent, key, ShadeConfig.HDR ? 1 : 0);
		} catch (Throwable t) {
			throw new IllegalStateException(t);
		}
		logBuilt(key);
		frames++;
		if (PREWARM_PIPELINES && (frames == 30 || frames == 1200)) prewarmPipelines();
		if (!said && opaque != 0) {
			said = true;
			System.out.println("[mcopt] shade: mcopt's own terrain lit per pixel (" + LIBRARIES.size() + " stage variants building or built)");
		}
	}

	private static final long BUILT = org.lwjgl.system.MemoryUtil.nmemAlloc(16);
	private static long builtLogAt;

	private static void logBuilt(int key) {
		try {
			shadeBuilt.invokeExact(own, BUILT);
		} catch (Throwable t) {
			return;
		}
		double n = org.lwjgl.system.MemoryUtil.memGetDouble(BUILT), ms = org.lwjgl.system.MemoryUtil.memGetDouble(BUILT + 8);
		long now = System.nanoTime();
		if (n > builtSeen && now - builtLogAt > 2_000_000_000L) {
			System.out.println(String.format("[mcopt] shade: own terrain pipelines built on the render thread: %.0f so far, %.0f ms in all (stage key %x)", n, ms, key));
			builtSeen = n;
			builtLogAt = now;
		}
	}

	private static long library(boolean translucent, int bits) {
		int key = (translucent ? TRANSLUCENT : 0) | bits;
		CompletableFuture<Long> f = LIBRARIES.get(key);
		if (f == null) {
			CompletableFuture<Long> mine = new CompletableFuture<>();
			f = LIBRARIES.putIfAbsent(key, mine);
			if (f == null) {
				mine.complete(compile(translucent, bits));
				f = mine;
			}
		}
		return f.join();
	}

	private static long compile(boolean translucent, int bits) {
		long t0 = System.nanoTime();
		try {
			long lib = ShadeRender.library(ShadeMsl.ownFragmentSource(translucent, bits));
			if (Boolean.getBoolean("mcopt.shade.terrain.log")) {
				System.out.println(String.format("[mcopt] shade: own terrain stage %s lean %d built in %.0f ms", translucent ? "translucent" : "opaque", bits,
					(System.nanoTime() - t0) / 1e6));
			}
			return lib;
		} catch (RuntimeException e) {
			if (!failedSaid) {
				failedSaid = true;
				System.out.println("[mcopt] shade: own terrain's shaded stage (" + (translucent ? "translucent" : "opaque") + ", lean " + bits
					+ ") failed to build, " + (bits != 0 ? "using the full stage" : "vanilla's look") + ": "
					+ String.valueOf(e.getMessage()).lines().limit(8).reduce((a, b) -> a + " | " + b).orElse(""));
			}
			return 0;
		}
	}

	private static void prewarm() {
		if (!ShadeRender.ready()) return;
		prewarmed = true;
		int tag = ShadeLean.tag();
		List<int[]> sets = new ArrayList<>();
		sets.add(new int[] {0, tag & ShadeMsl.LEAN_TERRAIN});
		sets.add(new int[] {1, tag & (ShadeMsl.LEAN_TERRAIN | ShadeMsl.LEAN_WATER)});
		if (ShadeLean.ON) {
			for (int t = 7; t >= 0; t--) sets.add(new int[] {0, t});
			for (int t = 0; t < 4; t++) sets.add(new int[] {0, ShadeMsl.LEAN_NETHER | t << 1});
			for (int t = 7; t >= 0; t--) sets.add(new int[] {1, t});
			for (int t = 0; t < 4; t++) sets.add(new int[] {1, ShadeMsl.LEAN_NETHER | t << 1});
			boolean dev = !ShadeLean.DEV_DEFINES.isEmpty();
			for (int t = 0; t < 4; t++) {
				int b = ShadeMsl.LEAN_OW | t << 1;
				sets.add(new int[] {1, b | ShadeMsl.LEAN_ABOVE | ShadeMsl.LEAN_NOFALLS});
				sets.add(new int[] {1, b | ShadeMsl.LEAN_ABOVE});
				sets.add(new int[] {1, b | ShadeMsl.LEAN_NOFALLS});
				if (dev) {
					sets.add(new int[] {1, b | ShadeMsl.LEAN_ABOVE | ShadeMsl.LEAN_DEV});
					sets.add(new int[] {1, b | ShadeMsl.LEAN_ABOVE | ShadeMsl.LEAN_DEV | ShadeMsl.LEAN_NOFALLS});
				}
			}
		} else {
			sets.add(new int[] {0, 0});
			sets.add(new int[] {1, 0});
		}
		for (int[] v : sets) {
			boolean translucent = v[0] == 1;
			int key = (translucent ? TRANSLUCENT : 0) | v[1];
			CompletableFuture<Long> mine = new CompletableFuture<>();
			if (LIBRARIES.putIfAbsent(key, mine) != null) continue;
			Runnable task = () -> mine.complete(compile(translucent, v[1]));
			if (!mcopt.metal.shade.ShadeCompile.background(task)) COMPILER.execute(task);
		}
	}

	private static final java.util.concurrent.atomic.AtomicInteger PREWARM_LEFT = new java.util.concurrent.atomic.AtomicInteger(),
		PREWARM_BUILT = new java.util.concurrent.atomic.AtomicInteger();
	private static volatile long prewarmT0;

	private static void prewarmPipelines() {
		if (PREWARM_LEFT.get() > 0) return;
		prewarmT0 = System.nanoTime();
		PREWARM_BUILT.set(0);
		List<Map.Entry<Integer, CompletableFuture<Long>>> all = new ArrayList<>(LIBRARIES.entrySet());
		PREWARM_LEFT.set(all.size());
		long round = frames;
		for (Map.Entry<Integer, CompletableFuture<Long>> e : all) {
			boolean translucent = (e.getKey() & TRANSLUCENT) != 0;
			e.getValue().whenComplete((lib, ex) -> {
				Runnable task = () -> {
					try {
						if (lib != null && lib != 0) PREWARM_BUILT.addAndGet((int) shadePrewarm.invokeExact(own, (long) lib, translucent ? 1 : 0, ShadeConfig.HDR ? 1 : 0));
					} catch (Throwable t) {
						System.out.println("[mcopt] shade: own terrain pipeline prewarm failed: " + t);
					}
					if (PREWARM_LEFT.decrementAndGet() == 0) {
						System.out.println(String.format("[mcopt] shade: own terrain pipelines prewarmed in the background: %d for %d stage variants in %.1f s (from frame %d)",
							PREWARM_BUILT.get(), all.size(), (System.nanoTime() - prewarmT0) / 1e9, round));
					}
				};
				if (!mcopt.metal.shade.ShadeCompile.background(task)) COMPILER.execute(task);
			});
		}
	}

	public static void beforeTranslucent(Object encoder) {
		MetalBridge.captureWater(encoder);
	}

	private static final boolean UOCC_GATE = Boolean.getBoolean("mcopt.shade.ownUoccGate");

	public static boolean noUocc() {
		if (!UOCC_GATE || !ShadeTaa.ON) return false;
		ShadeTaa t = ShadeTaa.current();
		return t != null && t.swapped() && (t.width(Integer.MAX_VALUE) < t.allocWidth() || t.height(Integer.MAX_VALUE) < t.allocHeight());
	}
}
