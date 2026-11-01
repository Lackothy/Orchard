package mcopt.metal.own;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicIntegerArray;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;

public final class OwnVisible {
	public static final String MODE = System.getProperty("mcopt.own.int.visible", "false");
	public static final boolean ON = "true".equals(MODE), VERIFY = "verify".equals(MODE);

	private static int generation = 1;
	private static int[] position = new int[0], stamp = new int[0];
	private static final ConcurrentHashMap<SectionRenderDispatcher.RenderSection, Boolean> WITH_BE = new ConcurrentHashMap<>();
	private static SectionRenderDispatcher.RenderSection[] scratch = new SectionRenderDispatcher.RenderSection[64];
	private static int filled;

	private OwnVisible() {
	}

	public static void cleared() {
		generation++;
	}

	public static void added(SectionRenderDispatcher.RenderSection section, int at) {
		int i = section.index;
		if (i >= stamp.length) {
			int cap = Math.max(1024, Integer.highestOneBit(i) * 2);
			stamp = Arrays.copyOf(stamp, cap);
			position = Arrays.copyOf(position, cap);
		}
		stamp[i] = generation;
		position[i] = at;
	}

	public static void meshSet(SectionRenderDispatcher.RenderSection section) {
		if (!section.getSectionMesh().getRenderableBlockEntities().isEmpty()) WITH_BE.put(section, Boolean.TRUE);
	}

	public static SectionRenderDispatcher.RenderSection[] visibleWithBlockEntities(int[] count) {
		int n = 0, g = generation;
		for (SectionRenderDispatcher.RenderSection s : WITH_BE.keySet()) {
			int i = s.index;
			boolean visible = i < stamp.length && stamp[i] == g;
			if (!visible) {
				if (s.getSectionMesh().getRenderableBlockEntities().isEmpty()) {
					WITH_BE.remove(s, Boolean.TRUE);
					if (!s.getSectionMesh().getRenderableBlockEntities().isEmpty()) WITH_BE.put(s, Boolean.TRUE);
				}
				continue;
			}
			if (n == scratch.length) scratch = Arrays.copyOf(scratch, n * 2);
			scratch[n++] = s;
		}
		int[] pos = position;
		Arrays.sort(scratch, 0, n, (a, b) -> Integer.compare(pos[a.index], pos[b.index]));
		if (n < filled) Arrays.fill(scratch, n, filled, null);
		filled = n;
		count[0] = n;
		return scratch;
	}

	public static void levelReset() {
		WITH_BE.clear();
		scratch = new SectionRenderDispatcher.RenderSection[64];
		filled = 0;
	}

	public static int[] visibleSlotsReversed(java.util.BitSet candidates, int candidatesVersion, int[] count) {
		int g = generation;
		if (g == cachedGeneration && candidatesVersion == cachedVersion) {
			count[0] = cachedCount;
			return slotScratch;
		}
		int n = 0;
		for (int i = candidates.nextSetBit(0); i >= 0; i = candidates.nextSetBit(i + 1)) {
			if (i >= stamp.length || stamp[i] != g) continue;
			if (n == slotScratch.length) slotScratch = Arrays.copyOf(slotScratch, n * 2);
			slotScratch[n++] = i;
		}
		long[] keys = n <= keyScratch.length ? keyScratch : (keyScratch = new long[Integer.highestOneBit(n) * 2]);
		for (int j = 0; j < n; j++) keys[j] = (long) position[slotScratch[j]] << 32 | slotScratch[j];
		Arrays.sort(keys, 0, n);
		for (int j = 0; j < n; j++) slotScratch[j] = (int) keys[n - 1 - j];
		count[0] = n;
		cachedGeneration = g;
		cachedVersion = candidatesVersion;
		cachedCount = n;
		return slotScratch;
	}

	private static int cachedGeneration = -1, cachedVersion = -1, cachedCount;
	private static int[] slotScratch = new int[256];
	private static long[] keyScratch = new long[256];
}
