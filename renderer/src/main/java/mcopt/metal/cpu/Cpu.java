package mcopt.metal.cpu;

import java.lang.reflect.Field;

public final class Cpu {
	private static final String CULL_REUSE = System.getProperty("mcopt.cpu.cullReuse", "");
	public static final boolean CULL_SKIP = CULL_REUSE.equals("true");
	public static final boolean CULL_VERIFY = CULL_REUSE.equals("verify");
	public static final boolean CULL_STATS = Boolean.getBoolean("mcopt.cpu.cullStats") || CULL_VERIFY;
	public static final boolean LISTS_AB = "ab".equals(System.getProperty("mcopt.cpu.lists"));
	public static final boolean LISTS = Boolean.getBoolean("mcopt.cpu.lists") || LISTS_AB;
	public static boolean listsActive = true;
	public static final boolean RECORD_AB = "ab".equals(System.getProperty("mcopt.cpu.record"));
	public static final boolean RECORD = Boolean.getBoolean("mcopt.cpu.record") || RECORD_AB;
	private static long recN0, recN1, recNs0, recNs1, recFrames0, recFrames1, recLastFrame = -1, recLastPrint = System.nanoTime();

	public static void recordAb(boolean fast, long ns, long frame) {
		if (frame != recLastFrame) {
			recLastFrame = frame;
			if (fast) recFrames1++;
			else recFrames0++;
		}
		if (fast) {
			recN1++;
			recNs1 += ns;
		} else {
			recN0++;
			recNs0 += ns;
		}
		long now = System.nanoTime();
		if (now - recLastPrint > 5_000_000_000L && recFrames0 > 0 && recFrames1 > 0) {
			recLastPrint = now;
			System.out.printf("mcopt-cpu: ab record per frame off %.2f us on %.2f us ratio %.3f (calls/frame %.0f)%n", recNs0 / 1e3 / recFrames0,
				recNs1 / 1e3 / recFrames1, ((double) recNs1 / recFrames1) / ((double) recNs0 / recFrames0), (double) recN0 / recFrames0);
		}
	}

	public static final boolean CULL_RECOVER = Boolean.getBoolean("mcopt.cpu.cullRecover");
	public static final boolean MODEL_AB = "ab".equals(System.getProperty("mcopt.cpu.model"));
	public static final boolean MODEL = Boolean.getBoolean("mcopt.cpu.model") || MODEL_AB;
	public static boolean modelActive = true;
	private static final String MERGE = System.getProperty("mcopt.cpu.mergeDraws", "");
	public static final boolean MERGE_AB = MERGE.equals("ab");
	public static final boolean MERGE_STATS = MERGE.equals("stats") || Boolean.getBoolean("mcopt.cpu.mergeStats");
	public static final boolean MERGE_ON = MERGE.equals("true") || MERGE_AB || MERGE_STATS;
	public static boolean mergeActive = MERGE_ON;
	public static volatile boolean terrainSplitting = true;
	public static final boolean AB_SHOTS = !System.getProperty("mcopt.cpu.abShots", "").isEmpty();
	public static final boolean LEASH_AB = "ab".equals(System.getProperty("mcopt.cpu.leash"));
	public static final boolean LEASH = Boolean.getBoolean("mcopt.cpu.leash") || LEASH_AB;
	public static boolean leashActive = true;
	public static final int LOADING_FPS = Integer.getInteger("mcopt.cpu.loadingFps", 0);
	public static final boolean PASS_AB = "ab".equals(System.getProperty("mcopt.cpu.pass"));
	public static final boolean PASS = Boolean.getBoolean("mcopt.cpu.pass") || PASS_AB;
	public static final boolean CMD_AHEAD = Boolean.getBoolean("mcopt.cpu.cmdAhead");

	public static int nativeFlags(boolean pass) {
		return (pass ? 1 : 0) | (CMD_AHEAD ? 2 : 0);
	}

	public static final Ab PASS_TIMER = PASS_AB ? new Ab("renderBegin") : null;
	private static final String FENCE_PROP = System.getProperty("mcopt.cpu.fence", "");
	public static final boolean FENCE = FENCE_PROP.equals("true") || FENCE_PROP.equals("true,stats");
	public static final boolean FENCE_STATS = FENCE_PROP.endsWith("stats");
	public static boolean fenceActive = true;
	private static long fenceMade, fenceMoved, fenceWaits, fenceWaitNs, fenceFirstSubmit = -1, fenceLastPrint = System.nanoTime();

	public static void fenceMade(boolean moved) {
		fenceMade++;
		if (moved) fenceMoved++;
	}

	public static void fenceWaited(long ns) {
		fenceWaits++;
		fenceWaitNs += ns;
	}

	public static void fenceTick(long submit) {
		if (fenceFirstSubmit < 0) fenceFirstSubmit = submit;
		long now = System.nanoTime();
		if (now - fenceLastPrint > 5_000_000_000L && submit > fenceFirstSubmit) {
			double frames = submit - fenceFirstSubmit;
			System.out.printf("mcopt-cpu: fence %s made %d moved %d, blocking waits %.3f/frame, blocked %.1f us/frame (%s)%n", FENCE ? "on" : "off",
				fenceMade, fenceMoved, fenceWaits / frames, fenceWaitNs / 1e3 / frames, phase());
			fenceLastPrint = now;
			fenceMade = fenceMoved = fenceWaits = fenceWaitNs = 0;
			fenceFirstSubmit = submit;
		}
	}

	private Cpu() {
	}

	public static boolean cullHooks() {
		return CULL_SKIP || CULL_VERIFY || CULL_STATS || CULL_RECOVER;
	}

	private static Field benchPhase;
	private static boolean benchLooked;

	static String phase() {
		if (!benchLooked) {
			benchLooked = true;
			try {
				benchPhase = Class.forName("mcopt.bench.Bench").getDeclaredField("phase");
				benchPhase.setAccessible(true);
			} catch (ReflectiveOperationException | RuntimeException absent) {
				benchPhase = null;
			}
		}
		if (benchPhase == null) return "game";
		try {
			return String.valueOf(benchPhase.get(null));
		} catch (ReflectiveOperationException e) {
			return "game";
		}
	}
}
