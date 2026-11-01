package mcopt.metal;

import com.mojang.blaze3d.platform.Monitor;
import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;

public final class EdgeCap {
	private static final String MODE = System.getProperty("mcopt.edgeCap", "");
	public static final boolean ON = !MODE.isEmpty() && !"false".equals(MODE);
	private static final int FIXED = ON && !"true".equals(MODE) ? Integer.parseInt(MODE) : 0;
	private static final long HOLD_NS = Long.getLong("mcopt.edgeCap.holdMs", 1000) * 1_000_000L;
	private static final int MARGIN = Integer.getInteger("mcopt.edgeCap.margin", 32);
	private static final boolean LOG = Boolean.getBoolean("mcopt.edgeCap.log");
	private static volatile long lastGenNs = Long.MIN_VALUE / 2;
	private static volatile long player;
	private static volatile int reach;
	private static final java.util.concurrent.atomic.AtomicInteger GEN = new java.util.concurrent.atomic.AtomicInteger(),
		NEAR = new java.util.concurrent.atomic.AtomicInteger(), MIN_D = new java.util.concurrent.atomic.AtomicInteger(Integer.MAX_VALUE),
		MAX_D = new java.util.concurrent.atomic.AtomicInteger();
	private static long nextDiag;
	private static boolean capped;
	private static long since, nextLog;
	private static double cappedS;
	private static int cap;

	private EdgeCap() {}

	public static void generating(ChunkPos pos) {
		int r = reach;
		if (r == 0) return;
		long p = player;
		int dx = pos.x() - (int) (p >> 32), dz = pos.z() - (int) p;
		int d2 = dx * dx + dz * dz;
		if (LOG) {
			GEN.incrementAndGet();
			int d = (int) Math.sqrt(d2);
			MIN_D.accumulateAndGet(d, Math::min);
			MAX_D.accumulateAndGet(d, Math::max);
		}
		if (d2 <= r * r) {
			lastGenNs = System.nanoTime();
			if (LOG) NEAR.incrementAndGet();
		}
	}

	public static int limit(int limit) {
		if (!ON) return limit;
		Minecraft mc = Minecraft.getInstance();
		boolean c = false;
		if (mc.hasSingleplayerServer() && mc.player != null) {
			long now = System.nanoTime();
			player = (long) SectionPos.blockToSectionCoord(mc.player.getBlockX()) << 32 | (SectionPos.blockToSectionCoord(mc.player.getBlockZ()) & 0xffffffffL);
			reach = mc.options.getEffectiveRenderDistance() + MARGIN;
			c = now - lastGenNs < HOLD_NS;
			if (LOG && now >= nextDiag) {
				nextDiag = now + 2_000_000_000L;
				System.out.printf("mcopt-edgecap: generated %d chunks (%d within reach %d), distance %d..%d, capped %b%n", GEN.getAndSet(0),
					NEAR.getAndSet(0), reach, MIN_D.getAndSet(Integer.MAX_VALUE), MAX_D.getAndSet(0), capped);
			}
			if (c != capped) {
				if (c) {
					since = now;
					cap = FIXED > 0 ? FIXED : 2 * refresh(mc);
				} else {
					cappedS += (now - since) / 1e9;
				}
				capped = c;
				if (now >= nextLog || !c) {
					System.out.printf("mcopt-edgecap: %s at %d fps (capped %.1f s so far)%n", c ? "cap on" : "cap off", cap, cappedS);
					nextLog = now + 1_000_000_000L;
				}
			}
		} else {
			reach = 0;
			capped = false;
		}
		return capped ? Math.min(limit, cap) : limit;
	}

	private static int refresh(Minecraft mc) {
		Monitor m = mc.getWindow().findBestMonitor();
		float hz = m == null ? 0 : m.currentMode().getRefreshRate();
		return hz >= 30 ? Math.round(hz) : 120;
	}
}
