// Modified for Russet: the block light over a cell's top in its crown word's free bits (glowBits).
package mcopt.metal.lod;

import org.lwjgl.system.MemoryUtil;

final class LodClip {
	static final int TILE = 64;
	private static final int MARGIN = 4;
	final int n, logN, levels, tilesPerSide;
	final long geomBuf, colorBuf, mipBuf, crownBuf, texBuf;
	final long geom, color, mip, crown, tex;
	final int crownLevels;
	final boolean plants = LodConfig.PLANTS;
	final long levelWords, levelMipWords;
	final int[] winTx, winTz;
	final long[][] slotKey;
	final short[][] slotMin, slotMax;
	final float[] switchDist;
	long version;
	final long[][] slotVer;
	static final boolean PAIRS = "pair".equals(System.getProperty("mcopt.lod.publish", "")) || LodPublish.ON;
	@org.jspecify.annotations.Nullable LodPublish publisher;
	final long[][] epoch;
	final java.util.concurrent.atomic.AtomicLongArray[] writing;
	private final java.util.HashMap<Long, Snapshot> staged = new java.util.HashMap<>();

	static final class Snapshot {
		final int level, tx, tz;
		final int[] g = new int[TILE * TILE], c = new int[TILE * TILE];
		final int @org.jspecify.annotations.Nullable [] cr, runs, tw, pl;
		long version;
		long epoch;

		Snapshot(int level, int tx, int tz, boolean crowns, boolean texture, boolean plants) {
			this.level = level;
			this.tx = tx;
			this.tz = tz;
			this.cr = crowns ? new int[TILE * TILE] : null;
			this.runs = crowns ? new int[TILE * TILE] : null;
			this.tw = texture ? new int[TILE * TILE] : null;
			this.pl = plants ? new int[2 * TILE * TILE] : null;
		}

		Snapshot copy() {
			Snapshot s = new Snapshot(this.level, this.tx, this.tz, this.cr != null, this.tw != null, this.pl != null);
			System.arraycopy(this.g, 0, s.g, 0, this.g.length);
			System.arraycopy(this.c, 0, s.c, 0, this.c.length);
			if (this.cr != null) System.arraycopy(this.cr, 0, s.cr, 0, this.cr.length);
			if (this.runs != null) System.arraycopy(this.runs, 0, s.runs, 0, this.runs.length);
			if (this.tw != null) System.arraycopy(this.tw, 0, s.tw, 0, this.tw.length);
			if (this.pl != null) System.arraycopy(this.pl, 0, s.pl, 0, this.pl.length);
			s.version = this.version;
			s.epoch = this.epoch;
			return s;
		}
	}

	interface Listener {
		void tilePut(int level, int tx, int tz, boolean refreshed);

		void tileCleared(int level, int slot);

		default void tileStaged(Snapshot s) {
		}
	}

	@org.jspecify.annotations.Nullable Listener listener;

	LodClip(long ctx, int n, double reach) {
		if (Integer.bitCount(n) != 1 || n < 256) throw new IllegalArgumentException("mcopt.lod.n must be a power of two >= 256: " + n);
		this.n = n;
		this.logN = Integer.numberOfTrailingZeros(n);
		this.tilesPerSide = n / TILE;
		int lv = 1;
		while ((n / 2.0 - TILE - MARGIN) * (1L << (lv - 1)) < reach && lv < 12) lv++;
		this.levels = lv;
		this.switchDist = new float[lv];
		for (int l = 0; l < lv; l++) this.switchDist[l] = (float) ((n / 2.0 - TILE - MARGIN) * (1L << l));
		this.levelWords = (long) n * n;
		this.levelMipWords = (long) (n / 8) * (n / 8);
		this.geomBuf = buffer(ctx, this.levelWords * 4 * lv);
		this.colorBuf = buffer(ctx, this.levelWords * 4 * lv);
		this.mipBuf = LodNative.buffer(ctx, this.mipBytes(lv));
		this.crownLevels = Math.min(lv, Math.max(0, LodConfig.CROWN_LEVELS));
		this.crownBuf = buffer(ctx, Math.max(16, this.levelWords * 8 * this.crownLevels));
		this.crown = LodNative.contents(this.crownBuf);
		long texBytes = LodConfig.TEXTURES ? this.levelWords * 4 * (this.plants ? 3 : 1) : 16;
		this.texBuf = buffer(ctx, texBytes);
		this.tex = LodNative.contents(this.texBuf);
		MemoryUtil.memSet(this.tex, 0, texBytes);
		this.geom = LodNative.contents(this.geomBuf);
		this.color = LodNative.contents(this.colorBuf);
		this.mip = LodNative.contents(this.mipBuf);
		MemoryUtil.memSet(this.geom, 0, this.levelWords * 4 * lv);
		MemoryUtil.memSet(this.color, 0, this.levelWords * 4 * lv);
		MemoryUtil.memSet(this.mip, 0, this.mipBytes(lv));
		this.winTx = new int[lv];
		this.winTz = new int[lv];
		java.util.Arrays.fill(this.winTx, Integer.MIN_VALUE);
		this.slotKey = new long[lv][this.tilesPerSide * this.tilesPerSide];
		this.slotMin = new short[lv][this.tilesPerSide * this.tilesPerSide];
		this.slotMax = new short[lv][this.tilesPerSide * this.tilesPerSide];
		for (long[] k : this.slotKey) java.util.Arrays.fill(k, -1L);
		this.slotVer = new long[lv][this.tilesPerSide * this.tilesPerSide];
		this.epoch = new long[lv][this.tilesPerSide * this.tilesPerSide];
		this.writing = new java.util.concurrent.atomic.AtomicLongArray[lv];
		for (int l = 0; l < lv; l++) this.writing[l] = new java.util.concurrent.atomic.AtomicLongArray(this.tilesPerSide * this.tilesPerSide);
	}

	private void beginWrite(int level, int slot) {
		this.writing[level].incrementAndGet(slot);
	}

	private void endWrite(int level, int slot) {
		this.writing[level].incrementAndGet(slot);
	}

	boolean hasStaged(int level, int tx, int tz) {
		return this.staged.containsKey(LodTile.key(level, tx, tz));
	}

	private boolean staging() {
		return PAIRS && this.listener != null;
	}

	static final boolean TRACKED = Boolean.getBoolean("mcopt.lod.trackedClip") || LodPublish.ON;

	private static long buffer(long ctx, long size) {
		return TRACKED ? LodSeam.trackedBuffer(ctx, size) : LodNative.buffer(ctx, size);
	}

	private long mipBytes(int lv) {
		return (this.levelMipWords + (long) this.tilesPerSide * this.tilesPerSide) * 2 * lv;
	}

	long tileMaxOffset(int level) {
		return this.levels * this.levelMipWords + (long) level * this.tilesPerSide * this.tilesPerSide;
	}

	long runsOffset() {
		return this.levelWords * this.crownLevels;
	}

	long bytes() {
		return this.levelWords * 8 * this.levels + this.mipBytes(this.levels) + this.levelWords * 8 * this.crownLevels
			+ (LodConfig.TEXTURES ? this.levelWords * 4 * (this.plants ? 3 : 1) : 0);
	}

	int span(int level) {
		return TILE << level;
	}

	boolean recenter(double camX, double camZ) {
		boolean moved = false;
		int h = this.tilesPerSide / 2;
		for (int l = 0; l < this.levels; l++) {
			int span = this.span(l);
			int tx = Math.floorDiv((int) Math.floor(camX), span) - h, tz = Math.floorDiv((int) Math.floor(camZ), span) - h;
			if (tx == this.winTx[l] && tz == this.winTz[l]) continue;
			moved = true;
			this.winTx[l] = tx;
			this.winTz[l] = tz;
			long[] keys = this.slotKey[l];
			for (int s = 0; s < keys.length; s++) {
				long k = keys[s];
				if (k == -1L) continue;
				if (!this.inWindow(l, LodTile.txOf(k), LodTile.tzOf(k))) this.clear(l, s);
			}
		}
		return moved;
	}

	boolean inWindow(int level, int tx, int tz) {
		int x = tx - this.winTx[level], z = tz - this.winTz[level];
		return x >= 0 && z >= 0 && x < this.tilesPerSide && z < this.tilesPerSide;
	}

	int slot(int tx, int tz) {
		int m = this.tilesPerSide - 1;
		return (tz & m) * this.tilesPerSide + (tx & m);
	}

	boolean resident(int level, int tx, int tz) {
		return this.slotKey[level][this.slot(tx, tz)] == LodTile.key(level, tx, tz);
	}

	private long cellOffset(int level, int cx, int cz) {
		int m = this.n - 1;
		return level * this.levelWords + ((long) (cz & m) << this.logN) + (cx & m);
	}

	private void clear(int level, int slot) {
		int tx = slot % this.tilesPerSide, tz = slot / this.tilesPerSide;
		if (this.slotKey[level][slot] != -1L) this.staged.remove(this.slotKey[level][slot]);
		this.epoch[level][slot]++;
		this.beginWrite(level, slot);
		for (int z = 0; z < TILE; z++) {
			long at = this.cellOffset(level, tx * TILE, tz * TILE + z) * 4;
			MemoryUtil.memSet(this.geom + at, 0, TILE * 4);
		}
		for (int z = 0; z < TILE / 8; z++) {
			long at = (level * this.levelMipWords + (long) (tz * (TILE / 8) + z) * (this.n / 8) + tx * (TILE / 8)) * 2;
			MemoryUtil.memSet(this.mip + at, 0, (TILE / 8) * 2);
		}
		MemoryUtil.memPutShort(this.mip + (this.tileMaxOffset(level) + slot) * 2, (short) 0);
		this.slotKey[level][slot] = -1L;
		this.slotVer[level][slot]++;
		this.endWrite(level, slot);
		this.version++;
		Listener l = this.listener;
		if (l != null) l.tileCleared(level, slot);
	}

	boolean put(int level, int tx, int tz, int[] g, int[] c, int @org.jspecify.annotations.Nullable [] cr, int @org.jspecify.annotations.Nullable [] tw,
		int @org.jspecify.annotations.Nullable [] runs, int @org.jspecify.annotations.Nullable [] pl) {
		if (!this.inWindow(level, tx, tz)) return false;
		this.staged.remove(LodTile.key(level, tx, tz));
		this.epoch[level][this.slot(tx, tz)]++;
		this.write(level, tx, tz, g, c, cr, tw, runs, pl);
		Listener l = this.listener;
		if (l != null) l.tilePut(level, tx, tz, false);
		return true;
	}

	boolean publish(Snapshot s) {
		if (!this.resident(s.level, s.tx, s.tz) || s.epoch != this.epoch[s.level][this.slot(s.tx, s.tz)]) return false;
		if (this.publisher != null) this.writeGpu(s.level, s.tx, s.tz, s.g, s.c, s.cr, s.tw, s.runs, s.pl);
		else this.write(s.level, s.tx, s.tz, s.g, s.c, s.cr, s.tw, s.runs, s.pl);
		long key = LodTile.key(s.level, s.tx, s.tz);
		Snapshot st = this.staged.get(key);
		if (st != null && st.version == s.version) this.staged.remove(key);
		return true;
	}

	private void writeGpu(int level, int tx, int tz, int[] g, int[] c, int @org.jspecify.annotations.Nullable [] cr, int @org.jspecify.annotations.Nullable [] tw,
		int @org.jspecify.annotations.Nullable [] runs, int @org.jspecify.annotations.Nullable [] pl) {
		LodPublish p = this.publisher;
		int s0 = this.slot(tx, tz);
		this.beginWrite(level, s0);
		boolean crowns = level < this.crownLevels, texture = level == 0 && LodConfig.TEXTURES, plant = level == 0 && this.plants && pl != null;
		int[] gm = new int[TILE * TILE];
		for (int i = 0; i < gm.length; i++) {
			int gw = g[i];
			if (!crowns || cr == null) gw &= ~GEOM_CROWN_BITS;
			if (!plant) gw &= ~GEOM_PLANT_BITS;
			gm[i] = gw;
		}
		int[] crw = crowns ? (cr != null ? cr : new int[TILE * TILE]) : null;
		int[] tww = texture ? (tw != null ? tw : new int[TILE * TILE]) : null;
		p.stage(level, tx, tz, gm, c, crw, crowns ? runs : null, tww, plant ? pl : null);
		this.slotKey[level][s0] = LodTile.key(level, tx, tz);
		this.mips(level, tx, tz, g);
		this.slotVer[level][s0]++;
		this.version++;
		this.endWrite(level, s0);
	}

	private void write(int level, int tx, int tz, int[] g, int[] c, int @org.jspecify.annotations.Nullable [] cr, int @org.jspecify.annotations.Nullable [] tw,
		int @org.jspecify.annotations.Nullable [] runs, int @org.jspecify.annotations.Nullable [] pl) {
		int x0 = tx * TILE, z0 = tz * TILE;
		int s0 = this.slot(tx, tz);
		this.beginWrite(level, s0);
		boolean crowns = level < this.crownLevels, texture = level == 0 && LodConfig.TEXTURES, plant = level == 0 && this.plants && pl != null;
		for (int z = 0; z < TILE; z++) {
			long at = this.cellOffset(level, x0, z0 + z) * 4;
			for (int x = 0; x < TILE; x++) {
				int i = z * TILE + x;
				if (crowns) {
					MemoryUtil.memPutInt(this.crown + at + x * 4L, cr != null ? cr[i] : 0);
					MemoryUtil.memPutInt(this.crown + this.runsOffset() * 4 + at + x * 4L, runs != null ? runs[i] : 0);
				}
				if (texture) MemoryUtil.memPutInt(this.tex + at + x * 4L, tw != null ? tw[i] : 0);
				if (plant) {
					MemoryUtil.memPutInt(this.tex + this.levelWords * 4 + at + x * 4L, pl[i]);
					MemoryUtil.memPutInt(this.tex + this.levelWords * 8 + at + x * 4L, pl[TILE * TILE + i]);
				}
				int gw = g[i];
				if (!crowns || cr == null) gw &= ~GEOM_CROWN_BITS;
				if (!plant) gw &= ~GEOM_PLANT_BITS;
				MemoryUtil.memPutInt(this.geom + at + x * 4L, gw);
				MemoryUtil.memPutInt(this.color + at + x * 4L, c[i]);
			}
		}
		int s = this.slot(tx, tz);
		this.slotKey[level][s] = LodTile.key(level, tx, tz);
		this.mips(level, tx, tz, g);
		this.slotVer[level][s]++;
		this.version++;
		this.endWrite(level, s0);
	}

	private void mips(int level, int tx, int tz, int[] g) {
		int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
		for (int bz = 0; bz < TILE / 8; bz++) {
			for (int bx = 0; bx < TILE / 8; bx++) {
				int max = 0;
				for (int z = 0; z < 8; z++) {
					for (int x = 0; x < 8; x++) {
						int w = g[(bz * 8 + z) * TILE + bx * 8 + x];
						if ((w & GEOM_VALID) == 0) continue;
						int y = (w & 0xFFF) + (w >>> GEOM_PLANT_SHIFT & 3);
						max = Math.max(max, y);
						lo = Math.min(lo, y);
						hi = Math.max(hi, y);
					}
				}
				int mbx = ((tx * (TILE / 8)) + bx) & (this.n / 8 - 1), mbz = ((tz * (TILE / 8)) + bz) & (this.n / 8 - 1);
				MemoryUtil.memPutShort(this.mip + (level * this.levelMipWords + (long) mbz * (this.n / 8) + mbx) * 2, (short) max);
			}
		}
		int s = this.slot(tx, tz);
		this.slotMin[level][s] = (short) (lo == Integer.MAX_VALUE ? 0 : lo - 512);
		this.slotMax[level][s] = (short) (hi == Integer.MIN_VALUE ? -512 : hi - 512);
		MemoryUtil.memPutShort(this.mip + (this.tileMaxOffset(level) + s) * 2, (short) (hi == Integer.MIN_VALUE ? 0 : hi));
	}

	void read(int level, int tx, int tz, int[] g, int[] c, int @org.jspecify.annotations.Nullable [] cr) {
		this.read(level, tx, tz, g, c, cr, null, null, null);
	}

	void read(int level, int tx, int tz, int[] g, int[] c, int @org.jspecify.annotations.Nullable [] cr, int @org.jspecify.annotations.Nullable [] tw,
		int @org.jspecify.annotations.Nullable [] runs, int @org.jspecify.annotations.Nullable [] pl) {
		int x0 = tx * TILE, z0 = tz * TILE;
		boolean crowns = level < this.crownLevels && cr != null, texture = level == 0 && LodConfig.TEXTURES && tw != null;
		boolean plant = level == 0 && this.plants && pl != null;
		for (int z = 0; z < TILE; z++) {
			long at = this.cellOffset(level, x0, z0 + z) * 4;
			for (int x = 0; x < TILE; x++) {
				g[z * TILE + x] = MemoryUtil.memGetInt(this.geom + at + x * 4L);
				c[z * TILE + x] = MemoryUtil.memGetInt(this.color + at + x * 4L);
				if (crowns) cr[z * TILE + x] = MemoryUtil.memGetInt(this.crown + at + x * 4L);
				if (crowns && runs != null) runs[z * TILE + x] = MemoryUtil.memGetInt(this.crown + this.runsOffset() * 4 + at + x * 4L);
				if (texture) tw[z * TILE + x] = MemoryUtil.memGetInt(this.tex + at + x * 4L);
				if (plant) {
					pl[z * TILE + x] = MemoryUtil.memGetInt(this.tex + this.levelWords * 4 + at + x * 4L);
					pl[TILE * TILE + z * TILE + x] = MemoryUtil.memGetInt(this.tex + this.levelWords * 8 + at + x * 4L);
				}
			}
		}
	}

	int[] cellWords(int cx, int cz) {
		long at = this.cellOffset(0, cx, cz) * 4;
		int[] w = new int[7];
		w[0] = MemoryUtil.memGetInt(this.geom + at);
		w[1] = MemoryUtil.memGetInt(this.color + at);
		if (this.crownLevels > 0) {
			w[2] = MemoryUtil.memGetInt(this.crown + at);
			w[3] = MemoryUtil.memGetInt(this.crown + this.runsOffset() * 4 + at);
		}
		if (LodConfig.TEXTURES) w[4] = MemoryUtil.memGetInt(this.tex + at);
		if (this.plants) {
			w[5] = MemoryUtil.memGetInt(this.tex + this.levelWords * 4 + at);
			w[6] = MemoryUtil.memGetInt(this.tex + this.levelWords * 8 + at);
		}
		return w;
	}

	void putCell(int level, int cx, int cz, int g, int c, int cr, int tw, int runs, int plantA, int plantB) {
		if (this.staging()) {
			this.putStaged(level, cx, cz, g, c, cr, tw, runs, plantA, plantB);
			return;
		}
		int ps = this.slot(Math.floorDiv(cx, TILE), Math.floorDiv(cz, TILE));
		this.beginWrite(level, ps);
		long at = this.cellOffset(level, cx, cz) * 4;
		boolean crowns = level < this.crownLevels;
		if (crowns) MemoryUtil.memPutInt(this.crown + this.runsOffset() * 4 + at, runs);
		if (level == 0 && LodConfig.TEXTURES) MemoryUtil.memPutInt(this.tex + at, tw);
		if (level == 0 && this.plants) {
			MemoryUtil.memPutInt(this.tex + this.levelWords * 4 + at, plantA);
			MemoryUtil.memPutInt(this.tex + this.levelWords * 8 + at, plantB);
		} else {
			g &= ~GEOM_PLANT_BITS;
		}
		if (crowns) MemoryUtil.memPutInt(this.crown + at, cr);
		else g &= ~GEOM_CROWN_BITS;
		MemoryUtil.memPutInt(this.geom + at, g);
		MemoryUtil.memPutInt(this.color + at, c);
		this.endWrite(level, ps);
	}

	private void putStaged(int level, int cx, int cz, int g, int c, int cr, int tw, int runs, int plantA, int plantB) {
		int tx = Math.floorDiv(cx, TILE), tz = Math.floorDiv(cz, TILE);
		long key = LodTile.key(level, tx, tz);
		Snapshot s = this.staged.get(key);
		boolean crowns = level < this.crownLevels;
		if (s == null) {
			s = new Snapshot(level, tx, tz, crowns, level == 0 && LodConfig.TEXTURES, level == 0 && this.plants);
			this.read(level, tx, tz, s.g, s.c, s.cr, s.tw, s.runs, s.pl);
			s.epoch = this.epoch[level][this.slot(tx, tz)];
			this.staged.put(key, s);
		}
		int i = (cz - tz * TILE) * TILE + (cx - tx * TILE);
		if (crowns) s.runs[i] = runs;
		if (s.tw != null) s.tw[i] = tw;
		if (s.pl != null) {
			s.pl[i] = plantA;
			s.pl[TILE * TILE + i] = plantB;
		} else {
			g &= ~GEOM_PLANT_BITS;
		}
		if (crowns) s.cr[i] = cr;
		else g &= ~GEOM_CROWN_BITS;
		s.g[i] = g;
		s.c[i] = c;
	}

	private final int[] scratchG = new int[TILE * TILE], scratchC = new int[TILE * TILE];

	void refresh(int level, int tx, int tz) {
		if (this.staging()) {
			Snapshot s = this.staged.get(LodTile.key(level, tx, tz));
			Listener l = this.listener;
			if (s == null || l == null) return;
			s.version++;
			l.tileStaged(s.copy());
			return;
		}
		this.read(level, tx, tz, this.scratchG, this.scratchC, null);
		this.mips(level, tx, tz, this.scratchG);
		this.slotVer[level][this.slot(tx, tz)]++;
		this.version++;
		Listener l = this.listener;
		if (l != null) l.tilePut(level, tx, tz, true);
	}

	static final int GEOM_WET = 0x1000, GEOM_VALID = 0x2000, GEOM_CROWN = 0x4000, GEOM_FRINGE = 1 << 21;
	static final int GEOM_CLEAR = 1 << 24;
	static final int GEOM_DEPTH_SHIFT = 25;

	static int depthBits(int depth) {
		return (depth & 127) << GEOM_DEPTH_SHIFT;
	}
	static final int GEOM_CROWN_BITS = GEOM_CROWN | 63 << 15;
	static final int GEOM_PLANT_SHIFT = 22, GEOM_PLANT_BITS = 3 << GEOM_PLANT_SHIFT;

	static int plantBits(int blocks) {
		return Math.clamp(blocks, 0, 3) << GEOM_PLANT_SHIFT;
	}

	static int plantWordA(int lower, int upper, int blocks) {
		return (lower & 1023) | (upper & 1023) << 10 | (Math.clamp(blocks, 1, 3) - 1) << 20;
	}

	static int plantWordB(int color, double ox, double oy, double oz) {
		int nx = (int) Math.clamp(Math.round((ox + 0.25) * 30), 0, 15), nz = (int) Math.clamp(Math.round((oz + 0.25) * 30), 0, 15);
		int ny = (int) Math.clamp(Math.round(-oy * 60), 0, 15);
		return rgb565(color) | nx << 16 | nz << 20 | ny << 24;
	}

	static int geomWord(int topY, boolean wet) {
		return ((topY + 512) & 0xFFF) | (wet ? GEOM_WET : 0) | GEOM_VALID;
	}

	static int crownGeomWord(int crownTopY, int thickness, boolean wet) {
		return geomWord(crownTopY, wet) | GEOM_CROWN | Math.clamp(thickness, 1, 63) << 15;
	}

	static int crownWord(int groundY, int groundColor) {
		return ((groundY + 512) & 0xFFF) | rgb565(groundColor) << 12;
	}

	static int glowBits(int light) {
		return (light & 15) << 28;
	}

	static int belowWord(int belowColor) {
		return rgb565(belowColor) << 12;
	}

	static int rgb565(int rgb) {
		int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
		return ((r * 31 + 127) / 255) << 11 | ((g * 63 + 127) / 255) << 5 | ((b * 31 + 127) / 255);
	}

	static int colorWord(int top, int side) {
		return rgb565(top) | rgb565(side) << 16;
	}

	void free() {
		LodNative.release(this.texBuf);
		LodNative.release(this.crownBuf);
		LodNative.release(this.geomBuf);
		LodNative.release(this.colorBuf);
		LodNative.release(this.mipBuf);
	}
}
