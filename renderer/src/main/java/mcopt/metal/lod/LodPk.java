package mcopt.metal.lod;

import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

final class LodPk {
	static final boolean ENABLED = Boolean.getBoolean("mcopt.lod.pk");
	static final float EPS = Float.parseFloat(System.getProperty("mcopt.lod.pkEps", "0.5"));
	static final double SEL = Double.parseDouble(System.getProperty("mcopt.lod.pkSel", "32"));
	static final double FAST = Double.parseDouble(System.getProperty("mcopt.lod.pkFast", "0.5"));
	static final float FAR_EPS = Float.parseFloat(System.getProperty("mcopt.lod.pkFarEps", "0"));
	static final boolean VIS = ENABLED && Boolean.getBoolean("mcopt.lod.pkVis");
	static final double VIS_RES = Double.parseDouble(System.getProperty("mcopt.lod.pkVisRes", "1"));
	static final int VIS_PER_FRAME = Integer.getInteger("mcopt.lod.pkVisPerFrame", 4);
	static final String VIS_EPS = System.getProperty("mcopt.lod.pkVisEps");
	static final int BUDGET = Integer.getInteger("mcopt.lod.pkBudget", 32);
	static final float BAND = Float.parseFloat(System.getProperty("mcopt.lod.pkBand", "64"));
	static final int AZ = 64, BANDS = 2, SECTORS = AZ * BANDS, WORDS = 16, PARAMS_BYTES = 96;
	private static final int PLANT_CAP = 4096, RING = 4;
	private static final int STAND_CAP = ((1 << 21) - (1 << 18)) / (64 * 2);
	private static final double BAND_REACH = 256;
	static final int STAND_BASE = 1 << 18;
	private static final long ALL = -1L;

	private final long ctx;
	private int recCap = Integer.getInteger("mcopt.lod.pkCap", 1 << 17), recCap0 = Integer.getInteger("mcopt.lod.pkCap0", 1 << 15);
	private long recBuf, plantBuf;
	private final long secBuf, secAddr, listBuf, argsBuf;
	final long params = MemoryUtil.nmemCalloc(1, PARAMS_BYTES);
	private final long bufs = MemoryUtil.nmemCalloc(17, 8);
	private final java.util.ArrayDeque<long[]> releaseLater = new java.util.ArrayDeque<>();
	private final boolean[] built = new boolean[SECTORS], dirty = new boolean[SECTORS];
	private final double[] bx = new double[SECTORS], by = new double[SECTORS], bz = new double[SECTORS];
	private boolean selSet;
	private double selX, selZ;
	private double bandR = BAND;
	private final long[] rebuild = new long[2], drawable = new long[2];
	int statFrames, statFallbacks, statRebuilt, statGenerations, statOverflows;

	LodPk(long ctx) {
		this.ctx = ctx;
		this.allocate();
		this.secBuf = LodNative.sharedTrackedBuffer(ctx, (long) SECTORS * WORDS * 4);
		this.secAddr = LodNative.contents(this.secBuf);
		MemoryUtil.memSet(this.secAddr, 0, (long) SECTORS * WORDS * 4);
		this.listBuf = LodNative.privateBuffer(ctx, 512 * 4);
		this.argsBuf = LodNative.privateBuffer(ctx, 512);
	}

	private void allocate() {
		this.recBuf = LodNative.privateBuffer(this.ctx, (long) AZ * (this.recCap0 + this.recCap) * 16);
		this.plantBuf = LodNative.privateBuffer(this.ctx, (long) SECTORS * PLANT_CAP * 32);
	}

	void tileChanged(int level, int tx, int tz) {
		double s = 64.0 * (1 << level), b = 16.0 * (1 << level);
		this.areaChanged(level, tx * s - b, tz * s - b, (tx + 1) * s + b, (tz + 1) * s + b, false);
	}

	void areaChanged(int level, double x0, double z0, double x1, double z1) {
		this.areaChanged(level, x0, z0, x1, z1, true);
	}

	void areaChanged(int level, double x0, double z0, double x1, double z1, boolean refresh) {
		if (this.selSet) {
			double fx = Math.max(Math.abs(x0 - this.selX), Math.abs(x1 - this.selX)), fz = Math.max(Math.abs(z0 - this.selZ), Math.abs(z1 - this.selZ));
			double far = Math.sqrt(fx * fx + fz * fz);
			double nx = Math.max(0, Math.max(x0 - this.selX, this.selX - x1)), nz = Math.max(0, Math.max(z0 - this.selZ, this.selZ - z1));
			double near = Math.sqrt(nx * nx + nz * nz);
			boolean ring = far < this.liveRing - 16 && (refresh || !(level == 0 && LodMesh.REAL_OCC));
			float[] sw = this.switchDist;
			boolean undrawn = sw != null && (refresh && level > 0 && far < sw[level - 1] - (32 << level) - SEL
				|| near > (level < sw.length - 1 ? sw[level] : this.reach) + (32 << level) + SEL);
			if (ring || undrawn) {
				this.statSkipped++;
				return;
			}
		}
		this.markArea(x0, z0, x1, z1, false);
	}

	void occluderDropped(int level, double x0, double z0, double x1, double z1) {
		if (this.selSet && !(level == 0 && LodMesh.REAL_OCC)) {
			double fx = Math.max(Math.abs(x0 - this.selX), Math.abs(x1 - this.selX)), fz = Math.max(Math.abs(z0 - this.selZ), Math.abs(z1 - this.selZ));
			double nx = Math.max(0, Math.max(x0 - this.selX, this.selX - x1)), nz = Math.max(0, Math.max(z0 - this.selZ, this.selZ - z1));
			double far = Math.sqrt(fx * fx + fz * fz), near = Math.sqrt(nx * nx + nz * nz);
			float[] sw = this.switchDist;
			boolean undrawn = far < this.liveRing - 16 || sw != null && (level > 0 && far < sw[level - 1] - (32 << level) - SEL
				|| near > (level < sw.length - 1 ? sw[level] : this.reach) + (32 << level) + SEL);
			if (undrawn) {
				this.statSkipped++;
				return;
			}
		}
		this.statOccDrops++;
		this.markArea(x0, z0, x1, z1, true);
	}

	int statOccDrops;

	void chunkChanged(int cx, int cz) {
		this.markArea(cx * 16.0 - 16, cz * 16.0 - 16, cx * 16.0 + 32, cz * 16.0 + 32, VIS);
	}

	float @org.jspecify.annotations.Nullable [] switchDist;
	private double liveRing, reach = Double.MAX_VALUE;
	int statSkipped, statLive;
	boolean liveNow;

	private void markArea(double x0, double z0, double x1, double z1, boolean drop) {
		if (!this.selSet) return;
		x0 -= this.selX;
		x1 -= this.selX;
		z0 -= this.selZ;
		z1 -= this.selZ;
		double m = SEL + EPS + 2;
		if (x0 <= m && x1 >= -m && z0 <= m && z1 >= -m) {
			java.util.Arrays.fill(this.dirty, true);
			java.util.Arrays.fill(this.full, false);
			return;
		}
		double nx = Math.max(0, Math.max(x0, -x1)), nz = Math.max(0, Math.max(z0, -z1));
		double fx = Math.max(Math.abs(x0), Math.abs(x1)), fz = Math.max(Math.abs(z0), Math.abs(z1));
		double near = Math.sqrt(nx * nx + nz * nz), far = Math.sqrt(fx * fx + fz * fz);
		boolean band0 = near < this.bandR + (drop ? BAND_REACH : 1), band1 = drop || far >= this.bandR - 1;
		double[][] c = {{x0, z0}, {x1, z0}, {x0, z1}, {x1, z1}};
		double a0 = diamond(c[0][0], c[0][1]), lo = 0, hi = 0;
		for (int i = 1; i < 4; i++) {
			double d = diamond(c[i][0], c[i][1]) - a0;
			d = d > 2 ? d - 4 : d < -2 ? d + 4 : d;
			lo = Math.min(lo, d);
			hi = Math.max(hi, d);
		}
		int s0 = (int) Math.floor((a0 + lo) * (AZ / 4.0)) - 2, s1 = (int) Math.floor((a0 + hi) * (AZ / 4.0)) + 2;
		for (int s = s0; s <= s1; s++) {
			int a = Math.floorMod(s, AZ);
			if (band0) this.dirty[a] = true;
			if (band1) this.dirty[AZ + a] = true;
			if (band0) this.full[a] = false;
			if (band1) this.full[AZ + a] = false;
			if (band0 && this.inViewS[a]) this.dirtyMarks++;
			if (band1 && this.inViewS[AZ + a]) this.dirtyMarks++;
		}
	}

	static double diamond(double x, double z) {
		double q = x / Math.max(Math.abs(x) + Math.abs(z), 1e-20);
		return z >= 0 ? 1 - q : 3 + q;
	}

	private static double[] dir(double a) {
		a -= 4 * Math.floor(a / 4);
		double q = a < 2 ? 1 - a : a - 3, z = a < 2 ? 1 - Math.abs(q) : Math.abs(q) - 1;
		double l = Math.sqrt(q * q + z * z);
		return new double[] {q / l, z / l};
	}

	void prepare(Matrix4f viewProj, double cx, double cy, double cz, double reach, float fwdX, float fwdZ, float maskDist) {
		this.statFrames++;
		this.checkOverflow();
		while (!this.releaseLater.isEmpty() && this.statFramesTotal - this.releaseLater.peek()[0] >= RING) LodNative.release(this.releaseLater.poll()[1]);
		this.statFramesTotal++;
		if (this.lastSet) {
			double dx = cx - this.lastX, dy = cy - this.lastY, dz = cz - this.lastZ;
			double v = Math.sqrt(dx * dx + dy * dy + dz * dz);
			this.speed = v > 8 ? 0 : this.speed * 0.9 + v * 0.1;
		}
		this.lastSet = true;
		this.lastX = cx;
		this.lastY = cy;
		this.lastZ = cz;
		float nearEps = (float) Math.min(4.0, Math.max(EPS, this.speed * 12));
		float farEps = FAR_EPS > 0 ? (float) Math.max(nearEps, Math.min(FAR_EPS, this.speed * 48)) : nearEps;
		this.batchEps = nearEps;
		double bandAt = maskDist + BAND;
		if (!this.selSet || Math.hypot(cx - this.selX, cz - this.selZ) > SEL || bandAt != this.bandR) {
			this.selSet = true;
			this.selX = cx;
			this.selZ = cz;
			this.bandR = bandAt;
			java.util.Arrays.fill(this.built, false);
			java.util.Arrays.fill(this.dirty, false);
			java.util.Arrays.fill(this.full, false);
			this.statGenerations++;
		}
		this.reach = reach;
		long inNear = this.inView(viewProj, cx, cy, cz, this.bandR + BAND_REACH), inFar = this.inView(viewProj, cx, cy, cz, reach * 1.5 + 1024);
		boolean[] inView = this.inViewS, valid = this.validS, rb = this.rbS;
		double[] stale = this.stale;
		for (int s = 0; s < SECTORS; s++) {
			inView[s] = ((s < AZ ? inNear : inFar) >>> (s & (AZ - 1)) & 1) != 0;
			double dx = cx - this.bx[s], dy = cy - this.by[s], dz = cz - this.bz[s];
			stale[s] = Math.sqrt(dx * dx + dy * dy + dz * dz) / this.eps[s];
			valid[s] = this.built[s] && !this.dirty[s] && stale[s] <= 0.98 && !this.full[s];
			rb[s] = false;
			if (!valid[s] && inView[s]) {
				if (!this.built[s]) this.statUnbuilt++;
				else if (this.dirty[s]) this.statDirty++;
				else this.statExpired++;
			}
		}
		double fa = diamond(fwdX, fwdZ);
		boolean must = false;
		double oldest = 0;
		for (int s = 0; s < SECTORS; s++) {
			must |= inView[s] && !valid[s] && !this.full[s];
			if (inView[s] && valid[s]) oldest = Math.max(oldest, stale[s]);
		}
		boolean due = must || oldest >= 0.6, background = this.speed < 0.005 && (due || this.statFramesTotal % 16 == 0);
		int n = 0, band = -1;
		while (due && n < BUDGET) {
			int best = -1;
			double bestD = 0;
			for (int s = 0; s < SECTORS; s++) {
				if (!inView[s] || valid[s] || rb[s] || this.full[s] || (band >= 0 && s / AZ != band)) continue;
				double d = Math.abs(((s & (AZ - 1)) + 0.5) * (4.0 / AZ) - fa);
				d = Math.min(d, 4 - d);
				if (best < 0 || d < bestD) {
					best = s;
					bestD = d;
				}
			}
			if (best < 0) break;
			rb[best] = true;
			n++;
			if (FAR_EPS > 0) band = best / AZ;
		}
		while (due && n < BUDGET) {
			int best = -1;
			for (int s = 0; s < SECTORS; s++)
				if (inView[s] && !rb[s] && valid[s] && stale[s] > 0.3 && (band < 0 || s / AZ == band) && (best < 0 || stale[s] > stale[best])) best = s;
			if (best < 0) break;
			rb[best] = true;
			n++;
			if (FAR_EPS > 0) band = best / AZ;
		}
		for (int s = 0, bg = 0; background && s < SECTORS && n < BUDGET + 4 && bg < 4; s++)
			if (!valid[s] && !rb[s] && !this.full[s] && (band < 0 || s / AZ == band) && (FAR_EPS <= 0 || band >= 0 || (band = s / AZ) >= 0)) {
				rb[s] = true;
				n++;
				bg++;
			}
		if (band == 1) this.batchEps = farEps;
		long r0 = 0, r1 = 0, d0 = 0, d1 = 0, l0 = 0, l1 = 0;
		for (int s = 0; s < SECTORS; s++) {
			long bit = 1L << (s & (AZ - 1));
			boolean draw = valid[s] || rb[s], live = inView[s] && !draw;
			if (s < AZ) {
				if (rb[s]) r0 |= bit;
				if (draw) d0 |= bit;
				if (live) l0 |= bit;
			} else {
				if (rb[s]) r1 |= bit;
				if (draw) d1 |= bit;
				if (live) l1 |= bit;
			}
		}
		this.rebuild[0] = r0;
		this.rebuild[1] = r1;
		this.drawable[0] = d0;
		this.drawable[1] = d1;
		this.liveNow = (l0 | l1) != 0;
		if (this.liveNow) this.statFallbacks++;
		this.statLive += Long.bitCount(l0) + Long.bitCount(l1);
		this.statRebuilt += Long.bitCount(r0) + Long.bitCount(r1);
		this.statRebuiltNear += Long.bitCount(r0);
		float nearFar = (float) (this.bandR + BAND_REACH + SEL + 8);
		long p = this.params;
		MemoryUtil.memPutLong(p, r0);
		MemoryUtil.memPutLong(p + 8, r1);
		MemoryUtil.memPutLong(p + 16, d0);
		MemoryUtil.memPutLong(p + 24, d1);
		MemoryUtil.memPutLong(p + 32, l0);
		MemoryUtil.memPutLong(p + 40, l1);
		MemoryUtil.memPutLong(p + 48, widen(r0 | r1, 2));
		MemoryUtil.memPutLong(p + 56, widen(l0 | l1, 2));
		MemoryUtil.memPutInt(p + 64, this.recCap);
		MemoryUtil.memPutInt(p + 68, PLANT_CAP);
		MemoryUtil.memPutInt(p + 72, STAND_CAP);
		MemoryUtil.memPutInt(p + 76, STAND_BASE);
		MemoryUtil.memPutInt(p + 80, this.recCap0);
		MemoryUtil.memPutFloat(p + 84, r1 != 0 ? 1e30F : nearFar);
		MemoryUtil.memPutFloat(p + 88, l1 != 0 ? 1e30F : nearFar);
		MemoryUtil.memPutInt(p + 92, 0);
	}

	boolean rebuilding() {
		return (this.rebuild[0] | this.rebuild[1]) != 0;
	}

	private final boolean[] inViewS = new boolean[SECTORS], validS = new boolean[SECTORS], rbS = new boolean[SECTORS];
	int statRebuiltNear;

	private boolean lastSet;
	private double lastX, lastY, lastZ, speed;
	private long trackNanos;
	private double tx, ty, tz, bps;
	private boolean fast;
	int statFastFrames, statSwitches;

	static final boolean LOAD = "load".equals(System.getProperty("mcopt.lod.pkSwitch", "speed"));
	static final int TIME_EVERY = 8;
	private static final double MARGIN = 0.12, SWITCH_HOLD_S = 0.15, MIN_DWELL_S = 0.5, WARM_S = 0.25;
	private static final int PROBE = 32, PROBE_FAR = 128;

	boolean lists(double cx, double cy, double cz, Matrix4f viewProj, double reach, boolean load, boolean timed) {
		long now = System.nanoTime();
		if (this.trackNanos != 0) {
			double dt = Math.max(1e-4, (now - this.trackNanos) / 1e9);
			double dx = cx - this.tx, dz = cz - this.tz, dy = cy - this.ty, d3 = Math.sqrt(dx * dx + dy * dy + dz * dz);
			double a = Math.min(1, dt / 0.25);
			this.bps = d3 > 16 ? 0 : this.bps + (d3 / dt - this.bps) * a;
			this.perFrame = d3 > 16 ? 0 : this.perFrame + (d3 - this.perFrame) * 0.1;
		}
		this.trackNanos = now;
		this.tx = cx;
		this.ty = cy;
		this.tz = cz;
		this.probing = false;
		boolean was = this.fast;
		this.framesSinceTimed++;
		if (load && this.tFull > 0) this.fast = !this.loadSwitch(now, cx, cy, cz, viewProj, reach);
		else if (FAST < 0) this.fast = true;
		else if (FAST > 0) this.fast = this.fast ? this.bps > FAST * 0.75 : this.bps > FAST;
		if (was != this.fast) {
			this.statSwitches++;
			this.switchedAt = now;
		}
		if (this.fast) {
			this.statFastFrames++;
			this.lastSet = false;
			return false;
		}
		if (load && ++this.sinceProbe >= (this.tLists > 0 && this.tLists < 0.7 * this.tFull ? PROBE_FAR : PROBE) && timed) {
			this.sinceProbe = 0;
			this.probing = true;
			this.statProbes++;
			return false;
		}
		return true;
	}

	private boolean loadSwitch(long now, double cx, double cy, double cz, Matrix4f viewProj, double reach) {
		boolean onLists = !this.fast;
		double since = (now - this.switchedAt) / 1e9;
		double costL, costF = this.tFull;
		int inView = 0;
		if (onLists) {
			for (int s = 0; s < SECTORS; s++) if (this.inViewS[s]) inView++;
		} else {
			if (!this.selSet) {
				this.selX = cx;
				this.selZ = cz;
			}
			long near = this.inView(viewProj, cx, cy, cz, this.bandR + BAND_REACH), far = this.inView(viewProj, cx, cy, cz, reach * 1.5 + 1024);
			inView = Long.bitCount(near) + Long.bitCount(far);
			for (int s = 0; s < SECTORS; s++) this.inViewS[s] = ((s < AZ ? near : far) >>> (s & (AZ - 1)) & 1) != 0;
		}
		double v = this.perFrame, e = Math.min(4.0, Math.max(EPS, v * 12));
		double demand = inView * (v / (0.45 * e) + v / SEL) + this.dirtyRate;
		this.estDemand = demand;
		if (onLists) {
			if (since >= WARM_S && demand > 0.05) this.demandScale += (Math.clamp(this.rebuildRate / demand, 0.25, 4.0) - this.demandScale) * 0.002;
			costL = since < WARM_S || this.tLists <= 0 ? -1 : this.tLists;
		} else {
			costL = this.tDraw + demand * this.demandScale * this.cSector;
		}
		this.estLists = costL;
		boolean other = costL >= 0 && (onLists ? costL > costF * (1 - MARGIN) : costL < costF * (1 - 2 * MARGIN));
		double dwell = onLists ? MIN_DWELL_S : Math.max(MIN_DWELL_S, this.backoff);
		if (!other || since < dwell) {
			this.otherSince = 0;
			return onLists;
		}
		if (this.otherSince == 0) this.otherSince = now;
		if ((now - this.otherSince) / 1e9 < SWITCH_HOLD_S) return onLists;
		this.otherSince = 0;
		if (onLists) this.backoff = since < 2.0 ? Math.min(8.0, Math.max(1.0, this.backoff * 2)) : 0;
		else this.tLists = -1;
		return !onLists;
	}

	private double rebuildRate, demandScale = 1, backoff;

	private double tFull = -1, tLists = -1, tDraw = 0.25, cSector = 0.02, dirtyRate, perFrame, estLists, estDemand;
	private long switchedAt, otherSince;
	private int sinceProbe, dirtyMarks, framesSinceTimed;
	private boolean probing;
	int statProbes;
	private final byte[] slotPath = new byte[16];
	private final int[] slotRebuilt = new int[16];
	private final boolean[] slotLive = new boolean[16];

	void timedPath(int slot, boolean listsRan) {
		this.slotPath[slot] = (byte) (listsRan ? 2 : 1);
		this.slotRebuilt[slot] = listsRan ? Long.bitCount(this.rebuild[0]) + Long.bitCount(this.rebuild[1]) : 0;
		if (listsRan) this.rebuildRate += (this.slotRebuilt[slot] - this.rebuildRate) * 0.08;
		this.slotLive[slot] = listsRan && this.liveNow;
		this.dirtyRate += (this.dirtyMarks / (double) Math.max(1, this.framesSinceTimed) - this.dirtyRate) * 0.3;
		this.dirtyMarks = 0;
		this.framesSinceTimed = 0;
	}

	void cullTime(int slot, double ms) {
		byte path = this.slotPath[slot];
		this.slotPath[slot] = 0;
		if (path == 0 || ms < 0) return;
		if (path == 1) {
			this.tFull = this.tFull < 0 ? ms : this.tFull + (ms - this.tFull) * (this.fast ? 0.3 : 0.5);
			return;
		}
		int n = this.slotRebuilt[slot];
		if (!this.slotLive[slot]) {
			if (n == 0) this.tDraw += (ms - this.tDraw) * 0.3;
			else this.cSector += (Math.max(0, ms - this.tDraw) / n - this.cSector) * 0.3;
		}
		if (!this.fast && (System.nanoTime() - this.switchedAt) / 1e9 >= WARM_S) this.tLists = this.tLists < 0 ? ms : this.tLists + (ms - this.tLists) * 0.15;
	}

	String loadState() {
		return String.format("load switch: full %.3f ms, lists %.3f (draw %.3f + %.4f a sector rebuilt; estimate %.3f at %.2f sectors a frame x %.2f, %.2f"
			+ " rebuilt), %d probes, backoff %.1f s, on the %s", this.tFull, this.tLists, this.tDraw, this.cSector, this.estLists, this.estDemand, this.demandScale,
			this.rebuildRate, this.statProbes, this.backoff, this.fast ? "full cull" : "lists");
	}

	private float batchEps = EPS;
	private final float[] eps = new float[SECTORS];
	private final double[] stale = new double[SECTORS];
	int statUnbuilt, statDirty, statExpired;

	private long statFramesTotal;

	void encoded(double cx, double cy, double cz) {
		for (int s = 0; s < SECTORS; s++) {
			if ((this.rebuild[s / AZ] >>> (s & (AZ - 1)) & 1) == 0) continue;
			this.built[s] = true;
			this.dirty[s] = false;
			this.eps[s] = this.batchEps;
			this.bx[s] = cx;
			this.by[s] = cy;
			this.bz[s] = cz;
			this.visPending[s] = VIS;
		}
	}

	private final boolean[] visPending = new boolean[SECTORS];
	private final long visFacet = MemoryUtil.nmemCalloc(1, 512), visUse = MemoryUtil.nmemCalloc(1, 16);
	int statVisPruned;

	void prune(long lod, long enc, long frame, int frameLength, long comp, int compLength, long bufs) {
		if (!VIS) return;
		long u0 = 0, u1 = 0;
		for (int s = 0; s < SECTORS; s++)
			if (this.built[s] && !this.dirty[s]) {
				if (s < AZ) u0 |= 1L << s;
				else u1 |= 1L << (s - AZ);
			}
		MemoryUtil.memPutLong(this.visUse, u0);
		MemoryUtil.memPutLong(this.visUse + 8, u1);
		double texX = MemoryUtil.memGetFloat(comp + 240);
		double focal = texX > 0 ? 1.0 / texX : 1000;
		for (int n = 0; n < VIS_PER_FRAME; n++) {
			int best = -1;
			for (int s = 0; s < SECTORS; s++) {
				if (!this.visPending[s] || !this.validS[s] || this.rbS[s]) continue;
				if (best < 0 || this.inViewS[s] && !this.inViewS[best]) best = s;
			}
			if (best < 0) break;
			this.visPending[best] = false;
			int a = best & (AZ - 1);
			double ex = dirX(a), ez = dirZ(a), fx = dirX(a + 0.5), fz = dirZ(a + 0.5), gx = dirX(a + 1), gz = dirZ(a + 1);
			double half = Math.acos(Math.min(1, ex * gx + ez * gz)), th = Math.tan(half), tv = Math.tan(Math.toRadians(35));
			int fw = (int) Math.ceil(th * focal * 2 * VIS_RES), fh = (int) Math.ceil(tv * focal * 2 * VIS_RES);
			MemoryUtil.memCopy(comp, this.visFacet, compLength);
			long f = this.visFacet;
			double sx = 1 / th, sy = 1 / tv, rx = -fz, rz = fx;
			float[] m = {(float) (rx * sx), 0, 0, (float) fx, 0, (float) sy, 0, 0, (float) (rz * sx), 0, 0, (float) fz, 0, 0, 1, 0};
			for (int i = 0; i < 16; i++) MemoryUtil.memPutFloat(f + i * 4L, m[i]);
			MemoryUtil.memPutFloat(f + 112, fw);
			MemoryUtil.memPutFloat(f + 116, fh);
			double ox = Math.floor(this.bx[best]), oy = Math.floor(this.by[best]), oz = Math.floor(this.bz[best]);
			MemoryUtil.memPutInt(f + 208, (int) ox);
			MemoryUtil.memPutInt(f + 212, (int) oy);
			MemoryUtil.memPutInt(f + 216, (int) oz);
			MemoryUtil.memPutFloat(f + 224, (float) (this.bx[best] - ox));
			MemoryUtil.memPutFloat(f + 228, (float) (this.by[best] - oy));
			MemoryUtil.memPutFloat(f + 232, (float) (this.bz[best] - oz));
			float e = VIS_EPS != null ? Float.parseFloat(VIS_EPS) : this.eps[best];
			LodNative.pkVis(lod, enc, frame, frameLength, f, compLength, this.params, PARAMS_BYTES, bufs, a, best < AZ ? 1 : 2, e, e,
				this.visUse, fw, fh, Math.max(this.recCap, this.recCap0));
			this.statVisPruned++;
		}
	}

	private static double dirX(double a) {
		double d = a * (4.0 / AZ);
		d -= 4 * Math.floor(d / 4);
		double q = d < 2 ? 1 - d : d - 3, z = d < 2 ? 1 - Math.abs(q) : Math.abs(q) - 1;
		return q / Math.hypot(q, z);
	}

	private static double dirZ(double a) {
		double d = a * (4.0 / AZ);
		d -= 4 * Math.floor(d / 4);
		double q = d < 2 ? 1 - d : d - 3, z = d < 2 ? 1 - Math.abs(q) : Math.abs(q) - 1;
		return z / Math.hypot(q, z);
	}

	void frameFields(long frame, double cx, double cz) {
		this.liveRing = -1;
		MemoryUtil.memPutFloat(frame + 116, (float) (this.selX - cx));
		MemoryUtil.memPutFloat(frame + 120, (float) (this.selZ - cz));
		MemoryUtil.memPutFloat(frame + 124, (float) this.liveRing);
		MemoryUtil.memPutInt(frame + 136, STAND_BASE);
		MemoryUtil.memPutFloat(frame + 408, this.batchEps);
		MemoryUtil.memPutFloat(frame + 412, (float) this.bandR);
		MemoryUtil.memPutInt(frame + 424, this.recCap);
	}

	long bufs(long table, long arena, long mask, LodMesh mesh, LodClip clip) {
		long[] b = {table, arena, mask, this.argsBuf, mesh.instBuf, this.plantBuf, this.recBuf, mesh.horizonBuf, mesh.listBuf, clip.geomBuf, clip.crownBuf,
			this.secBuf, mesh.instBuf, this.listBuf, mesh.argsBuf, mesh.survBuf, mesh.plantBuf};
		for (int i = 0; i < b.length; i++) MemoryUtil.memPutLong(this.bufs + i * 8L, b[i]);
		return this.bufs;
	}

	private long inView(Matrix4f m, double cx, double cy, double cz, double r) {
		double p0x = m.m03() + m.m00(), p0y = m.m13() + m.m10(), p0z = m.m23() + m.m20(), p0w = m.m33() + m.m30();
		double p1x = m.m03() - m.m00(), p1y = m.m13() - m.m10(), p1z = m.m23() - m.m20(), p1w = m.m33() - m.m30();
		double p2x = m.m03() + m.m01(), p2y = m.m13() + m.m11(), p2z = m.m23() + m.m21(), p2w = m.m33() + m.m31();
		double p3x = m.m03() - m.m01(), p3y = m.m13() - m.m11(), p3z = m.m23() - m.m21(), p3w = m.m33() - m.m31();
		double ax = this.selX - cx, az = this.selZ - cz, y0 = -520 - cy, y1 = 520 - cy;
		long out = 0;
		for (int s = 0; s < AZ; s++) {
			double[] px = this.ptX, pz = this.ptZ;
			px[0] = ax;
			pz[0] = az;
			px[1] = ax + W0X[s] * r;
			pz[1] = az + W0Z[s] * r;
			px[2] = ax + W1X[s] * r;
			pz[2] = az + W1Z[s] * r;
			px[3] = ax + WMX[s] * r;
			pz[3] = az + WMZ[s] * r;
			if (outside(px, pz, y0, y1, p0x, p0y, p0z, p0w) || outside(px, pz, y0, y1, p1x, p1y, p1z, p1w) || outside(px, pz, y0, y1, p2x, p2y, p2z, p2w)
				|| outside(px, pz, y0, y1, p3x, p3y, p3z, p3w)) continue;
			out |= 1L << s;
		}
		return out;
	}

	private final double[] ptX = new double[4], ptZ = new double[4];

	private static boolean outside(double[] px, double[] pz, double y0, double y1, double a, double b, double c, double d) {
		for (int k = 0; k < 4; k++)
			if (a * px[k] + b * y0 + c * pz[k] + d >= 0 || a * px[k] + b * y1 + c * pz[k] + d >= 0) return false;
		return true;
	}

	private static final double[] W0X = new double[AZ], W0Z = new double[AZ], W1X = new double[AZ], W1Z = new double[AZ], WMX = new double[AZ],
		WMZ = new double[AZ];

	static {
		for (int s = 0; s < AZ; s++) {
			double a0 = (s - 0.5) * (4.0 / AZ), a1 = (s + 1.5) * (4.0 / AZ);
			double[] u0 = dir(a0), u1 = dir(a1), um = dir((a0 + a1) * 0.5);
			double ch = Math.sqrt(Math.max(0.5 * (1 + u0[0] * u1[0] + u0[1] * u1[1]), 1e-4));
			W0X[s] = u0[0];
			W0Z[s] = u0[1];
			W1X[s] = u1[0];
			W1Z[s] = u1[1];
			WMX[s] = um[0] / ch;
			WMZ[s] = um[1] / ch;
		}
	}

	private static long widen(long m, int w) {
		long out = 0;
		for (int s = 0; s < AZ; s++)
			if ((m >>> s & 1) != 0)
				for (int k = -w; k <= w; k++) out |= 1L << Math.floorMod(s + k, AZ);
		return out;
	}

	private void checkOverflow() {
		int flags = 0, near = 0;
		for (int s = 0; s < SECTORS; s++) {
			int f = MemoryUtil.memGetInt(this.secAddr + ((long) s * WORDS + 15) * 4);
			flags |= f;
			if (s < AZ) near |= f;
		}
		if (flags == 0) return;
		this.statOverflows++;
		boolean grow0 = (near & 1) != 0 && this.recCap0 < (1 << 20), grow1 = (flags & ~near & 1) != 0 && this.recCap < (1 << 20);
		long now = System.nanoTime();
		if (now - this.overflowLogged > 10_000_000_000L) {
			this.overflowLogged = now;
			System.out.println("mcopt-lod: position-keyed lists overflowed (" + ((flags & 1) != 0 ? "records " : "") + ((flags & 2) != 0 ? "plants " : "")
				+ ((flags & 4) != 0 ? "stand-ins " : "") + "); records per sector " + this.recCap0 + (grow0 ? " -> " + this.recCap0 * 2 : "") + " (hand-off band), "
				+ this.recCap + (grow1 ? " -> " + this.recCap * 2 : "") + " (far band); " + this.statOverflows + " overflows so far");
		}
		if (grow0 || grow1) {
			for (int s = 0; s < SECTORS; s++) MemoryUtil.memPutInt(this.secAddr + ((long) s * WORDS + 15) * 4, 0);
			this.releaseLater.add(new long[] {this.statFramesTotal, this.recBuf});
			if (grow0) this.recCap0 *= 2;
			if (grow1) this.recCap *= 2;
			this.recBuf = LodNative.privateBuffer(this.ctx, (long) AZ * (this.recCap0 + this.recCap) * 16);
			java.util.Arrays.fill(this.built, false);
			return;
		}
		for (int s = 0; s < SECTORS; s++) {
			long w = this.secAddr + ((long) s * WORDS + 15) * 4;
			if (MemoryUtil.memGetInt(w) != 0) {
				this.full[s] = true;
				MemoryUtil.memPutInt(w, 0);
			}
		}
	}

	private final boolean[] full = new boolean[SECTORS];
	private long overflowLogged;

	double recordsMb() {
		return (double) AZ * (this.recCap0 + this.recCap) * 16 / 1048576.0;
	}

	long[] buffers() {
		long[] b = new long[5 + this.releaseLater.size()];
		int i = 0;
		b[i++] = this.recBuf;
		b[i++] = this.plantBuf;
		b[i++] = this.secBuf;
		b[i++] = this.listBuf;
		b[i++] = this.argsBuf;
		for (long[] o : this.releaseLater) b[i++] = o[1];
		return b;
	}
}
