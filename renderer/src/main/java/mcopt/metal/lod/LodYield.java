package mcopt.metal.lod;

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

final class LodYield {
	private LodYield() {
	}

	static final String SPEC = System.getProperty("mcopt.lod.yield", "");
	static final int MODE = SPEC.startsWith("server") ? 1 : SPEC.startsWith("missing") ? 2 : SPEC.startsWith("spare") ? 3 : 0;
	static final boolean ON = MODE != 0;
	static final int THRESH = SPEC.contains(":") ? Integer.parseInt(SPEC.substring(SPEC.indexOf(':') + 1).trim()) : MODE == 3 ? 2 : 0;
	private static final int CORES = Runtime.getRuntime().availableProcessors();
	private static final AtomicInteger spareRunning = new AtomicInteger();
	private static java.util.concurrent.@Nullable ForkJoinPool pool;
	static final int BUDGET = Integer.getInteger("mcopt.lod.yield.budget", 0);

	static volatile boolean pressure;
	static volatile int top = Integer.MAX_VALUE;
	private static final AtomicInteger holders = new AtomicInteger(), paused = new AtomicInteger();
	static final AtomicLong pausedNanos = new AtomicLong(), pressureFrames = new AtomicLong(), sampledFrames = new AtomicLong();
	private static final ThreadLocal<int[]> STATE = ThreadLocal.withInitial(() -> new int[2]);
	private static long frames;
	private static @Nullable Object dispatcher;
	private static @Nullable Object dispatcherOwner;
	private static @Nullable Field dispatcherField;
	private static java.lang.reflect.Method hasWork;

	static void frame(Minecraft mc, double camX, double camZ, int rd) {
		if (frames++ % 3 != 0 || mc.level == null) return;
		boolean p;
		if (MODE == 1) {
			p = serverHasWork(mc);
		} else {
			p = LodGenStats.missing(mc.level, camX, camZ, rd) > (MODE == 3 ? 0 : THRESH);
		}
		pressure = p;
		sampledFrames.incrementAndGet();
		if (p) pressureFrames.incrementAndGet();
	}

	private static boolean serverHasWork(Minecraft mc) {
		try {
			MinecraftServer server = mc.getSingleplayerServer();
			if (server == null || mc.level == null) return false;
			ServerLevel sl = server.getLevel(mc.level.dimension());
			if (sl == null) return false;
			ChunkMap map = sl.getChunkSource().chunkMap;
			if (dispatcherOwner != map) {
				if (dispatcherField == null) {
					dispatcherField = ChunkMap.class.getDeclaredField("worldgenTaskDispatcher");
					dispatcherField.setAccessible(true);
				}
				dispatcher = dispatcherField.get(map);
				hasWork = dispatcher.getClass().getMethod("hasWork");
				dispatcherOwner = map;
			}
			return (boolean) hasWork.invoke(dispatcher);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return false;
		}
	}

	static final boolean AT_TAKE = !"tile".equals(System.getProperty("mcopt.lod.yield.at", "take"));

	private static final ThreadLocal<int[]> SLOT = ThreadLocal.withInitial(() -> new int[1]);

	static boolean admit(int level) {
		if (!AT_TAKE) return true;
		int[] slot = SLOT.get();
		if (slot[0] == 2) holders.decrementAndGet();
		else if (slot[0] == 3) spareRunning.decrementAndGet();
		slot[0] = 0;
		if (!pressure || level >= top) return true;
		int h = holders.get();
		if (h < BUDGET && holders.compareAndSet(h, h + 1)) {
			slot[0] = 2;
			return true;
		}
		if (MODE == 3) {
			int idle = CORES - serverActive() - THRESH, r = spareRunning.get();
			if (r < idle && spareRunning.compareAndSet(r, r + 1)) {
				slot[0] = 3;
				return true;
			}
		}
		return false;
	}

	private static int serverActive() {
		try {
			java.util.concurrent.ForkJoinPool p = pool;
			if (p == null) {
				if (!(net.minecraft.util.Util.backgroundExecutor().service() instanceof java.util.concurrent.ForkJoinPool f)) return CORES;
				pool = p = f;
			}
			return p.getActiveThreadCount();
		} catch (RuntimeException e) {
			return CORES;
		}
	}

	static void idle() {
		long t0 = System.nanoTime();
		paused.incrementAndGet();
		try {
			Thread.sleep(3);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} finally {
			paused.decrementAndGet();
			pausedNanos.addAndGet(System.nanoTime() - t0);
		}
	}

	static void enter(int level, int top) {
		if (AT_TAKE) return;
		int[] s = STATE.get();
		if (s[0]++ > 0 || level >= top) return;
		await(s);
	}

	static void checkpoint() {
		if (AT_TAKE) return;
		int[] s = STATE.get();
		if (s[0] == 1 && s[1] == 0 && pressure) await(s);
	}

	static void exit() {
		if (AT_TAKE) return;
		int[] s = STATE.get();
		if (--s[0] == 0 && s[1] != 0) {
			s[1] = 0;
			holders.decrementAndGet();
		}
	}

	private static void await(int[] s) {
		long t0 = 0;
		while (pressure) {
			int h = holders.get();
			if (h < BUDGET && holders.compareAndSet(h, h + 1)) {
				s[1] = 1;
				break;
			}
			if (t0 == 0) {
				t0 = System.nanoTime();
				paused.incrementAndGet();
			}
			try {
				Thread.sleep(3);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		if (t0 != 0) {
			paused.decrementAndGet();
			pausedNanos.addAndGet(System.nanoTime() - t0);
		}
	}

	static int pausedNow() {
		return paused.get();
	}

	static String summary() {
		long n = Math.max(1, sampledFrames.get());
		return String.format(Locale.ROOT, "pressure %.0f%% of samples, %.1f worker-s paused", 100.0 * pressureFrames.get() / n, pausedNanos.get() / 1e9);
	}
}
