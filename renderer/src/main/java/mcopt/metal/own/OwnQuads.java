package mcopt.metal.own;

import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.util.LightCoordsUtil;
import org.joml.Vector3fc;
import org.lwjgl.system.MemoryUtil;

public final class OwnQuads {
	static final int BUCKETS = 7, QUAD = 64;
	private static final String ORDER_MODE = System.getProperty("mcopt.own.mesh.order", "");
	static final boolean ORDER = ORDER_MODE.equals("plane") || ORDER_MODE.equals("cluster") || ORDER_MODE.equals("cell") || ORDER_MODE.equals("facecell")
		|| ORDER_MODE.equals("cellrun") || ORDER_MODE.equals("mortonrun");
	static final boolean MORTONRUN = ORDER_MODE.equals("mortonrun");
	static final boolean CELLRUN = ORDER_MODE.equals("cellrun");
	static final boolean CELL = ORDER_MODE.equals("cell") || ORDER_MODE.equals("facecell") || CELLRUN;
	static final boolean CLUSTER = ORDER_MODE.equals("cluster") || CELL && !CELLRUN;
	private static final int CELL_SHIFT = Integer.numberOfTrailingZeros(Math.max(2, Math.min(16, Integer.highestOneBit(Integer.getInteger("mcopt.own.mesh.cell", ORDER_MODE.equals("facecell") || ORDER_MODE.equals("cellrun") ? 8 : 4)))));

	static final boolean SUBBOX = Boolean.getBoolean("mcopt.own.mesh.subbox");
	static final boolean BUCKET_CLASS = Boolean.getBoolean("mcopt.own.mesh.bucketClass") || "suffix".equals(System.getProperty("mcopt.own.mesh.bucketClass"));
	static final boolean BUCKET_SUFFIX = "suffix".equals(System.getProperty("mcopt.own.mesh.bucketClass"));

	static int[] subboxes(int[] runs, int[] lo, int[] hi, long @org.jspecify.annotations.Nullable [] tally) {
		int units = runs.length / OwnTerrain.RUN_INTS;
		int[] out = new int[units * 2];
		int[] alo = new int[3], ahi = new int[3], blo = new int[3], bhi = new int[3], best = new int[12];
		for (int u = 0; u < units; u++) {
			int r = u * OwnTerrain.RUN_INTS, first = runs[r], c = runs[r + 1];
			int[] ul = {255, 255, 255}, uh = {0, 0, 0};
			for (int i = first; i < first + c; i++) for (int a = 0; a < 3; a++) {
				ul[a] = Math.min(ul[a], lo[i] >>> (8 * a) & 255);
				uh[a] = Math.max(uh[a], hi[i] >>> (8 * a) & 255);
			}
			long unionArea = area(ul, uh), bestCost = unionArea;
			boolean found = false;
			for (int ax = 0; ax < 3; ax++) {
				for (int sp = ul[ax] + 1; sp <= uh[ax]; sp++) {
					java.util.Arrays.fill(alo, 255);
					java.util.Arrays.fill(blo, 255);
					java.util.Arrays.fill(ahi, 0);
					java.util.Arrays.fill(bhi, 0);
					int na = 0;
					for (int i = first; i < first + c; i++) {
						boolean inA = (lo[i] >>> (8 * ax) & 255) < sp;
						int[] l = inA ? alo : blo, h = inA ? ahi : bhi;
						if (inA) na++;
						for (int a = 0; a < 3; a++) {
							l[a] = Math.min(l[a], lo[i] >>> (8 * a) & 255);
							h[a] = Math.max(h[a], hi[i] >>> (8 * a) & 255);
						}
					}
					if (na == 0 || na == c) continue;
					long cost = area(alo, ahi) + area(blo, bhi);
					if (cost < bestCost) {
						bestCost = cost;
						found = true;
						for (int a = 0; a < 3; a++) {
							best[a] = Math.min(15, alo[a] - ul[a]);
							best[3 + a] = Math.min(15, uh[a] - ahi[a]);
							best[6 + a] = Math.min(15, blo[a] - ul[a]);
							best[9 + a] = Math.min(15, uh[a] - bhi[a]);
						}
					}
				}
			}
			if (found) {
				out[2 * u] = best[0] | best[1] << 4 | (best[2] | best[3] << 4) << 8;
				out[2 * u + 1] = best[4] | best[5] << 4 | best[6] << 8 | best[7] << 12 | best[8] << 16 | best[9] << 20 | best[10] << 24 | best[11] << 28;
			}
			if (tally != null) {
				tally[0]++;
				tally[2] += volume(ul, uh);
				tally[4] += unionArea;
				if (found) {
					tally[1]++;
					int[][] d = decode(out[2 * u], out[2 * u + 1], ul, uh);
					tally[3] += volume(d[0], d[1]) + volume(d[2], d[3]);
					tally[5] += area(d[0], d[1]) + area(d[2], d[3]);
				} else {
					tally[3] += volume(ul, uh);
					tally[5] += unionArea;
				}
			}
		}
		return out;
	}

	static int[][] decode(int w0, int w1, int[] ul, int[] uh) {
		int[] n = {w0 & 15, w0 >>> 4 & 15, w0 >>> 8 & 15, w0 >>> 12 & 15, w1 & 15, w1 >>> 4 & 15,
			w1 >>> 8 & 15, w1 >>> 12 & 15, w1 >>> 16 & 15, w1 >>> 20 & 15, w1 >>> 24 & 15, w1 >>> 28 & 15};
		int[][] d = new int[4][3];
		for (int a = 0; a < 3; a++) {
			d[0][a] = ul[a] + n[a];
			d[1][a] = uh[a] - n[3 + a];
			d[2][a] = ul[a] + n[6 + a];
			d[3][a] = uh[a] - n[9 + a];
		}
		return d;
	}

	private static long area(int[] l, int[] h) {
		long ex = h[0] - l[0], ey = h[1] - l[1], ez = h[2] - l[2];
		return 2 * (ex * ey + ey * ez + ez * ex);
	}

	private static long volume(int[] l, int[] h) {
		return (long) (h[0] - l[0]) * (h[1] - l[1]) * (h[2] - l[2]);
	}

	static boolean cut(int b, float planeA, int loA, float planeB, int loB) {
		if (CELL) return cellOf(loA) != cellOf(loB);
		if (b != 6 && planeA != planeB) return true;
		int ax = (loA & 255) >> CELL_SHIFT, ay = (loA >>> 8 & 255) >> CELL_SHIFT, az = (loA >>> 16 & 255) >> CELL_SHIFT;
		int bx = (loB & 255) >> CELL_SHIFT, by = (loB >>> 8 & 255) >> CELL_SHIFT, bz = (loB >>> 16 & 255) >> CELL_SHIFT;
		return switch (b >> 1) {
			case 0 -> ay != by || az != bz;
			case 1 -> ax != bx || az != bz;
			case 2 -> ax != bx || ay != by;
			default -> ax != bx || ay != by || az != bz;
		};
	}

	static long cellOf(int lo) {
		return spread3((lo & 255) >> CELL_SHIFT) | spread3((lo >>> 8 & 255) >> CELL_SHIFT) << 1 | spread3((lo >>> 16 & 255) >> CELL_SHIFT) << 2;
	}

	static int units(int b, int n, int run, float[] plane, int[] lo, int[] starts) {
		int u = 0;
		for (int k = 0; k < n; ) {
			starts[u++] = k;
			int end = Math.min(n, k + run), i = k + 1;
			if (CLUSTER) while (i < end && !cut(b, plane[i - 1], lo[i - 1], plane[i], lo[i])) i++;
			else i = end;
			k = i;
		}
		starts[u] = n;
		return u;
	}
	private static final ThreadLocal<OwnQuads> LOCAL = ThreadLocal.withInitial(OwnQuads::new);

	public static final class Layer {
		final long[] addr = new long[BUCKETS];
		final int[] cap = new int[BUCKETS], count = new int[BUCKETS];
		final float[][] plane = new float[BUCKETS][];
		final int[][] lo = new int[BUCKETS][], hi = new int[BUCKETS][];
		final int[][] mat = new int[BUCKETS][];
		final byte[][] cls = new byte[BUCKETS][];
		long[][] mk0 = new long[BUCKETS][], mk1 = new long[BUCKETS][], mk2 = new long[BUCKETS][];
		boolean spriteOk;
		int total;
		final long[] qaddr = new long[BUCKETS];
		int stubs;
		boolean hinted;
		int hintGreys, hintTint;

		Layer() {
			for (int b = 0; b < BUCKETS; b++) {
				this.cap[b] = 64;
				this.addr[b] = MemoryUtil.nmemAlloc(64L * QUAD);
				this.plane[b] = new float[64];
				this.lo[b] = new int[64];
				this.hi[b] = new int[64];
				this.mat[b] = new int[64];
				this.cls[b] = new byte[64];
				if (OwnQrec.ON) this.qaddr[b] = MemoryUtil.nmemAlloc(64L * OwnQrec.BYTES);
				if (OwnMergeStat.ON) {
					this.mk0[b] = new long[64];
					this.mk1[b] = new long[64];
					this.mk2[b] = new long[64];
				}
			}
		}

		public int total() {
			return this.total;
		}

		private boolean ordered;
		private long[] keys = new long[64];
		private long scratch;
		private int scratchCap;
		private float[] sortPlane = new float[0];
		private int[] sortLo = new int[0], sortHi = new int[0], sortMat = new int[0];
		private byte[] sortClass = new byte[0];

		void reset() {
			java.util.Arrays.fill(this.count, 0);
			this.total = 0;
			this.ordered = false;
			this.runsMemo = null;
			this.stubs = 0;
			this.hinted = false;
		}

		int stubs() {
			return this.stubs;
		}

		void copyRecsTo(long dst, int rawBase, long stubDst, int stubBase, long arena) {
			this.order();
			int pos = 0, k = 0;
			for (int b = 0; b < BUCKETS; b++) {
				int n = this.count[b];
				if (n == 0) continue;
				long d0 = dst;
				MemoryUtil.memCopy(this.qaddr[b], dst, (long) n * OwnQrec.BYTES);
				if (this.stubs > 0) {
					for (int i = 0; i < n; i++) {
						long r = dst + (long) i * OwnQrec.BYTES;
						if (!OwnQrec.isStub(r)) continue;
						if (rawBase >= 0) {
							MemoryUtil.memPutInt(r + 4, rawBase + pos + i);
						} else {
							MemoryUtil.memCopy(this.addr[b] + (long) i * QUAD, stubDst + (long) k * QUAD, QUAD);
							MemoryUtil.memPutInt(r + 4, stubBase + k++);
						}
					}
				}
				if (OwnQrec.CHECK) OwnQrec.check(d0, this.addr[b], n, arena);
				dst += (long) n * OwnQrec.BYTES;
				pos += n;
			}
		}

		void order() {
			if (!ORDER || this.ordered) return;
			this.ordered = true;
			for (int b = 0; b < BUCKETS; b++) {
				int n = this.count[b];
				if (n < 2) continue;
				if (this.keys.length < n) this.keys = new long[Math.max(n, this.keys.length * 2)];
				if (this.sortPlane.length < n) {
					int capacity = this.keys.length;
					this.sortPlane = new float[capacity];
					this.sortLo = new int[capacity];
					this.sortHi = new int[capacity];
					this.sortMat = new int[capacity];
					this.sortClass = new byte[capacity];
				}
				long[] k = this.keys;
				for (int i = 0; i < n; i++) k[i] = key(b, this.plane[b][i], this.lo[b][i], i);
				java.util.Arrays.sort(k, 0, n);
				if (this.scratchCap < n) {
					this.scratch = this.scratch == 0 ? MemoryUtil.nmemAlloc((long) n * QUAD) : MemoryUtil.nmemRealloc(this.scratch, (long) n * QUAD);
					this.scratchCap = n;
				}
				long src = this.addr[b], dst = this.scratch;
				float[] pl = this.plane[b], npl = this.sortPlane;
				int[] lo = this.lo[b], hi = this.hi[b], nlo = this.sortLo, nhi = this.sortHi, mt = this.mat[b], nmt = this.sortMat;
				byte[] cl = this.cls[b], ncl = this.sortClass;
				long qsrc = this.qaddr[b];
				if (qsrc != 0) {
					long qdst = this.scratch;
					for (int i = 0; i < n; i++) MemoryUtil.memCopy(qsrc + (k[i] & 0xFFFFFF) * OwnQrec.BYTES, qdst + (long) i * OwnQrec.BYTES, OwnQrec.BYTES);
					MemoryUtil.memCopy(qdst, qsrc, (long) n * OwnQrec.BYTES);
				}
				for (int i = 0; i < n; i++) {
					int q = (int) (k[i] & 0xFFFFFF);
					MemoryUtil.memCopy(src + (long) q * QUAD, dst + (long) i * QUAD, QUAD);
					npl[i] = pl[q];
					nlo[i] = lo[q];
					nhi[i] = hi[q];
					nmt[i] = mt[q];
					ncl[i] = cl[q];
				}
				MemoryUtil.memCopy(dst, src, (long) n * QUAD);
				System.arraycopy(npl, 0, pl, 0, n);
				System.arraycopy(nlo, 0, lo, 0, n);
				System.arraycopy(nhi, 0, hi, 0, n);
				System.arraycopy(nmt, 0, mt, 0, n);
				System.arraycopy(ncl, 0, cl, 0, n);
			}
		}

		private long slot(int b) {
			int n = this.count[b];
			if (n == this.cap[b]) {
				int cap = n * 2;
				this.addr[b] = MemoryUtil.nmemRealloc(this.addr[b], (long) cap * QUAD);
				if (this.addr[b] == 0) throw new OutOfMemoryError("own mesh bucket");
				this.plane[b] = java.util.Arrays.copyOf(this.plane[b], cap);
				this.lo[b] = java.util.Arrays.copyOf(this.lo[b], cap);
				this.hi[b] = java.util.Arrays.copyOf(this.hi[b], cap);
				this.mat[b] = java.util.Arrays.copyOf(this.mat[b], cap);
				this.cls[b] = java.util.Arrays.copyOf(this.cls[b], cap);
				if (OwnQrec.ON) {
					this.qaddr[b] = MemoryUtil.nmemRealloc(this.qaddr[b], (long) cap * OwnQrec.BYTES);
					if (this.qaddr[b] == 0) throw new OutOfMemoryError("own mesh qrec bucket");
				}
				if (OwnMergeStat.ON) {
					this.mk0[b] = java.util.Arrays.copyOf(this.mk0[b], cap);
					this.mk1[b] = java.util.Arrays.copyOf(this.mk1[b], cap);
					this.mk2[b] = java.util.Arrays.copyOf(this.mk2[b], cap);
				}
				this.cap[b] = cap;
			}
			return this.addr[b] + (long) n * QUAD;
		}

		void copyTo(long dst) {
			this.order();
			for (int b = 0; b < BUCKETS; b++) {
				long bytes = (long) this.count[b] * QUAD;
				if (bytes == 0) continue;
				MemoryUtil.memCopy(this.addr[b], dst, bytes);
				dst += bytes;
			}
		}

		void countHidden(net.minecraft.world.level.BlockGetter level, int ox, int oy, int oz, long[] out) {
			net.minecraft.core.BlockPos.MutableBlockPos pos = new net.minecraft.core.BlockPos.MutableBlockPos();
			net.minecraft.core.Direction[] facing = {net.minecraft.core.Direction.EAST, net.minecraft.core.Direction.WEST, net.minecraft.core.Direction.UP,
				net.minecraft.core.Direction.DOWN, net.minecraft.core.Direction.SOUTH, net.minecraft.core.Direction.NORTH};
			for (int b = 0; b < 6; b++) {
				int axis = b >> 1;
				for (int i = 0, n = this.count[b]; i < n; i++) {
					float pl = this.plane[b][i];
					if (pl != (float) Math.floor(pl)) continue;
					int lo = this.lo[b][i], hi = this.hi[b][i];
					int lx = (lo & 255) - 16, ly = (lo >>> 8 & 255) - 16, lz = (lo >>> 16) - 16, hx = (hi & 255) - 16, hy = (hi >>> 8 & 255) - 16, hz = (hi >>> 16) - 16;
					if (axis != 0 && hx - lx != 1 || axis != 1 && hy - ly != 1 || axis != 2 && hz - lz != 1) continue;
					int k = (int) pl, front = (b & 1) == 0 ? k : k - 1;
					int x = axis == 0 ? front : lx, y = axis == 1 ? front : ly, z = axis == 2 ? front : lz;
					net.minecraft.world.level.block.state.BlockState nb = level.getBlockState(pos.set(ox + x, oy + y, oz + z));
					net.minecraft.world.phys.shapes.VoxelShape occ = nb.getFaceOcclusionShape(facing[b].getOpposite());
					if (occ == net.minecraft.world.phys.shapes.Shapes.block()) {
						out[0]++;
						out[1 + b]++;
					} else if (out.length > 7 && occ == net.minecraft.world.phys.shapes.Shapes.empty()) {
						out[7]++;
					}
				}
			}
		}

		int[][] arenaBoxes() {
			this.order();
			int[] l = new int[this.total], h = new int[this.total];
			int at = 0;
			for (int b = 0; b < BUCKETS; b++) {
				System.arraycopy(this.lo[b], 0, l, at, this.count[b]);
				System.arraycopy(this.hi[b], 0, h, at, this.count[b]);
				at += this.count[b];
			}
			return new int[][] {l, h};
		}

		void writeMaterials(long dst, byte[] grid) {
			this.order();
			for (int b = 0; b < BUCKETS; b++) {
				int[] mt = this.mat[b];
				for (int i = 0, n = this.count[b]; i < n; i++) {
					int m = mt[i];
					MemoryUtil.memPutByte(dst++, (byte) (grid[m & 4095] & 15 | (m >>> 12 & 15) << 4));
				}
			}
		}

		private int @org.jspecify.annotations.Nullable [] runsMemo;
		private int runsMemoRun;

		int[] runs(int run) {
			if (this.runsMemo != null && this.runsMemoRun == run) return this.runsMemo;
			this.runsMemo = this.runsOnce(run);
			this.runsMemoRun = run;
			return this.runsMemo;
		}

		private int[] runsOnce(int run) {
			this.order();
			int[][] starts = new int[BUCKETS][];
			int[] units = new int[BUCKETS];
			int runCount = 0;
			for (int b = 0; b < BUCKETS; b++) {
				starts[b] = new int[this.count[b] + 1];
				units[b] = OwnQuads.units(b, this.count[b], run, this.plane[b], this.lo[b], starts[b]);
				runCount += units[b];
			}
			int[] runs = new int[runCount * OwnTerrain.RUN_INTS];
			int r = 0, first0 = 0;
			for (int b = 0; b < BUCKETS; b++) {
				int n = this.count[b];
				float[] pl = this.plane[b];
				int[] lo = this.lo[b], hi = this.hi[b];
				for (int u = 0; u < units[b]; u++) {
					int k = starts[b][u], c = starts[b][u + 1] - k;
					float bound = (b & 1) == 0 ? Float.POSITIVE_INFINITY : Float.NEGATIVE_INFINITY;
					for (int i = k; i < k + c; i++) bound = (b & 1) == 0 ? Math.min(bound, pl[i]) : Math.max(bound, pl[i]);
					int fixed = b == 6 ? 0 : (b & 1) == 0 ? (int) Math.floor(bound * 256) : (int) Math.ceil(bound * 256);
					int lx = 255, ly = 255, lz = 255, hx = 0, hy = 0, hz = 0;
					for (int i = k; i < k + c; i++) {
						int l = lo[i], h = hi[i];
						lx = Math.min(lx, l & 255);
						ly = Math.min(ly, l >>> 8 & 255);
						lz = Math.min(lz, l >>> 16);
						hx = Math.max(hx, h & 255);
						hy = Math.max(hy, h >>> 8 & 255);
						hz = Math.max(hz, h >>> 16);
					}
					runs[r++] = first0 + k;
					runs[r++] = c;
					runs[r++] = b;
					runs[r++] = fixed;
					runs[r++] = lx | ly << 8 | lz << 16;
					runs[r++] = hx | hy << 8 | hz << 16;
				}
				first0 += n;
			}
			return runs;
		}

		private void mergeKey(int b, int i, float[] p, int[] abgr, float[] uv, int[] light) {
			int reason = 0;
			int ca = 0, cb = 0;
			long k0 = 0, k1 = 0, k2l = 0;
			if (b == 6) {
				reason = 1;
			} else {
				int axis = b >> 1, ia = axis == 0 ? 1 : 0, ib = axis == 2 ? 1 : 2;
				float amin = Float.POSITIVE_INFINITY, amax = Float.NEGATIVE_INFINITY, bmin = amin, bmax = amax;
				for (int v = 0; v < 4; v++) {
					amin = Math.min(amin, p[v * 3 + ia]);
					amax = Math.max(amax, p[v * 3 + ia]);
					bmin = Math.min(bmin, p[v * 3 + ib]);
					bmax = Math.max(bmax, p[v * 3 + ib]);
				}
				if (amax - amin != 1f || bmax - bmin != 1f || amin != (float) Math.floor(amin) || bmin != (float) Math.floor(bmin) || amin < 0 || bmin < 0 || amin > 15 || bmin > 15) {
					reason = 2;
				} else if (abgr[0] != abgr[1] || abgr[0] != abgr[2] || abgr[0] != abgr[3] || light[0] != light[1] || light[0] != light[2] || light[0] != light[3]) {
					reason = 3;
				} else if (!this.spriteOk) {
					reason = 4;
				} else {
					ca = (int) amin;
					cb = (int) bmin;
					long u00 = -1, u10 = -1, u11 = -1;
					for (int v = 0; v < 4; v++) {
						boolean atA = p[v * 3 + ia] == amax, atB = p[v * 3 + ib] == bmax;
						long uvv = unorm16(uv[v * 2]) | unorm16(uv[v * 2 + 1]) << 16;
						if (!atA && !atB) u00 = uvv;
						else if (atA && !atB) u10 = uvv;
						else if (atA) u11 = uvv;
					}
					k0 = u00 | u10 << 32;
					k1 = u11 | (abgr[0] & 0xFFFFFFFFL) << 32;
					k2l = light[0] & 0xFFFFFFFFL;
				}
			}
			this.mk0[b][i] = k0;
			this.mk1[b][i] = k1;
			this.mk2[b][i] = k2l | (long) ca << 32 | (long) cb << 40 | (long) reason << 48;
		}

		void quad(float[] p, int[] abgr, float[] uv, int[] light) {
			if (OwnGridStat.ON) OwnGridStat.quad(this == OwnGridStat.solidOf(this) ? 0 : 1, p);
			if (OwnQrStat.ON) OwnQrStat.quad(this == OwnGridStat.solidOf(this) ? 0 : 1, p, abgr, uv, light);
			float x0 = p[0], y0 = p[1], z0 = p[2], x1 = p[3], y1 = p[4], z1 = p[5], x2 = p[6], y2 = p[7], z2 = p[8], x3 = p[9], y3 = p[10], z3 = p[11];
			float ux = x1 - x0, uy = y1 - y0, uz = z1 - z0, vx = x2 - x0, vy = y2 - y0, vz = z2 - z0;
			float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
			float wx = x3 - x2, wy = y3 - y2, wz = z3 - z2, tx = x0 - x2, ty = y0 - y2, tz = z0 - z2;
			float mx = wy * tz - wz * ty, my = wz * tx - wx * tz, mz = wx * ty - wy * tx;
			int b;
			float pl;
			if (x0 == x1 && x1 == x2 && x2 == x3 && ny == 0 && nz == 0 && my == 0 && mz == 0 && nx != 0 && Math.signum(nx) == Math.signum(mx)) {
				pl = x0;
				b = nx > 0 ? 0 : 1;
			} else if (y0 == y1 && y1 == y2 && y2 == y3 && nx == 0 && nz == 0 && mx == 0 && mz == 0 && ny != 0 && Math.signum(ny) == Math.signum(my)) {
				pl = y0;
				b = ny > 0 ? 2 : 3;
			} else if (z0 == z1 && z1 == z2 && z2 == z3 && nx == 0 && ny == 0 && mx == 0 && my == 0 && nz != 0 && Math.signum(nz) == Math.signum(mz)) {
				pl = z0;
				b = nz > 0 ? 4 : 5;
			} else {
				pl = 0;
				b = 6;
			}
			long d = this.slot(b);
			int i = this.count[b]++;
			this.total++;
			this.runsMemo = null;
			this.plane[b][i] = pl;
			if (OwnMergeStat.ON) this.mergeKey(b, i, p, abgr, uv, light);
			if (b < 6) {
				this.cls[b][i] = (byte) b;
			} else {
				float ax = Math.abs(nx), ay = Math.abs(ny), az = Math.abs(nz);
				this.cls[b][i] = (byte) (ax >= ay && ax >= az ? (nx >= 0 ? 0 : 1) : ay >= az ? (ny >= 0 ? 2 : 3) : (nz >= 0 ? 4 : 5));
			}
			float minX = Math.min(Math.min(x0, x1), Math.min(x2, x3)), minY = Math.min(Math.min(y0, y1), Math.min(y2, y3)), minZ = Math.min(Math.min(z0, z1), Math.min(z2, z3));
			float maxX = Math.max(Math.max(x0, x1), Math.max(x2, x3)), maxY = Math.max(Math.max(y0, y1), Math.max(y2, y3)), maxZ = Math.max(Math.max(z0, z1), Math.max(z2, z3));
			this.lo[b][i] = OwnTerrain.box(minX, true) | OwnTerrain.box(minY, true) << 8 | OwnTerrain.box(minZ, true) << 16;
			this.hi[b][i] = OwnTerrain.box(maxX, false) | OwnTerrain.box(maxY, false) << 8 | OwnTerrain.box(maxZ, false) << 16;
			if (OwnMaterials.ON) {
				float cx = 0, cy = 0, cz = 0, ylo = Float.POSITIVE_INFINITY, yhi = Float.NEGATIVE_INFINITY;
				for (int v = 0; v < 4; v++) {
					float yv = p[v * 3 + 1];
					cx += p[v * 3];
					cy += yv;
					cz += p[v * 3 + 2];
					ylo = Math.min(ylo, yv);
					yhi = Math.max(yhi, yv);
				}
				cx *= 0.25F;
				cy *= 0.25F;
				cz *= 0.25F;
				final float e = 1.0F / 32.0F;
				switch (b) {
					case 0 -> cx -= e;
					case 1 -> cx += e;
					case 2 -> cy -= e;
					case 3 -> cy += e;
					case 4 -> cz -= e;
					case 5 -> cz += e;
					default -> {
					}
				}
				int bx = Math.clamp((int) Math.floor(cx), 0, 15), by = Math.clamp((int) Math.floor(cy), 0, 15), bz = Math.clamp((int) Math.floor(cz), 0, 15);
				float mid = (ylo + yhi) * 0.5F + 1e-4F;
				int up = 0;
				for (int v = 0; v < 4; v++) if (p[v * 3 + 1] > mid) up |= 1 << v;
				this.mat[b][i] = by << 8 | bz << 4 | bx | up << 12;
			}
			for (int v = 0; v < 4; v++) {
				long a = d + v * 16L;
				int l = light[v];
				MemoryUtil.memPutLong(a, (long) fixed(p[v * 3]) | (long) fixed(p[v * 3 + 1]) << 16 | (long) fixed(p[v * 3 + 2]) << 32
					| (long) clampByte((short) l) << 48 | (long) clampByte((short) (l >>> 16)) << 56);
				MemoryUtil.memPutLong(a + 8, (abgr[v] & 0xFFFFFFFFL) | (long) unorm16(uv[v * 2]) << 32 | (long) unorm16(uv[v * 2 + 1]) << 48);
			}
			if (OwnQrec.ON) {
				long r = this.qaddr[b] + (long) i * OwnQrec.BYTES;
				if (!OwnQrec.write(r, d, this.hinted, this.hintGreys, this.hintTint)) {
					OwnQrec.stub(r, 0);
					this.stubs++;
				}
				this.hinted = false;
			}
		}
	}

	static long key(int b, float plane, int lo, int i) {
		if (MORTONRUN) return (spread3(lo & 255) | spread3(lo >>> 8 & 255) << 1 | spread3(lo >>> 16 & 255) << 2) << 24 | i;
		if (CELL) {
			int pk = b == 6 ? 0 : Math.max(0, Math.min(16383, Math.round((plane + 32f) * 256f)));
			return cellOf(lo) << 38 | (long) pk << 24 | i;
		}
		int x = lo & 255, y = lo >>> 8 & 255, z = lo >>> 16 & 255;
		long cell;
		int pk;
		if (b == 6) {
			pk = 0;
			cell = spread3(x) | spread3(y) << 1 | spread3(z) << 2;
		} else {
			pk = Math.max(0, Math.min(32767, Math.round((plane + 64f) * 256f)));
			int u = b < 2 ? y : x, v = b < 4 && b >= 2 ? z : b < 2 ? z : y;
			cell = spread2(u) | spread2(v) << 1;
		}
		return (long) pk << 48 | cell << 24 | i;
	}

	private static long spread2(int v) {
		long x = v & 255;
		x = (x | x << 4) & 0x0F0F;
		x = (x | x << 2) & 0x3333;
		x = (x | x << 1) & 0x5555;
		return x;
	}

	private static long spread3(int v) {
		long x = v & 255;
		x = (x | x << 16) & 0xFF0000FFL;
		x = (x | x << 8) & 0x0F00F00FL;
		x = (x | x << 4) & 0xC30C30C3L;
		x = (x | x << 2) & 0x49249249L;
		return x;
	}

	private static long fixed(float p) {
		if (OwnPosTable.ON) return OwnPosTable.encode(p);
		return Math.max(0, Math.min(65535, Math.round((p + 8f) * 2048f)));
	}

	private static long unorm16(float u) {
		return Math.max(0, Math.min(65535, Math.round(u * 65536f)));
	}

	private static long clampByte(short s) {
		return Math.max(0, Math.min(255, s));
	}

	public final Layer[] layers = {new Layer(), new Layer()};
	private final float[] p = new float[12], uv = new float[8];
	private final int[] abgr = new int[4], light = new int[4];
	public final VertexConsumer[] fluid = {new FluidSink(0), new FluidSink(1)};
	private int fluidVertex;
	private boolean hinted;
	private int hintGreys, hintTint;

	void hint(int c0, int c1, int c2, int c3, int tint) {
		this.hinted = grey(c0) && grey(c1) && grey(c2) && grey(c3);
		this.hintGreys = c0 & 255 | (c1 & 255) << 8 | (c2 & 255) << 16 | (c3 & 255) << 24;
		this.hintTint = toABGR(tint);
	}

	private static boolean grey(int c) {
		return c >>> 24 == 255 && (c & 255) == (c >> 8 & 255) && (c & 255) == (c >> 16 & 255);
	}

	public static OwnQuads get() {
		OwnQuads q = LOCAL.get();
		q.reset();
		return q;
	}

	static OwnQuads peek() {
		return LOCAL.get();
	}

	void reset() {
		this.layers[0].reset();
		this.layers[1].reset();
		this.fluidVertex = 0;
		this.groups = null;
	}

	OwnTieGroups.@org.jspecify.annotations.Nullable Result groups;

	public OwnTieGroups.@org.jspecify.annotations.Nullable Result groups() {
		return this.groups;
	}

	public void put(int layer, float x, float y, float z, BakedQuad quad, QuadInstance instance) {
		int emission = quad.materialInfo().lightEmission();
		float[] p = this.p, uv = this.uv;
		int[] abgr = this.abgr, light = this.light;
		for (int v = 0; v < 4; v++) {
			Vector3fc pos = quad.position(v);
			p[v * 3] = pos.x() + x;
			p[v * 3 + 1] = pos.y() + y;
			p[v * 3 + 2] = pos.z() + z;
			long packedUv = quad.packedUV(v);
			uv[v * 2] = Float.intBitsToFloat((int) (packedUv >> 32));
			uv[v * 2 + 1] = Float.intBitsToFloat((int) packedUv);
			abgr[v] = toABGR(instance.getColor(v));
			light[v] = LightCoordsUtil.lightCoordsWithEmission(instance.getLightCoords(v), emission);
		}
		if (OwnMergeStat.ON) this.layers[layer].spriteOk = !quad.materialInfo().sprite().contents().isAnimated();
		if (OwnQrec.ON) {
			Layer l = this.layers[layer];
			l.hinted = this.hinted;
			l.hintGreys = this.hintGreys;
			l.hintTint = this.hintTint;
			this.hinted = false;
		}
		this.layers[layer].quad(p, abgr, uv, light);
	}

	private static int toABGR(int c) {
		return c & 0xFF00FF00 | (c & 0xFF0000) >> 16 | (c & 0xFF) << 16;
	}

	private void fluidVertex(int layer, float x, float y, float z, int color, float u, float v, int lightCoords) {
		int k = this.fluidVertex;
		this.p[k * 3] = x;
		this.p[k * 3 + 1] = y;
		this.p[k * 3 + 2] = z;
		this.uv[k * 2] = u;
		this.uv[k * 2 + 1] = v;
		this.abgr[k] = toABGR(color);
		this.light[k] = lightCoords;
		if (k == 3) {
			this.fluidVertex = 0;
			this.layers[layer].spriteOk = false;
			this.layers[layer].quad(this.p, this.abgr, this.uv, this.light);
		} else {
			this.fluidVertex = k + 1;
		}
	}

	private final class FluidSink implements VertexConsumer {
		private final int layer;

		FluidSink(int layer) {
			this.layer = layer;
		}

		@Override
		public void addVertex(float x, float y, float z, int color, float u, float v, int overlayCoords, int lightCoords, float nx, float ny, float nz) {
			OwnQuads.this.fluidVertex(this.layer, x, y, z, color, u, v, lightCoords);
		}

		private static UnsupportedOperationException unsupported() {
			return new UnsupportedOperationException("own mesh fluid sink: BLOCK-format addVertex only");
		}

		@Override
		public VertexConsumer addVertex(float x, float y, float z) {
			throw unsupported();
		}

		@Override
		public VertexConsumer setColor(int r, int g, int b, int a) {
			throw unsupported();
		}

		@Override
		public VertexConsumer setColor(int color) {
			throw unsupported();
		}

		@Override
		public VertexConsumer setUv(float u, float v) {
			throw unsupported();
		}

		@Override
		public VertexConsumer setUv1(int u, int v) {
			throw unsupported();
		}

		@Override
		public VertexConsumer setUv2(int u, int v) {
			throw unsupported();
		}

		@Override
		public VertexConsumer setUv3(float u, float v) {
			throw unsupported();
		}

		@Override
		public VertexConsumer setNormal(float x, float y, float z) {
			throw unsupported();
		}

		@Override
		public VertexConsumer setLineWidth(float width) {
			throw unsupported();
		}
	}
}
