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

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.invoke.MethodHandle;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

import static java.lang.foreign.ValueLayout.JAVA_INT;

public final class ShadeDiag {
	private static final boolean ON = !"false".equals(System.getProperty("mcopt.shade.diag"));
	private static final long PERIOD = 60_000_000_000L, HITCH = 50_000_000L;
	private static final String[] THERMAL = {"nominal", "fair", "serious", "critical"};
	private static @Nullable MethodHandle thermal;
	private static boolean linked;
	private static long start, last;
	private static int frames, hitches, lastThermal = -1;
	private static long worst, gcStart, gcCountStart;

	private ShadeDiag() {
	}

	public static void frame() {
		if (!ON) return;
		long now = System.nanoTime();
		if (start == 0) {
			start = last = now;
			long[] gc = gc();
			gcStart = gc[0];
			gcCountStart = gc[1];
			return;
		}
		long ft = now - last;
		last = now;
		frames++;
		if (ft > HITCH) hitches++;
		worst = Math.max(worst, ft);
		int th = thermal();
		if (th != lastThermal) {
			if (lastThermal >= 0 || th > 0) System.out.println("[mcopt] shade: diag: macOS thermal state " + name(th));
			lastThermal = th;
		}
		if (now - start < PERIOD) return;
		long[] gc = gc();
		var taa = mcopt.metal.shade.taa.ShadeTaa.current();
		System.out.println(String.format(Locale.ROOT, "[mcopt] shade: diag %.0f s: %.1f fps, %d hitches > 50 ms (longest %.0f ms), GC %d ms in %d, thermal %s, render scale %s, GPU memory %s",
			(now - start) * 1e-9, frames / ((now - start) * 1e-9), hitches, worst * 1e-6, gc[0] - gcStart, gc[1] - gcCountStart, name(th),
			taa != null ? String.format(Locale.ROOT, "%.2f", taa.scale()) : "-", gpuMemory()));
		start = now;
		frames = hitches = 0;
		worst = 0;
		gcStart = gc[0];
		gcCountStart = gc[1];
	}

	private static String gpuMemory() {
		try {
			long ctx = ShadeBindings.ctx();
			return ctx == 0 ? "-" : (mcopt.metal.MetalBridge.deviceAllocated(ctx) >> 20) + " MB";
		} catch (Throwable t) {
			return "-";
		}
	}

	private static String name(int th) {
		return th >= 0 && th < THERMAL.length ? THERMAL[th] : "?";
	}

	private static long[] gc() {
		long t = 0, n = 0;
		for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
			t += Math.max(b.getCollectionTime(), 0);
			n += Math.max(b.getCollectionCount(), 0);
		}
		return new long[] {t, n};
	}

	private static int thermal() {
		if (!linked) {
			linked = true;
			try {
				var symbol = mcopt.metal.MetalBridge.library().find("mc_thermal_state");
				if (symbol.isPresent()) thermal = Linker.nativeLinker().downcallHandle(symbol.get(), FunctionDescriptor.of(JAVA_INT));
			} catch (Throwable t) {
				thermal = null;
			}
		}
		if (thermal == null) return -1;
		try {
			return (int) thermal.invokeExact();
		} catch (Throwable t) {
			return -1;
		}
	}
}
