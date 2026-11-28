// Modified for Russet: MeshFrame caps.w, the real terrain's water drawn under from far terrain (LodConfig.WATER_UNDER).
package mcopt.metal.lod;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

final class LodMesh implements LodClip.Listener {
	static final int BLOCK = Integer.getInteger("mcopt.lod.meshBlock", 16) == 8 ? 8 : 16;
	static final int BPT = LodClip.TILE / BLOCK, HDR_WORDS = BPT * BPT * 32;
	static final int FRAME_BYTES = 432;
	static final boolean FENCES = Boolean.getBoolean("mcopt.lod.meshFences") && !LodPk.ENABLED;
	static final boolean HORIZON = Boolean.parseBoolean(System.getProperty("mcopt.lod.horizonCull", "true"));
	static final boolean REAL_OCC = Boolean.getBoolean("mcopt.lod.realOcc");
	static final boolean REAL_OCC_HEIGHT = "height".equals(System.getProperty("mcopt.lod.realOccRule"));
	static final boolean HZ_MARK = Boolean.getBoolean("mcopt.lod.hzMark");
	private static final int RING = 4;
	static final int DISSOLVE_MS = Integer.getInteger("mcopt.lod.dissolve", 0);
	private static final long CLOCK0 = System.nanoTime();

	static int clockMs() {
		return (int) ((System.nanoTime() - CLOCK0) / 1_000_000L) & 0xFFFFFF;
	}
	static final int MAX_INSTANCES = LodPk.ENABLED ? 1 << 21 : 1 << 19;
	private static final int MAX_PLANT_INSTANCES = 1 << 16, Q = 32;
	private static final int SCRATCH_QUADS = 1 << 17;
	private static final int MAX_SURVIVORS = 1 << 21;
	private static final long MAX_ARENA_WORDS = 1L << 29;

	private final long ctx;
	private final LodClip clip;
	private final int tps;
	private long arenaBuf, arenaAddr, arenaWords;
	private final TreeMap<Long, Long> free = new TreeMap<>();
	private final ArrayDeque<long[]> releaseLater = new ArrayDeque<>();
	private final ArrayDeque<long[]> buffersLater = new ArrayDeque<>();
	private long usedWords;
	private final long[][] off, len, key;
	private final int[][] builtNeighbors;
	private final long[][] seq;
	private final long[][] builtVer;
	private final ConcurrentHashMap<Long, Long> reqVer = new ConcurrentHashMap<>();
	private final long tableBytes;
	private final long tableHost;
	private final long[] tableBufs = new long[RING];
	final long argsBuf, instBuf, plantBuf, survBuf, horizonBuf, listBuf;
	private static final int MAX_LISTED = 1 << 16;
	final long frame = MemoryUtil.nmemCalloc(1, FRAME_BYTES);
	private final LinkedBlockingDeque<long[]> jobs = new LinkedBlockingDeque<>();
	private final ConcurrentHashMap<Long, Long> queued = new ConcurrentHashMap<>();
	private final ConcurrentLinkedQueue<long[]> results = new ConcurrentLinkedQueue<>();
	private final ConcurrentHashMap<Long, LodClip.Snapshot> snaps = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<Long, LodClip.Snapshot> resultSnaps = new ConcurrentHashMap<>();
	private final long[][] publishedVer;
	private final ArrayDeque<long[]> publishLater = new ArrayDeque<>();
	private static final int PUBLISH_BUDGET = Math.min(LodPublish.CAP, Integer.getInteger("mcopt.lod.publishBudget", 32));
	private final ArrayDeque<long[]> requestLater = new ArrayDeque<>();
	private static final int SCRATCH_LOG = 7, SCRATCH = 1 << SCRATCH_LOG;
	final AtomicLong published = new AtomicLong(), seqRetries = new AtomicLong();
	private final AtomicLong nextSeq = new AtomicLong();
	private final Thread[] workers;
	private volatile boolean closed;
	final AtomicLong meshed = new AtomicLong(), meshNanos = new AtomicLong(), meshQuads = new AtomicLong();
	int installed;
	@Nullable LodPk pk;

	LodMesh(long ctx, LodClip clip) {
		this.ctx = ctx;
		this.clip = clip;
		this.tps = clip.tilesPerSide;
		int slots = this.tps * this.tps;
		this.off = new long[clip.levels][slots];
		this.len = new long[clip.levels][slots];
		this.key = new long[clip.levels][slots];
		this.seq = new long[clip.levels][slots];
		this.builtNeighbors = new int[clip.levels][slots];
		this.builtVer = new long[clip.levels][slots];
		this.publishedVer = new long[clip.levels][slots];
		for (long[] o : this.off) java.util.Arrays.fill(o, -1L);
		for (long[] k : this.key) java.util.Arrays.fill(k, -1L);
		this.tableBytes = (long) clip.levels * slots * 16;
		this.tableHost = MemoryUtil.nmemCalloc(1, this.tableBytes);
		for (int i = 0; i < RING; i++) this.tableBufs[i] = LodNative.buffer(ctx, this.tableBytes);
		this.arenaWords = 16L << 20;
		this.arenaBuf = LodNative.buffer(ctx, this.arenaWords * 4);
		this.arenaAddr = LodNative.contents(this.arenaBuf);
		this.free.put(0L, this.arenaWords);
		java.util.function.LongUnaryOperator gpu = FENCES ? n -> LodNative.privateUntrackedBuffer(ctx, n) : n -> LodNative.privateBuffer(ctx, n);
		this.argsBuf = gpu.applyAsLong(512);
		this.instBuf = gpu.applyAsLong((long) MAX_INSTANCES * 32);
		this.plantBuf = gpu.applyAsLong((long) MAX_PLANT_INSTANCES * 32);
		this.survBuf = gpu.applyAsLong((long) MAX_SURVIVORS * 16);
		this.horizonBuf = gpu.applyAsLong(4096L * 129 * 4);
		this.listBuf = gpu.applyAsLong((long) MAX_LISTED * 16);
		int n = Math.max(1, Integer.getInteger("mcopt.lod.meshThreads", 2));
		this.workers = new Thread[n];
		for (int i = 0; i < n; i++) {
			Thread t = new Thread(this::work, "mcopt-lod-mesh-" + i);
			t.setDaemon(true);
			t.setPriority(Thread.NORM_PRIORITY - 1);
			t.start();
			this.workers[i] = t;
		}
		clip.listener = this;
	}

	@Override
	public void tilePut(int level, int tx, int tz, boolean refreshed) {
		if (LodClip.PAIRS) this.snaps.remove(LodTile.key(level, tx, tz));
		this.request(level, tx, tz);
		int[][] d = {{1, 0, 2}, {-1, 0, 1}, {0, 1, 8}, {0, -1, 4}};
		for (int[] n : d) {
			int nx = tx + n[0], nz = tz + n[1];
			if (!this.clip.resident(level, nx, nz)) continue;
			int ns = this.clip.slot(nx, nz);
			boolean without = this.key[level][ns] != LodTile.key(level, nx, nz) || (this.builtNeighbors[level][ns] & n[2]) == 0;
			if (refreshed || without) this.request(level, nx, nz);
		}
	}

	@Override
	public void tileCleared(int level, int slot) {
		this.drop(level, slot);
		if (LodClip.PAIRS) this.snaps.values().removeIf(sn -> sn.level == level && this.clip.slot(sn.tx, sn.tz) == slot);
	}

	@Override
	public void tileStaged(LodClip.Snapshot snap) {
		this.snaps.put(LodTile.key(snap.level, snap.tx, snap.tz), snap);
		this.request(snap.level, snap.tx, snap.tz);
	}

	private void request(int level, int tx, int tz) {
		long k = LodTile.key(level, tx, tz);
		long s = this.nextSeq.incrementAndGet();
		this.reqVer.put(k, this.clip.slotVer[level][this.clip.slot(tx, tz)]);
		if (this.queued.put(k, s) != null) return;
		this.jobs.add(new long[] {k});
	}

	private void work() {
		long header = MemoryUtil.nmemAlloc((long) HDR_WORDS * 4);
		long quads = MemoryUtil.nmemAlloc((long) SCRATCH_QUADS * 8);
		long scratch = LodClip.PAIRS ? MemoryUtil.nmemCalloc(5L * SCRATCH * SCRATCH, 4) : 0;
		while (!this.closed) {
			long[] job;
			try {
				job = this.jobs.take();
			} catch (InterruptedException e) {
				return;
			}
			long k = job[0];
			Long s = this.queued.remove(k);
			if (s == null) continue;
			Long ver = this.reqVer.get(k);
			int level = LodTile.levelOf(k), tx = LodTile.txOf(k), tz = LodTile.tzOf(k);
			try {
				if (!this.clip.resident(level, tx, tz)) continue;
				int neighbors = (this.clip.resident(level, tx + 1, tz) ? 1 : 0) | (this.clip.resident(level, tx - 1, tz) ? 2 : 0)
					| (this.clip.resident(level, tx, tz + 1) ? 4 : 0) | (this.clip.resident(level, tx, tz - 1) ? 8 : 0);
				long lw = this.clip.levelWords * 4 * level;
				boolean crowns = level < this.clip.crownLevels;
				long crown = crowns ? this.clip.crown + lw : 0, runs = crowns ? this.clip.crown + this.clip.runsOffset() * 4 + lw : 0;
				boolean plants = level == 0 && this.clip.plants;
				long pa = plants ? this.clip.tex + this.clip.levelWords * 4 : 0, pb = plants ? this.clip.tex + this.clip.levelWords * 8 : 0;
				long t0 = System.nanoTime();
				LodClip.Snapshot snap = null;
				int n;
				if (LodClip.PAIRS) {
					snap = this.snaps.get(k);
					long v = this.copyRegion(scratch, level, tx, tz, snap, crowns, plants);
					if (v < 0) {
						this.request(level, tx, tz);
						continue;
					}
					ver = v;
					long sp = (long) SCRATCH * SCRATCH * 4;
					n = LodNative.meshTile(scratch, crowns ? scratch + sp : 0, crowns ? scratch + 2 * sp : 0, plants ? scratch + 3 * sp : 0, plants ? scratch + 4 * sp : 0,
						SCRATCH_LOG, level, tx, tz, neighbors, BLOCK, header, quads, SCRATCH_QUADS);
				} else {
					n = LodNative.meshTile(this.clip.geom + lw, crown, runs, pa, pb, this.clip.logN, level, tx, tz, neighbors, BLOCK, header, quads, SCRATCH_QUADS);
				}
				this.meshNanos.addAndGet(System.nanoTime() - t0);
				if (n < 0) {
					System.out.println("mcopt-lod: mesh of tile L" + level + " " + tx + "," + tz + " overflows " + SCRATCH_QUADS + " quads; not drawn");
					continue;
				}
				long words = HDR_WORDS + 2L * n;
				long block = MemoryUtil.nmemAlloc(words * 4);
				MemoryUtil.memCopy(header, block, (long) HDR_WORDS * 4);
				MemoryUtil.memCopy(quads, block + (long) HDR_WORDS * 4, (long) n * 8);
				this.meshed.incrementAndGet();
				this.meshQuads.addAndGet(n);
				if (snap != null) this.resultSnaps.put(block, snap);
				this.results.add(new long[] {k, s, block, words, neighbors, ver != null ? ver : -1});
			} catch (RuntimeException e) {
				System.out.println("mcopt-lod: mesh job failed: " + e);
			}
		}
		MemoryUtil.nmemFree(header);
		MemoryUtil.nmemFree(quads);
		if (scratch != 0) MemoryUtil.nmemFree(scratch);
	}

	private long copyRegion(long scratch, int level, int tx, int tz, LodClip.Snapshot snap, boolean crowns, boolean plants) {
		var w = this.clip.writing[level];
		int[] slots = {this.clip.slot(tx, tz), this.clip.slot(tx + 1, tz), this.clip.slot(tx - 1, tz), this.clip.slot(tx, tz + 1), this.clip.slot(tx, tz - 1)};
		long[] before = new long[5];
		long sp = (long) SCRATCH * SCRATCH * 4;
		long lw = this.clip.levelWords * 4 * level;
		long crownBase = this.clip.crown + lw, runsBase = this.clip.crown + this.clip.runsOffset() * 4 + lw;
		long paBase = this.clip.tex + this.clip.levelWords * 4, pbBase = this.clip.tex + this.clip.levelWords * 8;
		int m = this.clip.n - 1, sm = SCRATCH - 1;
		int ax0 = tx * LodClip.TILE, az0 = tz * LodClip.TILE;
		for (int attempt = 0; attempt < 64; attempt++) {
			boolean odd = false;
			for (int i = 0; i < 5; i++) {
				before[i] = w.get(slots[i]);
				odd |= (before[i] & 1) != 0;
			}
			if (odd) {
				Thread.onSpinWait();
				continue;
			}
			for (int z = -1; z <= LodClip.TILE; z++) {
				for (int x = -1; x <= LodClip.TILE; x++) {
					boolean outX = x < 0 || x >= LodClip.TILE, outZ = z < 0 || z >= LodClip.TILE;
					if (outX && outZ) continue;
					int ax = ax0 + x, az = az0 + z;
					long dst = (((long) (az & sm) << SCRATCH_LOG) | (ax & sm)) * 4;
					if (snap != null && !outX && !outZ) {
						int i = z * LodClip.TILE + x;
						MemoryUtil.memPutInt(scratch + dst, snap.g[i]);
						if (crowns) {
							MemoryUtil.memPutInt(scratch + sp + dst, snap.cr[i]);
							MemoryUtil.memPutInt(scratch + 2 * sp + dst, snap.runs[i]);
						}
						if (plants) {
							MemoryUtil.memPutInt(scratch + 3 * sp + dst, snap.pl[i]);
							MemoryUtil.memPutInt(scratch + 4 * sp + dst, snap.pl[LodClip.TILE * LodClip.TILE + i]);
						}
						continue;
					}
					long src = (((long) (az & m) << this.clip.logN) | (ax & m)) * 4;
					MemoryUtil.memPutInt(scratch + dst, MemoryUtil.memGetInt(this.clip.geom + lw + src));
					if (crowns) {
						MemoryUtil.memPutInt(scratch + sp + dst, MemoryUtil.memGetInt(crownBase + src));
						MemoryUtil.memPutInt(scratch + 2 * sp + dst, MemoryUtil.memGetInt(runsBase + src));
					}
					if (plants) {
						MemoryUtil.memPutInt(scratch + 3 * sp + dst, MemoryUtil.memGetInt(paBase + src));
						MemoryUtil.memPutInt(scratch + 4 * sp + dst, MemoryUtil.memGetInt(pbBase + src));
					}
				}
			}
			java.lang.invoke.VarHandle.acquireFence();
			boolean same = true;
			for (int i = 0; i < 5; i++) same &= w.get(slots[i]) == before[i];
			if (same) return before[0];
			this.seqRetries.incrementAndGet();
		}
		return -1;
	}

	void integrate(long frame) {
		while (!this.releaseLater.isEmpty() && frame - this.releaseLater.peek()[0] >= RING) {
			long[] r = this.releaseLater.poll();
			this.release(r[1], r[2]);
		}
		while (!this.buffersLater.isEmpty() && frame - this.buffersLater.peek()[0] >= RING) LodNative.release(this.buffersLater.poll()[1]);
		long[] r;
		int budget = 256;
		this.publishes = 0;
		while (!this.requestLater.isEmpty() && this.requestLater.peek()[0] <= frame) {
			long[] q = this.requestLater.poll();
			if (this.clip.resident((int) q[1], (int) q[2], (int) q[3])) this.request((int) q[1], (int) q[2], (int) q[3]);
		}
		int held = this.publishLater.size();
		for (int i = 0; i < held; i++) {
			r = this.publishLater.poll();
			if (this.publishes >= PUBLISH_BUDGET) {
				this.publishLater.add(r);
				continue;
			}
			this.installOrHold(frame, r);
		}
		while (budget-- > 0 && (r = this.results.poll()) != null) this.installOrHold(frame, r);
	}

	private int publishes;

	private void installOrHold(long frame, long[] r) {
		LodClip.Snapshot snap = LodClip.PAIRS ? this.resultSnaps.get(r[2]) : null;
		if (snap != null && this.publishes >= PUBLISH_BUDGET) {
			this.publishLater.add(r);
			return;
		}
		if (snap != null) this.resultSnaps.remove(r[2]);
		try {
			if (LodClip.PAIRS) this.installPair(frame, r, snap);
			else this.install(frame, r);
		} finally {
			MemoryUtil.nmemFree(r[2]);
		}
	}

	private void installPair(long frame, long[] r, LodClip.Snapshot snap) {
		long k = r[0];
		int level = LodTile.levelOf(k), tx = LodTile.txOf(k), tz = LodTile.tzOf(k);
		if (!this.clip.resident(level, tx, tz)) return;
		int slot = this.clip.slot(tx, tz);
		if (snap != null) {
			if (snap.version <= this.publishedVer[level][slot]) return;
			if (!this.clip.publish(snap)) return;
			this.snaps.remove(k, snap);
			this.publishedVer[level][slot] = snap.version;
			this.publishes++;
			this.published.incrementAndGet();
			r[5] = this.clip.writing[level].get(slot);
			this.install(frame, r, true);
			int[][] d = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
			for (int[] n : d) {
				if (!this.clip.resident(level, tx + n[0], tz + n[1])) continue;
				if (LodPublish.ON) this.requestLater.add(new long[] {frame + RING, level, tx + n[0], tz + n[1]});
				else this.request(level, tx + n[0], tz + n[1]);
			}
			return;
		}
		if (r[5] != this.clip.writing[level].get(slot)) return;
		this.install(frame, r, true);
	}

	private void install(long frame, long[] r) {
		this.install(frame, r, false);
	}

	private void install(long frame, long[] r, boolean checked) {
		long k = r[0];
		int level = LodTile.levelOf(k), tx = LodTile.txOf(k), tz = LodTile.tzOf(k);
		if (!this.clip.resident(level, tx, tz)) return;
		int slot = this.clip.slot(tx, tz);
		if (!checked && this.key[level][slot] == k && this.seq[level][slot] > r[1]) return;
		long words = r[3];
		long at = this.allocate(frame, words);
		if (at < 0) {
			System.out.println("mcopt-lod: mesh arena full (" + (this.arenaWords >> 18) + " MB); tile L" + level + " " + tx + "," + tz + " not drawn");
			return;
		}
		MemoryUtil.memCopy(r[2], this.arenaAddr + at * 4, words * 4);
		boolean fresh = this.off[level][slot] < 0 || this.key[level][slot] != k;
		if (this.pk != null) {
			if (this.off[level][slot] >= 0 && this.key[level][slot] == k) this.changedBlocks(level, tx, tz, this.arenaAddr + this.off[level][slot] * 4, r[2]);
			else this.pk.tileChanged(level, tx, tz);
		}
		if (this.off[level][slot] >= 0) {
			this.releaseLater.add(new long[] {frame, this.off[level][slot], this.len[level][slot]});
			this.installed--;
		}
		this.off[level][slot] = at;
		this.len[level][slot] = words;
		this.key[level][slot] = k;
		this.seq[level][slot] = r[1];
		this.builtNeighbors[level][slot] = (int) r[4];
		this.builtVer[level][slot] = r[5];
		this.installed++;
		long e = this.tableHost + ((long) level * this.tps * this.tps + slot) * 16;
		MemoryUtil.memPutInt(e, (int) (at + 1));
		MemoryUtil.memPutInt(e + 4, tx);
		MemoryUtil.memPutInt(e + 8, tz);
		if (DISSOLVE_MS <= 0) MemoryUtil.memPutInt(e + 12, 0);
		else if (fresh) MemoryUtil.memPutInt(e + 12, clockMs() | 1 << 24);
	}

	private void changedBlocks(int level, int tx, int tz, long oldAddr, long newAddr) {
		long oldQ = oldAddr + (long) HDR_WORDS * 4, newQ = newAddr + (long) HDR_WORDS * 4;
		for (int b = 0; b < BPT * BPT; b++) {
			long oh = oldAddr + (long) b * 32 * 4, nh = newAddr + (long) b * 32 * 4;
			boolean dropped = false;
			for (int k = 0; k < 16 && !dropped; k++) {
				int ow = MemoryUtil.memGetInt(oh + (16 + (k >> 1)) * 4L), nw = MemoryUtil.memGetInt(nh + (16 + (k >> 1)) * 4L);
				int ob = MemoryUtil.memGetInt(oh + (24 + (k >> 1)) * 4L), nb = MemoryUtil.memGetInt(nh + (24 + (k >> 1)) * 4L);
				int sh = 24 + (k & 1) * 4;
				dropped = (nw >>> ((k & 1) * 12) & 0xFFF) < (ow >>> ((k & 1) * 12) & 0xFFF)
					|| (nw >>> sh & 15 | (nb >>> sh & 15) << 4) > (ow >>> sh & 15 | (ob >>> sh & 15) << 4);
			}
			boolean same = MemoryUtil.memGetInt(oh + 15 * 4L) == MemoryUtil.memGetInt(nh + 15 * 4L);
			for (int w = 24; w < 32 && same; w++) same = MemoryUtil.memGetInt(oh + w * 4L) == MemoryUtil.memGetInt(nh + w * 4L);
			for (int g = 0; g < 15 && same; g++) {
				int ow = MemoryUtil.memGetInt(oh + g * 4L), nw = MemoryUtil.memGetInt(nh + g * 4L);
				int on = ow >>> 20, nn = nw >>> 20;
				if (on != nn) {
					same = false;
					break;
				}
				long os = oldQ + (long) (ow & 0xFFFFF) * 8, ns = newQ + (long) (nw & 0xFFFFF) * 8;
				for (int i = 0; i < 2 * on && same; i++) same = MemoryUtil.memGetInt(os + i * 4L) == MemoryUtil.memGetInt(ns + i * 4L);
			}
			if (same && !dropped) continue;
			double cell = 1 << level, bs = BLOCK * cell;
			double x0 = (tx * 64 + (b % BPT) * BLOCK) * cell, z0 = (tz * 64 + (b / BPT) * BLOCK) * cell;
			if (!same) this.pk.areaChanged(level, x0 - bs, z0 - bs, x0 + 2 * bs, z0 + 2 * bs);
			if (dropped) this.pk.occluderDropped(level, x0, z0, x0 + bs, z0 + bs);
		}
	}

	private void drop(int level, int slot) {
		if (this.pk != null && this.key[level][slot] >= 0) {
			long k = this.key[level][slot];
			this.pk.tileChanged(level, LodTile.txOf(k), LodTile.tzOf(k));
		}
		if (this.off[level][slot] >= 0) {
			this.releaseLater.add(new long[] {this.lastFrame, this.off[level][slot], this.len[level][slot]});
			this.installed--;
		}
		this.off[level][slot] = -1;
		this.key[level][slot] = -1;
		this.publishedVer[level][slot] = 0;
		MemoryUtil.memSet(this.tableHost + ((long) level * this.tps * this.tps + slot) * 16, 0, 16);
	}

	private long lastFrame;

	boolean installed(int level, int tx, int tz) {
		int slot = this.clip.slot(tx, tz);
		return this.off[level][slot] >= 0 && this.key[level][slot] == LodTile.key(level, tx, tz);
	}

	boolean stale(int level, int tx, int tz) {
		int slot = this.clip.slot(tx, tz);
		return this.installed(level, tx, tz)
			&& this.builtVer[level][slot] != (LodClip.PAIRS ? this.clip.writing[level].get(slot) : this.clip.slotVer[level][slot]);
	}

	private long allocate(long frame, long words) {
		words = (words + 15) & ~15L;
		while (true) {
			for (Map.Entry<Long, Long> f : this.free.entrySet()) {
				if (f.getValue() < words) continue;
				long at = f.getKey(), size = f.getValue();
				this.free.remove(at);
				if (size > words) this.free.put(at + words, size - words);
				this.usedWords += words;
				return at;
			}
			if (this.arenaWords * 2 > MAX_ARENA_WORDS) return -1;
			this.grow(frame);
		}
	}

	private void release(long at, long words) {
		words = (words + 15) & ~15L;
		this.usedWords -= words;
		Map.Entry<Long, Long> lo = this.free.floorEntry(at);
		if (lo != null && lo.getKey() + lo.getValue() == at) {
			at = lo.getKey();
			words += lo.getValue();
			this.free.remove(at);
		}
		Long hi = this.free.get(at + words);
		if (hi != null) {
			this.free.remove(at + words);
			words += hi;
		}
		this.free.put(at, words);
	}

	private void grow(long frame) {
		long words = this.arenaWords * 2;
		long buf = LodNative.buffer(this.ctx, words * 4);
		long addr = LodNative.contents(buf);
		MemoryUtil.memCopy(this.arenaAddr, addr, this.arenaWords * 4);
		this.buffersLater.add(new long[] {frame, this.arenaBuf});
		this.release(this.arenaWords, this.arenaWords);
		this.usedWords += this.arenaWords;
		this.arenaBuf = buf;
		this.arenaAddr = addr;
		this.arenaWords = words;
		System.out.println("mcopt-lod: mesh arena grown to " + (words >> 18) + " MB");
	}

	long arena() {
		return this.arenaBuf;
	}

	long table(long frame) {
		this.lastFrame = frame;
		long b = this.tableBufs[(int) (frame % RING)];
		MemoryUtil.memCopy(this.tableHost, LodNative.contents(b), this.tableBytes);
		this.current = b;
		return b;
	}

	private long current;

	long currentTable() {
		return this.current;
	}

	int blocks() {
		int bpw = this.tps * BPT;
		return this.clip.levels * bpw * bpw;
	}

	void pack(org.joml.Matrix4f viewProj, double camX, double camY, double camZ, double reach, boolean maskOn, int maskX, int maskZ, int maskSize,
		int maskWords, float maskDist) {
		long f = this.frame;
		MemoryUtil.memSet(f, 0, FRAME_BYTES);
		viewProj.getToAddress(f);
		int bx = (int) Math.floor(camX), by = (int) Math.floor(camY), bz = (int) Math.floor(camZ);
		MemoryUtil.memPutFloat(f + 64, (float) (camX - bx));
		MemoryUtil.memPutFloat(f + 68, (float) (camY - by));
		MemoryUtil.memPutFloat(f + 72, (float) (camZ - bz));
		MemoryUtil.memPutFloat(f + 76, (float) reach);
		MemoryUtil.memPutInt(f + 80, bx);
		MemoryUtil.memPutInt(f + 84, by);
		MemoryUtil.memPutInt(f + 88, bz);
		MemoryUtil.memPutInt(f + 92, this.clip.levels);
		MemoryUtil.memPutInt(f + 96, maskOn ? maskX : 0);
		MemoryUtil.memPutInt(f + 100, maskOn ? maskZ : 0);
		MemoryUtil.memPutInt(f + 104, maskOn ? maskSize : 0);
		MemoryUtil.memPutInt(f + 108, Math.max(1, maskWords));
		MemoryUtil.memPutFloat(f + 112, maskOn ? maskDist : 0);
		MemoryUtil.memPutInt(f + 128, this.tps);
		MemoryUtil.memPutInt(f + 132, BLOCK);
		MemoryUtil.memPutInt(f + 136, LodPk.ENABLED ? LodPk.STAND_BASE : MAX_INSTANCES);
		MemoryUtil.memPutInt(f + 140, MAX_PLANT_INSTANCES);
		MemoryUtil.memPutInt(f + 392, HORIZON ? (REAL_OCC ? (REAL_OCC_HEIGHT ? 49 : 17) : 1) | (HZ_MARK ? 8 : 0) : 0);
		MemoryUtil.memPutFloat(f + 400, 48.0F);
		MemoryUtil.memPutFloat(f + 404, (float) (128.0 / Math.log(Math.max(2.0, reach * 2.0 / 48.0))));
		MemoryUtil.memPutInt(f + 416, MAX_SURVIVORS);
		MemoryUtil.memPutInt(f + 420, MAX_LISTED);
		MemoryUtil.memPutInt(f + 428, LodConfig.WATER_UNDER ? 1 : 0);
		for (int l = 0; l < this.clip.levels; l++) {
			MemoryUtil.memPutInt(f + 144 + l * 16L, this.clip.winTx[l]);
			MemoryUtil.memPutInt(f + 148 + l * 16L, this.clip.winTz[l]);
			MemoryUtil.memPutFloat(f + 336 + l * 4L, this.clip.switchDist[l]);
		}
	}

	double arenaMb() {
		return this.arenaWords * 4 / 1048576.0;
	}

	double usedMb() {
		return this.usedWords * 4 / 1048576.0;
	}

	int pending() {
		return this.jobs.size() + this.results.size();
	}

	void close() {
		this.closed = true;
		for (Thread t : this.workers) t.interrupt();
		this.clip.listener = null;
	}

	long[] buffers() {
		long[] b = new long[RING + 7 + this.buffersLater.size()];
		int i = 0;
		for (long t : this.tableBufs) b[i++] = t;
		b[i++] = this.arenaBuf;
		b[i++] = this.argsBuf;
		b[i++] = this.instBuf;
		b[i++] = this.plantBuf;
		b[i++] = this.survBuf;
		b[i++] = this.horizonBuf;
		b[i++] = this.listBuf;
		for (long[] o : this.buffersLater) b[i++] = o[1];
		return b;
	}
}
