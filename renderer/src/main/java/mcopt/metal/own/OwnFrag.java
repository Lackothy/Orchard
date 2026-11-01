package mcopt.metal.own;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import mcopt.metal.MetalBridge;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

final class OwnFrag {
	static final int VANILLA = 0, CUTFIRST = 1, BPRE = 2, SPLIT = 3;
	private static final int SIZE_QUADS = Math.max(1, Integer.highestOneBit(Math.max(1, Integer.getInteger("mcopt.own.frag.sizeQuads", 8)))), FRAG_FRAME_BYTES = 64, BINS = 8;
	static final int K_GREATER = 1 << 16, K_HALF = 1 << 18;
	private static final int K_EQUAL = 64, K_BIAS = 1 << 17, K_LEAN = 1 << 19, K_NONIDX = 1 << 20,
		K_MESH = 1 << 21, K_REVERSE = 1 << 22, K_MESH4 = 1 << 23, K_BOUND = 1 << 24, K_TIGHT = 1 << 25, K_POSONLY = 1 << 26, K_OCCA = 1 << 27,
		K_OCCB = 1 << 28, K_QMESH = 1 << 29, K_FOGVERT = 1 << 14, K_FOGVERT_ALL = 1 << 15, K_FOGVERT_T = 1 << 13;
	static final boolean QMESH = Boolean.getBoolean("mcopt.own.frag.qmesh");
	static final boolean FACESTATS = Boolean.getBoolean("mcopt.own.frag.facestats");
	static final boolean MSUB = Boolean.getBoolean("mcopt.own.frag.msub");
	static final boolean PIPE = Boolean.getBoolean("mcopt.own.frag.pipe");
	static final boolean LISTS2 = Boolean.getBoolean("mcopt.own.frag.lists2");
	static final boolean SPLIT_A = Boolean.getBoolean("mcopt.own.frag.splitA");
	static final int SPLIT_NEAR = shadeWhole() ? 0 : Integer.getInteger("mcopt.own.frag.splitNear", 0);
	private static boolean shadeWhole() {
		return mcopt.metal.shade.terrain.ShadeTerrain.ON && !"profile".equals(System.getProperty("mcopt.shade.ownSplit"));
	}
	static final boolean NEAR_ONLY = Boolean.getBoolean("mcopt.own.frag.nearOnly");
	static final boolean TEST_LATE = !shadeWhole() && Boolean.getBoolean("mcopt.own.frag.testLate");
	static final int TL_A1_SPLIT = Integer.getInteger("mcopt.own.tl.a1Split", 0);
	static final int TL_A1_MAX_CORES = Integer.getInteger("mcopt.own.tl.a1SplitMaxCores", 0), TL_A1_MIN_CORES = Integer.getInteger("mcopt.own.tl.a1SplitMinCores", 0);
	private static final boolean TL_A1_GATED;
	static {
		int cores = mcopt.metal.Profile.gpuCores();
		boolean any = TL_A1_MAX_CORES > 0 || TL_A1_MIN_CORES > 0;
		boolean on = !any || cores > 0 && (TL_A1_MAX_CORES > 0 && cores <= TL_A1_MAX_CORES || TL_A1_MIN_CORES > 0 && cores >= TL_A1_MIN_CORES);
		TL_A1_GATED = !on;
		if (TL_A1_SPLIT > 0) System.out.println("mcopt-own-frag tl.a1Split: " + TL_A1_SPLIT + "% " + (on ? "on" : "off") + " (gpu cores " + cores + ", max " + TL_A1_MAX_CORES + ", min " + TL_A1_MIN_CORES + ")");
	}
	private int a1sNow, a1sId;
	private long a1sCur;
	private final long[] a1sBuf = new long[3];
	private int a1sFrame;
	private boolean a1sDidSplit;
	private static final boolean A1S_FAIL_TEST = Boolean.getBoolean("mcopt.own.tl.a1SplitFailTest");
	private boolean a1sTestSet;
	private long a1sSplit, a1sNoKernel, a1sNotWritten, a1sPassRefused, a1sLogAt;
	private final java.util.Set<String> a1sLogged = new java.util.HashSet<>();

	private void a1sOnce(String why) {
		if (this.a1sLogged.add(why)) System.out.println("mcopt-own-frag tl.a1Split: drawn unsplit: " + why + " (logged once)");
	}

	private boolean a1sPassOk() {
		int why = OwnNative.tlRestartOk(this.enc);
		if (why == 0) return true;
		this.a1sNow = 0;
		this.a1sPassRefused++;
		this.a1sOnce("the render pass isn't one a restart keeps exact (mcmetal.m mc_tl_restart_ok reason " + why + ")");
		return false;
	}

	private void a1sLog() {
		long now = System.nanoTime();
		if (now < this.a1sLogAt) return;
		this.a1sLogAt = now + 10_000_000_000L;
		System.out.printf("mcopt-own-frag tl.a1Split: frames split %d; unsplit: kernel unavailable %d, not written %d, pass unsupported %d%n", this.a1sSplit,
			this.a1sNoKernel, this.a1sNotWritten, this.a1sPassRefused);
	}
	static final boolean B_REDRAW = Boolean.getBoolean("mcopt.own.frag.bRedraw");
	static final boolean DROP_SOLID_A = Boolean.getBoolean("mcopt.own.frag.dropSolidA");
	static final boolean TIE_CLOSE = OwnTieGroups.CLOSE;
	static final boolean TIE_VERIFY = TIE_CLOSE && Boolean.getBoolean("mcopt.own.frag.tieCloseVerify");
	static final int CAP_FAULT = Math.max(0, Integer.getInteger("mcopt.own.frag.capFault", 0));
	static final int T_CAP_FAULT = Math.max(0, Integer.getInteger("mcopt.own.frag.tCapFault", 0));
	private static final long[] TIE_FRAMES = new long[6];
	static final boolean A1_EXACT = Boolean.getBoolean("mcopt.own.frag.a1Exact");
	static final boolean A2_EXACT = Boolean.getBoolean("mcopt.own.frag.a2Exact");
	static final int EXACT_MAX_CORES = Integer.getInteger("mcopt.own.frag.exactMaxCores", 0);
	static final int A2_MAX_CORES = Integer.getInteger("mcopt.own.frag.a2ExactMaxCores", 0);
	private static boolean coreGated(int max) {
		int cores = mcopt.metal.Profile.gpuCores();
		return max > 0 && (cores <= 0 || cores > max);
	}
	private static final boolean EXACT_GATED = coreGated(EXACT_MAX_CORES), A2_GATED = coreGated(A2_MAX_CORES);
	static final int VGROUP = Integer.getInteger("mcopt.own.frag.vGroup", 0), VGROUP_MIN_CORES = Integer.getInteger("mcopt.own.frag.vGroupMinCores", 0);
	private static final int VGROUP_SH = vGroupShift();
	static final boolean VARGS_COPY = Boolean.getBoolean("mcopt.own.frag.vArgsCopy");
	static final boolean VGROUP_FAIL_A = Boolean.getBoolean("mcopt.own.frag.vGroupFailA"), VGROUP_FAIL_B = Boolean.getBoolean("mcopt.own.frag.vGroupFailB");
	private boolean vgTestSet;
	private final long vgTel = MemoryUtil.nmemCalloc(6, 8);
	private long vgLogAt;

	private void vgLog(int sh) {
		long now = System.nanoTime();
		if (sh == 0 || now < this.vgLogAt) return;
		this.vgLogAt = now + 10_000_000_000L;
		OwnNative.vGroupStats(this.own, this.vgTel);
		long[] v = new long[6];
		for (int i = 0; i < 6; i++) v[i] = MemoryUtil.memGetLong(this.vgTel + 8L * i);
		System.out.printf("mcopt-own-frag vGroup: culls grouped %d, requested but ungrouped %d; class draws grouped: phase A %d, phase B %d, split halves %d; drawn ungrouped %d%n",
			v[0], v[1], v[2], v[3], v[4], v[5]);
	}
	private static int vGroupShift() {
		if (VGROUP != 2 && VGROUP != 4 && VGROUP != 8) return 0;
		int cores = mcopt.metal.Profile.gpuCores();
		if (VGROUP_MIN_CORES > 0 && (cores <= 0 || cores < VGROUP_MIN_CORES)) return 0;
		return Integer.numberOfTrailingZeros(VGROUP);
	}
	static final int EXACT_SHIFT = Math.max(0, Math.min(3, Integer.getInteger("mcopt.own.frag.exactShift", 2)));
	private static final int K_EXACT = 1 << 12;
	private boolean a1Exact, a2Exact, a1Req, a2Req;
	private final long exactTel = MemoryUtil.nmemCalloc(32, 4);
	private long exactLogAt;
	private int a1Frame;
	static final int PYR_AUTO = Integer.getInteger("mcopt.own.frag.pyrAuto", 0);
	static final String A_KIND = System.getProperty("mcopt.own.frag.aKind", "");
	static final boolean FOG_LOG = Boolean.getBoolean("mcopt.own.frag.fogLog");
	static final String T_KIND = System.getProperty("mcopt.own.frag.tKind", "");

	static final boolean FOG_VERT = Boolean.getBoolean("mcopt.own.frag.fogVert");

	static int tKind() {
		return switch (OwnProbe.string("frag.tKind", T_KIND)) {
			case "fogvertall" -> K_FOGVERT | K_FOGVERT_ALL;
			case "fogvert" -> K_FOGVERT_T;
			default -> OwnProbe.bool("frag.fogVert", FOG_VERT) ? K_FOGVERT_T : 0;
		};
	}
	private String fogLogged = "";

	private static int aKind(int base) {
		return switch (OwnProbe.string("frag.aKind", A_KIND)) {
			case "tex" -> base | 8;
			case "nofade" -> base | 9;
			case "nofog" -> base | 10;
			case "fogvert" -> base | K_FOGVERT;
			case "fogvertall" -> base | K_FOGVERT | K_FOGVERT_ALL;
			case "flat" -> base | 2;
			case "pos" -> base | 7 | K_POSONLY;
			case "skip" -> -1;
			default -> base;
		};
	}
	static final int A2_ENC = Integer.getInteger("mcopt.own.frag.a2Enc", 0);
	static final boolean NEW_IN_A2 = Boolean.getBoolean("mcopt.own.frag.newInA2");
	static final boolean NEW_IN_A = Boolean.getBoolean("mcopt.own.frag.newInA") || NEW_IN_A2;

	private static int lists(Config c) {
		return c.listCount() * (c.uocc && SPLIT_NEAR > 0 ? 2 : 1);
	}

	private static int splitNear() {
		if (SPLIT_NEAR <= 0) return SPLIT_NEAR;
		int v = OwnProbe.integer("frag.splitNear", SPLIT_NEAR);
		return v > 0 ? v : SPLIT_NEAR;
	}

	static final boolean ORACLE = Boolean.getBoolean("mcopt.own.oracle");
	private static final int K_ORACLE = 1 << 30, ORACLE_EVERY = 64, ORACLE_READ = 8;

	static int qmeshKind(boolean on) {
		return on ? K_MESH | K_QMESH : 0;
	}

	record Config(int order, boolean sizes, boolean lean, boolean half, boolean box, boolean nonidx, boolean mesh, boolean sort, boolean mesh4,
		boolean tight, boolean posonly, boolean occ, boolean occa, boolean uocc, int fine, boolean nosub, boolean hull, int diag, boolean rpyr, int pyr) {
		static @Nullable Config parse(String opts) {
			int order = VANILLA;
			boolean sizes = false, lean = false, half = false, box = false, nonidx = false, mesh = false, sort = false, mesh4 = false, tight = false,
				posonly = false, occ = false, occa = false, uocc = false;
			int fine = 0;
			boolean nosub = false, hull = false;
			int diag = 0;
			boolean rpyr = false;
			int pyr = 0;
			for (String o : opts.split("\\+")) {
				switch (o.trim()) {
					case "", "vanilla" -> {
					}
					case "cutfirst" -> order = CUTFIRST;
					case "bpre" -> order = BPRE;
					case "split" -> order = SPLIT;
					case "sizes" -> sizes = true;
					case "lean" -> lean = true;
					case "half" -> half = true;
					case "box" -> box = true;
					case "nonidx" -> nonidx = true;
					case "mesh" -> mesh = OwnTerrain.RUN <= 32;
					case "sort" -> sort = true;
					case "mesh4" -> mesh4 = mesh = OwnTerrain.RUN <= 32;
					case "tight" -> tight = true;
					case "posonly" -> posonly = true;
					case "occ" -> occ = OwnTerrain.RUN <= 32;
					case "occa" -> occ = occa = OwnTerrain.RUN <= 32;
					case "uocc" -> uocc = true;
					case "fine1", "fine2", "fine3", "fine4" -> fine = o.trim().charAt(4) - '0';
					case "hull" -> hull = true;
					case "rpyr" -> rpyr = true;
					case "pyr4" -> pyr = 1;
					case "pyr8" -> pyr = 2;
					case "nopyr" -> diag = 1;
					case "nosplit" -> diag = 2;
					case "nosub" -> nosub = true;
					case "nosolid", "noquads" -> {
					}
					default -> throw new IllegalArgumentException("mcopt-own-frag: unknown option " + o);
				}
			}
			if (occ) {
				mesh = true;
				mesh4 = false;
				order = VANILLA;
			}
			if (uocc) {
				occ = occa = mesh = mesh4 = nonidx = posonly = false;
				order = VANILLA;
			}
			return new Config(order, sizes, lean, half, box, nonidx, mesh, sort, mesh4, tight, posonly, occ, occa, uocc, fine, nosub, hull, diag, rpyr, pyr);
		}

		int sizeClasses() {
			return this.sizes ? Math.max(1, OwnTerrain.RUN / SIZE_QUADS) : 1;
		}

		int classes() {
			return Math.max(this.sizeClasses(), this.sort ? BINS : 1);
		}

		int listCount() {
			return 2 * this.classes() * 2;
		}
	}

	static final @Nullable Config FLAGS = flags();
	static final double UOCC_MAX_MP = Double.parseDouble(System.getProperty("mcopt.own.frag.uoccMaxMp", "0"));
	static final int UOCC_MAX_MP_CORES = Integer.getInteger("mcopt.own.frag.uoccMaxMpCores", 0);
	private static final boolean SIZE_SWITCH = UOCC_MAX_MP > 0 && (UOCC_MAX_MP_CORES <= 0 || mcopt.metal.Profile.gpuCores() >= UOCC_MAX_MP_CORES);
	private static final @Nullable Config FLAGS_NO_UOCC = FLAGS != null && FLAGS.uocc() && (SIZE_SWITCH || OwnProbe.ON || TIE_CLOSE
		|| mcopt.metal.shade.terrain.ShadeTerrain.ON) ? Config.parse(flagString().replace("+uocc", "")) : null;

	private static @Nullable Config drawFlags() {
		if (FLAGS_NO_UOCC == null) return FLAGS;
		if (!OwnProbe.bool("frag.uocc", true)) return FLAGS_NO_UOCC;
		if (mcopt.metal.shade.terrain.ShadeTerrain.ON && mcopt.metal.shade.terrain.ShadeTerrain.noUocc()) return FLAGS_NO_UOCC;
		if (!SIZE_SWITCH) return FLAGS;
		var w = net.minecraft.client.Minecraft.getInstance().getWindow();
		return (double) w.getWidth() * w.getHeight() > UOCC_MAX_MP * 1e6 ? FLAGS_NO_UOCC : FLAGS;
	}

	private static @Nullable Config flags() {
		String s = flagString();
		return s.equals("vanilla") ? null : Config.parse(s);
	}

	private static String flagString() {
		String order = System.getProperty("mcopt.own.frag.order", "vanilla");
		StringBuilder b = new StringBuilder(order);
		for (String f : new String[] {"sizes", "lean", "half", "box", "mesh", "sort", "mesh4", "tight", "occ", "uocc", "hull", "nopyr", "nosplit", "rpyr"}) if (Boolean.getBoolean("mcopt.own.frag." + f)) b.append('+').append(f);
		int fine = Integer.getInteger("mcopt.own.frag.fine", 0);
		if (fine >= 1 && fine <= 4) b.append("+fine").append(fine);
		int skip = Integer.getInteger("mcopt.own.frag.pyrSkip", 0);
		if (skip == 1 || skip == 2) b.append(skip == 1 ? "+pyr4" : "+pyr8");
		return b.toString();
	}

	static boolean cutFirst(String mode) {
		return mode.equals("f-qcutfirst");
	}

	static int quadsKind(String mode) {
		Config c = quadsConfig(mode);
		return c == null ? 0 : (c.half() ? K_HALF : 0) | (c.tight() ? K_TIGHT : 0);
	}

	private static @Nullable Config quadsConfig(String mode) {
		if (mode.startsWith("f-")) return Config.parse(mode.substring(2));
		return mode.equals("draw") ? FLAGS : null;
	}

	static @Nullable Config config(String mode, boolean occOrFat) {
		return config(mode, occOrFat, 0);
	}

	static @Nullable Config config(String mode, boolean occOrFat, int tieReason) {
		if (occOrFat) return null;
		if (mode.startsWith("f-")) return Config.parse(mode.substring(2));
		if (!mode.equals("draw")) return null;
		Config c = drawFlags();
		if (c == null || !c.uocc() || !tieCloseOn()) return c;
		TIE_FRAMES[Math.min(tieReason, 5)]++;
		return tieReason != 0 && FLAGS_NO_UOCC != null ? FLAGS_NO_UOCC : c;
	}

	static boolean tieCloseOn() {
		return TIE_CLOSE && OwnTieGroups.ON && OwnProbe.bool("frag.tieClose", true) && !OwnProbe.bool("frag.dropSolidA", DROP_SOLID_A);
	}

	private final OwnTerrain terrain;
	private final long ctx, enc, own;
	private long lists, args, argsAddress;
	private int listCap, listCount;
	private static final String CULL_R = System.getProperty("mcopt.own.int.cullR", "false");
	private static final boolean CULL_R_ON = "true".equals(CULL_R), CULL_R_VERIFY = "verify".equals(CULL_R);
	private static final int CULL_W = Integer.getInteger("mcopt.own.int.cullW", 8);
	private static final boolean PRE_EARLY = Boolean.getBoolean("mcopt.own.int.preEarly");
	private long vLists, vArgs;
	private final long[] sigs = new long[3], sigAddrs = new long[3];
	private final int[] sigLists = new int[3];
	private long sigFrame, sigChecked, sigBad, sigLog;
	private long masks;
	private int masksCap;
	private final long[] statOcc = new long[4];
	private long occVis, tList, listsB, bArgs, bArgsAddress;
	private int occVisCap, tCap, bCount;
	private long pLists, pArgs, pArgsAddress, pOccVis, pTList, pListsB, pBArgs, pBArgsAddress;
	private final long[] statSlots = new long[8];
	private int pListCount, pListCap, pOccVisCap, pTCap, pBCount;
	private int epoch;
	private long cullFrame, fragFrame = MemoryUtil.nmemCalloc(1, FRAG_FRAME_BYTES), clipAddr = MemoryUtil.nmemCalloc(1, 64);
	private int cullFrameLength;
	private int tieSetN, pTieSetN;
	private long tiePromoted, tiePromotedQuads, tieCounted, tieLogAt = System.nanoTime();
	private final long[] tieLost = new long[5], tieLostAll = new long[5];
	private long statTested, statB, statQuadsA, statQuadsB, statRedraw, statRedrawQuads, statRedrawFrames, statA1Quads, statA1Units, statA1Slots, statA1Over;
	private final long[] statFace = new long[5];
	private long statFunnelIn, statFunnelFacing;
	private @Nullable Config last;
	private long hAtlas, hSampler, hLight, hLightSampler;
	private boolean bound;
	private final java.util.Set<Integer> failed = new java.util.HashSet<>();
	private long statFrames, statSolid, statCutout, statLean, statLast = System.nanoTime();
	private final long[] statBuckets = new long[14];
	private long oKept, oVis, oKeptAddr, oVisAddr, oDraws, oSampleAt = -1, oTest, oTestAddr;
	private int oEpoch;
	private boolean oTested;
	private int oCap, oFrame;
	private double oCamX, oCamY, oCamZ, oCullX, oCullY, oCullZ;
	private @Nullable Config oConfig;
	private long oLogAt;

	OwnFrag(OwnTerrain terrain, long ctx, long enc, long own) {
		this.terrain = terrain;
		this.ctx = ctx;
		this.enc = enc;
		this.own = own;
		System.out.println("mcopt-own-frag: on" + (FLAGS != null ? " (" + FLAGS + ")" : " (probe modes only)") + (LISTS2 ? ", two sets of the cull's lists" : "") + (SPLIT_A ? ", phase A split around the test" : "") + (SPLIT_NEAR > 0 ? ", phase A split at " + SPLIT_NEAR + " blocks" + (NEAR_ONLY ? ", farther units tested, not drawn in phase A" : "") : "") + (TEST_LATE ? ", test after phase A's later part" : "") + (NEW_IN_A ? ", untested units in phase A" + (NEW_IN_A2 ? "'s later part" : "") : "") + (PYR_AUTO > 0 ? ", uocc pyramid coarser while >= " + PYR_AUTO + " texels wide" : "") + (SIZE_SWITCH && FLAGS_NO_UOCC != null ? ", no uocc above " + UOCC_MAX_MP + " MP" + (UOCC_MAX_MP_CORES > 0 ? " (gpu cores " + mcopt.metal.Profile.gpuCores() + " >= " + UOCC_MAX_MP_CORES + ")" : "") : UOCC_MAX_MP > 0 ? ", uocc at every size (gpu cores " + mcopt.metal.Profile.gpuCores() + " < " + UOCC_MAX_MP_CORES + ")" : ""));
	}

	void cull(Config c, long f, int frameBytes, long table, long recs, long mask, int sectionCount, int listCap, GpuBufferSlice fog, int nowMs, int fadeMs,
		int renderDistance, org.joml.Matrix4f clip) {
		if (PIPE && c.uocc || LISTS2 && !c.uocc) this.swapSets();
		int n = lists(c);
		if (n > this.listCount || listCap != this.listCap) {
			long oldLists = this.lists, oldArgs = this.args;
			if (oldLists != 0) this.terrain.later(() -> {
				OwnNative.release(oldLists);
				OwnNative.release(oldArgs);
			});
			int count = Math.max(n, this.listCount);
			this.lists = OwnNative.buffer(this.ctx, 4L * count * listCap, 0);
			this.args = OwnNative.buffer(this.ctx, 32L * count + 128, 2);
			this.argsAddress = OwnNative.contents(this.args);
			this.listCount = count;
			this.listCap = listCap;
		}
		this.last = c;
		if (ORACLE) {
			this.oCullX = MemoryUtil.memGetInt(f + 64) - MemoryUtil.memGetFloat(f + 80);
			this.oCullY = MemoryUtil.memGetInt(f + 68) - MemoryUtil.memGetFloat(f + 84);
			this.oCullZ = MemoryUtil.memGetInt(f + 72) - MemoryUtil.memGetFloat(f + 88);
		}
		if (c.uocc) {
			int cap = this.terrain.recCapacity();
			if (cap > this.occVisCap) {
				long old = this.occVis;
				if (old != 0) this.terrain.later(() -> OwnNative.release(old));
				this.occVis = OwnNative.buffer(this.ctx, 24L * cap, 0);
				OwnNative.fragClear(this.own, this.occVis);
				this.occVisCap = cap;
			}
			if (listCap != this.tCap || this.listCount > this.bCount) {
				long oldT = this.tList, oldL = this.listsB, oldA = this.bArgs;
				if (oldT != 0) this.terrain.later(() -> {
					OwnNative.release(oldT);
					OwnNative.release(oldL);
					OwnNative.release(oldA);
				});
				this.tList = OwnNative.buffer(this.ctx, 8L * listCap, 0);
				this.listsB = OwnNative.buffer(this.ctx, 4L * this.listCount * listCap, 0);
				this.bArgs = OwnNative.buffer(this.ctx, 20L * this.listCount + 64, 2);
				this.bArgsAddress = OwnNative.contents(this.bArgs);
				this.tCap = listCap;
				this.bCount = this.listCount;
			}
			if (this.tieSetN > 0 && this.bArgsAddress != 0) {
				this.tiePromoted += Integer.toUnsignedLong(MemoryUtil.memGetInt(this.bArgsAddress + (this.tieSetN * 5L + 6) * 4));
				this.tiePromotedQuads += Integer.toUnsignedLong(MemoryUtil.memGetInt(this.bArgsAddress + (this.tieSetN * 5L + 7) * 4));
				for (int k = 0; k < 5; k++) {
					long v = Integer.toUnsignedLong(MemoryUtil.memGetInt(this.bArgsAddress + (this.tieSetN * 5L + 8 + k) * 4));
					this.tieLost[k] += v;
					this.tieLostAll[k] += v;
				}
				this.tieCounted++;
			}
			if (++this.epoch == 0) this.epoch = 1;
			this.cullFrame = f;
			this.cullFrameLength = frameBytes;
			clip.getToAddress(this.clipAddr);
			OwnNative.fragUoccBuffers(this.own, this.occVis, this.tList, this.bArgs, this.listsB);
			this.terrain.tOccBind();
		} else if (this.occVis != 0) {
			OwnNative.fragUoccBuffers(this.own, 0, 0, 0, 0);
		}
		if (c.occ) {
			int cap = this.terrain.recCapacity();
			if (cap > this.masksCap) {
				long old = this.masks;
				if (old != 0) this.terrain.later(() -> OwnNative.release(old));
				this.masks = OwnNative.buffer(this.ctx, 4L * cap, 0);
				this.masksCap = cap;
			}
			OwnNative.fragMasks(this.own, this.masks, this.args, (n * 8L + 14) * 4, OwnTerrain.STATS);
		}
		float rdStart = 0;
		long fa = MetalBridge.bufferAddress(fog.buffer());
		if (fa != 0) rdStart = MemoryUtil.memGetFloat(fa + fog.offset() + 24);
		if (FOG_LOG && fa != 0) {
			long b = fa + fog.offset();
			String v = String.format("env %.2f..%.2f, render distance %.2f..%.2f, sky %.2f, clouds %.2f, colour a %.3f", MemoryUtil.memGetFloat(b + 16),
				MemoryUtil.memGetFloat(b + 20), MemoryUtil.memGetFloat(b + 24), MemoryUtil.memGetFloat(b + 28), MemoryUtil.memGetFloat(b + 32),
				MemoryUtil.memGetFloat(b + 36), MemoryUtil.memGetFloat(b + 12));
			if (!v.equals(this.fogLogged)) {
				System.out.println("mcopt-own-frag: fog " + v);
				this.fogLogged = v;
			}
		}
		long ring = TIE_CLOSE ? this.terrain.tieRing() : 0;
		boolean tie = c.uocc && ring != 0 && tieCloseOn();
		if (TIE_CLOSE) {
			OwnNative.fragTieRing(this.own, tie ? ring : 0);
			OwnNative.fragTieVerify(this.own, tie && TIE_VERIFY ? this.lists : 0, tie && TIE_VERIFY);
			if (c.uocc) this.tieSetN = tie ? n : 0;
			this.tieLog();
		}
		this.vgLog(OwnProbe.integer("frag.vGroupSh", VGROUP_SH) | (VARGS_COPY ? 4 : 0));
		if ((VGROUP_FAIL_A || VGROUP_FAIL_B) && !this.vgTestSet) {
			OwnNative.vGroupFailTest(VGROUP_FAIL_A, VGROUP_FAIL_B);
			this.vgTestSet = true;
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long g = stack.ncalloc(16, 1, FRAG_FRAME_BYTES);
			MemoryUtil.memPutFloat(g, rdStart);
			MemoryUtil.memPutInt(g + 4, nowMs);
			MemoryUtil.memPutInt(g + 8, fadeMs);
			MemoryUtil.memPutInt(g + 12, (c.lean && fa != 0 ? 1 : 0) | (c.sizes ? 2 : 0) | (c.box ? 4 : 0) | (c.order == CUTFIRST ? 8 : 0) | (c.sort ? 16 : 0)
				| (OwnTerrain.STATS ? 32 : 0) | (c.uocc ? 64 : 0) | (c.uocc && OwnProbe.bool("frag.msub", MSUB) && !c.nosub ? 256 : 0) | (c.uocc && PIPE ? 512 : 0) | (c.uocc && SPLIT_NEAR > 0 ? 1024 : 0)
				| (c.uocc && SPLIT_NEAR > 0 && NEAR_ONLY ? 2048 : 0) | (c.uocc && NEW_IN_A ? 4096 : 0) | (c.uocc && NEW_IN_A2 && SPLIT_NEAR > 0 ? 8192 : 0)
				| (c.uocc && OwnProbe.bool("frag.dropSolidA", DROP_SOLID_A) ? 16384 : 0)
				| (c.uocc && OwnProbe.bool("frag.bRedraw", B_REDRAW) && !tieCloseOn() ? 32768 : 0)
				| (OwnQuads.BUCKET_CLASS ? 128 : 0) | (tie ? 1 << 20 : 0) | (OwnProbe.integer("frag.vGroupSh", VGROUP_SH) & 3) << 24 | (VARGS_COPY ? 1 << 26 : 0));
			MemoryUtil.memPutInt(g + 16, c.sizeClasses());
			MemoryUtil.memPutInt(g + 20, OwnTerrain.RUN / c.sizeClasses());
			MemoryUtil.memPutInt(g + 24, n);
			MemoryUtil.memPutInt(g + 28, c.classes());
			MemoryUtil.memPutFloat(g + 32, Math.max(16, renderDistance * 16f / BINS));
			MemoryUtil.memPutInt(g + 36, OwnTerrain.RUN);
			MemoryUtil.memPutInt(g + 40, this.epoch);
			MemoryUtil.memPutInt(g + 44, T_CAP_FAULT > 0 ? Math.min(this.tCap, T_CAP_FAULT) : this.tCap);
			MemoryUtil.memPutFloat(g + 48, splitNear());
			MemoryUtil.memPutInt(g + 52, this.occVisCap);
			MemoryUtil.memPutInt(g + 56, CAP_FAULT);
			MemoryUtil.memCopy(g, this.fragFrame, FRAG_FRAME_BYTES);
			boolean able = c.uocc && !c.mesh && !c.mesh4 && !c.nonidx && !c.posonly && !c.tight && !ORACLE && c.order != CUTFIRST && !Boolean.getBoolean("mcopt.own.fat");
			this.a1Req = OwnProbe.bool("frag.a1Exact", A1_EXACT);
			this.a2Req = OwnProbe.bool("frag.a2Exact", A2_EXACT);
			this.a1Exact = able && !EXACT_GATED && this.a1Req;
			this.a2Exact = able && !EXACT_GATED && !A2_GATED && this.a2Req && SPLIT_NEAR > 0;
			boolean tableOnly = !this.a1Exact && c.uocc && !c.mesh && !c.mesh4 && !c.nonidx && OwnProbe.bool("frag.a1Table", false);
			int a1Mode = this.a1Exact || this.a2Exact || tableOnly ? 1 : c.uocc && OwnTerrain.STATS ? 2 : 0;
			if (a1Mode != 0 || this.a1Frame > 0) {
				long lmask = 0;
				int cls2 = c.classes() * 2, bank = c.uocc && SPLIT_NEAR > 0 ? c.listCount() : lists(c);
				if (this.a1Exact || tableOnly || a1Mode == 2) lmask |= bits(0, cls2);
				if (this.a2Exact) lmask |= bits(bank, bank + cls2) | bits(cls2, bank) | bits(bank + cls2, 2 * bank);
				OwnNative.fragExactMask(this.own, (int) lmask, (int) (lmask >>> 32));
				OwnNative.fragA1Exact(this.own, a1Mode, cls2, listCap, this.a1Frame++);
			}
			if (FACESTATS) OwnNative.fragFace(this.own, this.terrain.arenaBuffer(), true);
			int a1p = c.uocc && SPLIT_NEAR > 0 && !this.a1Exact && c.order != CUTFIRST && !c.sort && !TL_A1_GATED ? OwnProbe.integer("tl.a1Split", TL_A1_SPLIT) : 0;
			this.a1sNow = a1p > 0 && a1p < 100 ? a1p : 0;
			if (this.a1sNow > 0) this.a1sLog();
			if (this.a1sNow > 0) {
				int k = this.a1sFrame++ % 3;
				if (this.a1sBuf[k] == 0) this.a1sBuf[k] = OwnNative.buffer(this.ctx, 64, 0);
				this.a1sCur = this.a1sBuf[k];
				this.a1sId = (c.sizeClasses() - 1) * 2 + (c.lean ? 1 : 0);
				if (A1S_FAIL_TEST && !this.a1sTestSet) {
					OwnNative.tlA1SplitTest(true);
					this.a1sTestSet = true;
				}
				if (!OwnNative.tlA1SplitReq(this.own, this.a1sCur, this.a1sId, this.a1sNow, 100)) {
					this.a1sNow = 0;
					this.a1sNoKernel++;
					this.a1sOnce("the split kernel is unavailable");
				}
			}
			boolean ordered = c.order == CUTFIRST || c.sort;
			if ((CULL_R_ON || CULL_R_VERIFY && !c.uocc) && !ordered) {
				int lanes = CULL_W;
				if (CULL_R_VERIFY) {
					if (this.vArgs == 0 || this.vListsCap != (long) this.listCount * this.listCap) {
						long oa = this.vArgs, ol = this.vLists;
						if (oa != 0) this.terrain.later(() -> {
							OwnNative.release(oa);
							OwnNative.release(ol);
						});
						this.vArgs = OwnNative.buffer(this.ctx, 32L * this.listCount + 128, 0);
						this.vLists = OwnNative.buffer(this.ctx, 4L * this.listCount * this.listCap, 0);
						this.vListsCap = (long) this.listCount * this.listCap;
					}
					int k = (int) (this.sigFrame++ % 3);
					if (this.sigs[k] == 0 || this.sigLists[k] < n) {
						long old = this.sigs[k];
						if (old != 0) this.terrain.later(() -> OwnNative.release(old));
						this.sigs[k] = OwnNative.buffer(this.ctx, 32L * Math.max(n, 64), 2);
						this.sigAddrs[k] = OwnNative.contents(this.sigs[k]);
						this.sigLists[k] = Math.max(n, 64);
						MemoryUtil.memSet(this.sigAddrs[k], 0, 32L * this.sigLists[k]);
					} else if (this.sigFrame > 3) {
						this.checkSignatures(this.sigAddrs[k], n);
					}
					OwnNative.fragCull(this.own, this.enc, f, frameBytes, g, FRAG_FRAME_BYTES, table, recs, this.args, this.lists, mask, sectionCount, n, lanes,
						this.vArgs, this.vLists, this.sigs[k], this.listCap);
				} else {
					OwnNative.fragCull(this.own, this.enc, f, frameBytes, g, FRAG_FRAME_BYTES, table, recs, this.args, this.lists, mask, sectionCount, n, lanes, 0,
						0, 0, this.listCap);
				}
			} else {
				OwnNative.fragCull(this.own, this.enc, f, frameBytes, g, FRAG_FRAME_BYTES, table, recs, this.args, this.lists, mask, sectionCount, n);
			}
			if (this.a1sNow > 0 && !OwnNative.tlA1SplitWritten(this.own)) {
				this.a1sNow = 0;
				this.a1sNotWritten++;
				this.a1sOnce("the cull didn't write the split arguments");
			}
			if (PRE_EARLY) OwnNative.preCommit(this.enc);
			if (this.terrain.tracing()) {
				System.out.println("mcopt-metal trace: compute encoder 'own frag cull' #" + OwnNative.lastComputeGroup() + " (pre command buffer, profiled like a pass)");
			}
		}
	}

	private void tieLog() {
		long now = System.nanoTime();
		if (now - this.tieLogAt < 5_000_000_000L) return;
		this.tieLogAt = now;
		long[] t = TIE_FRAMES;
		long all = t[0] + t[1] + t[2] + t[3] + t[4] + t[5];
		System.out.printf("mcopt-own-frag tieClose: %s; uocc frames %d: closure %d, without uocc %d (grouping off, inFlight > 2: %d, cross-section candidate %d, unknown section %d, "
			+ "float scope %d, lists %d); promoted a frame %.2f units, %.1f quads (%d frames counted); entries lost to capacity A %d T %d B %d (run %d / %d / %d)%s; %s%n",
			tieCloseOn() ? "on" : "off (probe)", all, t[0], all - t[0], t[1], t[2], t[3], t[4], t[5], this.tieCounted == 0 ? 0.0 : (double) this.tiePromoted / this.tieCounted,
			this.tieCounted == 0 ? 0.0 : (double) this.tiePromotedQuads / this.tieCounted, this.tieCounted, this.tieLost[0], this.tieLost[1], this.tieLost[2],
			this.tieLostAll[0], this.tieLostAll[1], this.tieLostAll[2], TIE_VERIFY ? String.format("; verify: records in A and B %d, twice in A %d (run %d / %d)",
				this.tieLost[3], this.tieLost[4], this.tieLostAll[3], this.tieLostAll[4]) : "", this.terrain.tieRingStats());
		java.util.Arrays.fill(this.tieLost, 0);
		java.util.Arrays.fill(t, 0);
		this.tiePromoted = this.tiePromotedQuads = this.tieCounted = 0;
	}

	private void swapSets() {
		long t;
		int i;
		t = this.lists; this.lists = this.pLists; this.pLists = t;
		t = this.args; this.args = this.pArgs; this.pArgs = t;
		t = this.argsAddress; this.argsAddress = this.pArgsAddress; this.pArgsAddress = t;
		t = this.occVis; this.occVis = this.pOccVis; this.pOccVis = t;
		t = this.tList; this.tList = this.pTList; this.pTList = t;
		t = this.listsB; this.listsB = this.pListsB; this.pListsB = t;
		t = this.bArgs; this.bArgs = this.pBArgs; this.pBArgs = t;
		t = this.bArgsAddress; this.bArgsAddress = this.pBArgsAddress; this.pBArgsAddress = t;
		i = this.listCount; this.listCount = this.pListCount; this.pListCount = i;
		i = this.listCap; this.listCap = this.pListCap; this.pListCap = i;
		i = this.occVisCap; this.occVisCap = this.pOccVisCap; this.pOccVisCap = i;
		i = this.tCap; this.tCap = this.pTCap; this.pTCap = i;
		i = this.bCount; this.bCount = this.pBCount; this.pBCount = i;
		i = this.tieSetN; this.tieSetN = this.pTieSetN; this.pTieSetN = i;
	}

	private long vListsCap;

	private void checkSignatures(long sig, int n) {
		boolean ok = true;
		long units = 0;
		for (int id = 0; id < n && ok; id++) {
			long a = sig + id * 32L;
			for (int q = 0; q < 4; q++) ok &= MemoryUtil.memGetInt(a + q * 4L) == MemoryUtil.memGetInt(a + 16 + q * 4L);
			units += Integer.toUnsignedLong(MemoryUtil.memGetInt(a));
		}
		this.sigChecked++;
		if (!ok) this.sigBad++;
		MemoryUtil.memSet(sig, 0, 32L * n);
		long now = System.nanoTime();
		if (now - this.sigLog > 5_000_000_000L) {
			System.out.println("mcopt-own cullR verify: " + this.sigChecked + " frames, list mismatches " + this.sigBad + " (last frame " + units + " units in " + n
				+ " lists)");
			this.sigLog = now;
		}
	}

	boolean draw(Config c, long d, long arena, long table, long recs, long u, GpuTextureView atlas, GpuSampler sampler, GpuTextureView light, GpuSampler lightSampler) {
		boolean split = false;
		this.hAtlas = MetalBridge.viewHandle(atlas);
		this.hSampler = MetalBridge.samplerHandle(sampler);
		this.hLight = MetalBridge.viewHandle(light);
		this.hLightSampler = MetalBridge.samplerHandle(lightSampler);
		this.bound = false;
		int base = (c.half ? K_HALF : 0) | (c.nonidx ? K_NONIDX : 0) | (c.mesh ? K_MESH : 0) | (c.mesh4 ? K_MESH4 : 0) | (c.tight ? K_TIGHT : 0);
		boolean oSample = false;
		if (ORACLE) {
			this.oDraws++;
			if (this.oSampleAt > 0 && this.oDraws == this.oSampleAt + ORACLE_READ) this.oracleRead();
			oSample = !c.posonly && !c.mesh && !c.occ && this.oDraws % ORACLE_EVERY == 0 && System.nanoTime() - this.oLogAt > 1_000_000_000L;
			if (oSample) this.oLogAt = System.nanoTime();
		}
		if (c.posonly) {
			this.layer(c, 0, base | 7 | K_POSONLY, d, arena, table, recs, u, atlas, sampler, light, lightSampler);
			this.layer(c, 1, base | 7 | K_POSONLY, d, arena, table, recs, u, atlas, sampler, light, lightSampler);
			if (OwnTerrain.STATS) this.stats(c);
			return false;
		}
		if (c.uocc) {
			int a1 = aKind(base);
			this.exactLog();
			if (a1 >= 0 && this.a1Exact) {
				this.layer(c, 0, a1 | K_EXACT, d, arena, table, recs, u, atlas, sampler, light, lightSampler, this.lists, this.args);
				this.bound = false;
			} else if (a1 >= 0) {
				this.a1sDidSplit = false;
				this.layer(c, 0, a1, d, arena, table, recs, u, atlas, sampler, light, lightSampler, this.lists, this.args);
				if (this.a1sDidSplit) split = true;
			}
			if (SPLIT_A || SPLIT_NEAR > 0) oSample = false;
			else this.layer(c, 1, base | 1, d, arena, table, recs, u, atlas, sampler, light, lightSampler, this.lists, this.args);
			if (oSample) {
				this.oracleBegin();
				this.oracleLists(c, base, d, arena, table, recs, u, atlas, sampler, light, lightSampler, this.lists, this.args);
				if (this.oTest != 0) OwnNative.oracleTest(this.own, this.oTest);
				this.oEpoch = this.epoch;
				this.oTested = this.oTest != 0;
			}
			if (c.diag == 2) {
				if (OwnTerrain.STATS) this.stats(c);
				return false;
			}
			int pyr = Math.max(0, Math.min(2, OwnProbe.integer("frag.pyrSkip", c.pyr)));
			int uoccMode = OwnProbe.integer("frag.fine", c.fine) | (c.hull ? 256 : 0) | (c.diag == 1 ? 512 : 0) | (c.rpyr ? 1024 : 0) | (pyr << 13)
				| (pyr == 0 && PYR_AUTO > 0 ? 1 << 15 | Math.min(255, PYR_AUTO / 64) << 16 : 0);
			boolean late = OwnProbe.bool("frag.testLate", TEST_LATE) && (SPLIT_NEAR > 0 || SPLIT_A) && !c.rpyr && c.diag != 1;
			split = OwnNative.fragUocc(this.own, this.enc, this.cullFrame, this.cullFrameLength, this.fragFrame, FRAG_FRAME_BYTES, this.clipAddr, table, recs,
				this.args, lists(c), uoccMode | (late ? 2048 : 0)) == 1;
			if (this.terrain.tracing()) {
				System.out.println("mcopt-metal trace: compute encoder 'own frag unit occlusion' #" + OwnNative.lastComputeGroup() + " (the split, profiled like a pass)");
			}
			this.bound = false;
			if (SPLIT_NEAR > 0) {
				int bank = c.listCount(), ex = this.a2Exact ? K_EXACT : 0;
				this.layer(c, 0, base | ex, d, arena, table, recs, u, atlas, sampler, light, lightSampler, this.lists, this.args, bank);
				if (ex != 0) this.bound = false;
				if (A2_ENC > 0 && split) this.a2Split();
				this.layer(c, 1, base | 1 | ex, d, arena, table, recs, u, atlas, sampler, light, lightSampler, this.lists, this.args, 0);
				if (ex != 0) this.bound = false;
				if (A2_ENC > 1 && split) this.a2Split();
				this.layer(c, 1, base | 1 | ex, d, arena, table, recs, u, atlas, sampler, light, lightSampler, this.lists, this.args, bank);
				if (ex != 0) this.bound = false;
				this.afterLater(c, split, late, uoccMode, table, recs);
			} else if (SPLIT_A) {
				this.layer(c, 1, base | 1, d, arena, table, recs, u, atlas, sampler, light, lightSampler, this.lists, this.args);
				this.afterLater(c, split, late, uoccMode, table, recs);
			}
			this.layer(c, 0, base, d, arena, table, recs, u, atlas, sampler, light, lightSampler, this.listsB, this.bArgs);
			this.layer(c, 1, base | 1, d, arena, table, recs, u, atlas, sampler, light, lightSampler, this.listsB, this.bArgs);
			if (oSample) {
				this.oracleLists(c, base, d, arena, table, recs, u, atlas, sampler, light, lightSampler, this.listsB, this.bArgs);
				this.oracleEnd(c);
			}
			if (OwnTerrain.STATS) this.stats(c);
			return split;
		}
		if (c.occ) {
			this.layer(c, 0, base | K_OCCA, d, arena, table, recs, u, atlas, sampler, light, lightSampler);
			this.layer(c, 1, base | 1 | K_OCCA, d, arena, table, recs, u, atlas, sampler, light, lightSampler);
			if (c.occa) {
				if (OwnTerrain.STATS) this.stats(c);
				return false;
			}
			split = OwnNative.fragOcc(this.own, this.enc) == 1;
			this.bound = false;
			this.layer(c, 0, base | K_OCCB, d, arena, table, recs, u, atlas, sampler, light, lightSampler);
			this.layer(c, 1, base | 1 | K_OCCB, d, arena, table, recs, u, atlas, sampler, light, lightSampler);
			if (OwnTerrain.STATS) this.stats(c);
			return split;
		}
		switch (c.order) {
			case CUTFIRST -> {
				this.layer(c, 1, base | 1, d, arena, table, recs, u, atlas, sampler, light, lightSampler);
				this.layer(c, 0, base | K_GREATER | K_REVERSE, d, arena, table, recs, u, atlas, sampler, light, lightSampler);
			}
			case BPRE -> {
				this.layer(c, 1, base | 6 | K_BIAS, d, arena, table, recs, u, atlas, sampler, light, lightSampler);
				this.layer(c, 0, base, d, arena, table, recs, u, atlas, sampler, light, lightSampler);
				this.layer(c, 1, base | K_EQUAL | K_BIAS, d, arena, table, recs, u, atlas, sampler, light, lightSampler);
			}
			case SPLIT -> {
				this.layer(c, 0, base, d, arena, table, recs, u, atlas, sampler, light, lightSampler);
				split = OwnNative.split(this.own, this.enc) == 1;
				this.bound = false;
				this.layer(c, 1, base | 1, d, arena, table, recs, u, atlas, sampler, light, lightSampler);
			}
			default -> {
				int s0 = aKind(base);
				if (s0 >= 0) this.layer(c, 0, s0, d, arena, table, recs, u, atlas, sampler, light, lightSampler);
				this.layer(c, 1, base | 1, d, arena, table, recs, u, atlas, sampler, light, lightSampler);
			}
		}
		if (oSample) {
			this.oracleBegin();
			this.oracleLists(c, base, d, arena, table, recs, u, atlas, sampler, light, lightSampler, this.lists, this.args);
			this.oracleEnd(c);
		}
		if (OwnTerrain.STATS) this.stats(c);
		return split;
	}

	private void a2Split() {
		OwnNative.split(this.own, this.enc);
		this.bound = false;
	}

	private void afterLater(Config c, boolean split, boolean late, int uoccMode, long table, long recs) {
		if (split && late) {
			OwnNative.fragUocc(this.own, this.enc, this.cullFrame, this.cullFrameLength, this.fragFrame, FRAG_FRAME_BYTES, this.clipAddr, table, recs, this.args,
				lists(c), uoccMode | 4096);
			if (this.terrain.tracing()) System.out.println("mcopt-metal trace: compute encoder 'own frag unit occlusion' (testLate's test) #" + OwnNative.lastComputeGroup());
		} else if (split) {
			OwnNative.split(this.own, this.enc);
		}
		this.bound = false;
	}

	private void layer(Config c, int layer, int kind, long d, long arena, long table, long recs, long u, GpuTextureView atlas, GpuSampler sampler,
		GpuTextureView light, GpuSampler lightSampler) {
		this.layer(c, layer, kind, d, arena, table, recs, u, atlas, sampler, light, lightSampler, this.lists, this.args);
	}

	private void layer(Config c, int layer, int kind, long d, long arena, long table, long recs, long u, GpuTextureView atlas, GpuSampler sampler,
		GpuTextureView light, GpuSampler lightSampler, long lists, long args) {
		this.layer(c, layer, kind, d, arena, table, recs, u, atlas, sampler, light, lightSampler, lists, args, 0);
	}

	private void layer(Config c, int layer, int kind, long d, long arena, long table, long recs, long u, GpuTextureView atlas, GpuSampler sampler,
		GpuTextureView light, GpuSampler lightSampler, long lists, long args, int bank) {
		boolean bins = layer == 1 && c.sort;
		int classes = c.classes(), used = bins ? BINS : c.sizeClasses();
		for (int i = 0; i < used; i++) {
			int cls = bins || (kind & K_REVERSE) != 0 ? i : used - 1 - i;
			for (int lean = 0; lean < (c.lean ? 2 : 1); lean++) {
				int id = (layer * classes + cls) * 2 + lean + bank;
				MemoryUtil.memPutInt(d, (kind & K_EXACT) != 0 ? 0 : id * this.listCap);
				long argsOffset = c.mesh ? (lists(c) * 5L + id * 3L) * 4 : id * 20L;
				int k = kind | (lean == 1 ? K_LEAN : 0);
				if (this.failed.contains(k)) continue;
				try {
					long ar = args;
					if (this.a1sNow > 0 && layer == 0 && bank == 0 && lists == this.lists && id == this.a1sId && !c.mesh && (kind & K_EXACT) == 0
						&& this.a1sPassOk()) {
						this.bound = OwnNative.draw(this.own, this.enc, k | (this.bound ? K_BOUND : 0), d, OwnTerrain.DRAW_FRAME_BYTES, arena, table, recs, lists,
							this.a1sCur, 0, u, this.hAtlas, this.hSampler, this.hLight, this.hLightSampler) == 1;
						if (OwnNative.tlRestart(this.enc, 1) > 0) {
							this.a1sDidSplit = true;
							this.bound = false;
							this.a1sSplit++;
						}
						ar = this.a1sCur;
						argsOffset = 20;
					}
					this.bound = OwnNative.draw(this.own, this.enc, k | (this.bound ? K_BOUND : 0), d, OwnTerrain.DRAW_FRAME_BYTES, arena, table, recs, lists, ar,
						argsOffset, u, this.hAtlas, this.hSampler, this.hLight, this.hLightSampler) == 1;
				} catch (IllegalStateException e) {
					this.failed.add(k);
					System.out.println("mcopt-own-frag: draw kind " + k + " failed, skipped from now on: " + e.getMessage());
				}
			}
		}
	}

	private static long bits(int from, int to) {
		long m = 0;
		for (int i = from; i < Math.min(to, 64); i++) m |= 1L << i;
		return m;
	}

	private void exactLog() {
		long now = System.nanoTime();
		if (!(this.a1Req || this.a2Req) || now - this.exactLogAt < 5_000_000_000L) return;
		this.exactLogAt = now;
		OwnNative.fragExactTelemetry(this.own, this.exactTel);
		int cores = mcopt.metal.Profile.gpuCores();
		StringBuilder b = new StringBuilder("mcopt-own-frag exact: requested a1 " + this.a1Req + " a2 " + this.a2Req + "; effective a1 " + this.a1Exact + " a2 "
			+ this.a2Exact + (EXACT_GATED ? " (gated: exactMaxCores " + EXACT_MAX_CORES + ", gpu cores " + cores + ")" : "")
			+ (A2_GATED && this.a2Req ? " (a2 gated: a2ExactMaxCores " + A2_MAX_CORES + ", gpu cores " + cores + ")" : "")
			+ "; block " + (1 << EXACT_SHIFT) + " quads");
		String[] names = {"A1", "A2 solid", "A2 cutout"};
		for (int k = 0; k < 3; k++) {
			long t = this.exactTel + k * 32L;
			long in = MemoryUtil.memGetInt(t + 24) & 0xFFFFFFFFL, out = MemoryUtil.memGetInt(t + 28) & 0xFFFFFFFFL;
			b.append(String.format("; %s: table lists %d, fallback lists %d region / %d listCap, blocks %d, units %d, real quads %d, input slots %d, emitted %d, removed %d",
				names[k], MemoryUtil.memGetInt(t), MemoryUtil.memGetInt(t + 4), MemoryUtil.memGetInt(t + 8), MemoryUtil.memGetInt(t + 12),
				MemoryUtil.memGetInt(t + 16), MemoryUtil.memGetInt(t + 20), in, out, in - out));
		}
		b.append(String.format("; draws drawn as class draws (no pipeline / no table) %d, table allocation failed %d, table %d KB a set (%d blocks a list)",
			MemoryUtil.memGetInt(this.exactTel + 96), MemoryUtil.memGetInt(this.exactTel + 100), MemoryUtil.memGetInt(this.exactTel + 104) / 1024,
			MemoryUtil.memGetInt(this.exactTel + 108)));
		System.out.println(b);
	}

	private void oracleBegin() {
		int cap = this.terrain.recCapacity();
		if (cap > this.oCap) {
			long oldK = this.oKept, oldV = this.oVis;
			if (oldK != 0) this.terrain.later(() -> {
				OwnNative.release(oldK);
				OwnNative.release(oldV);
			});
			if (this.oTest != 0) {
				long oldT = this.oTest;
				this.terrain.later(() -> OwnNative.release(oldT));
			}
			this.oTest = OwnNative.buffer(this.ctx, 4L * cap, 2);
			this.oTestAddr = OwnNative.contents(this.oTest);
			MemoryUtil.memSet(this.oTestAddr, 0, 4L * cap);
			this.oKept = OwnNative.buffer(this.ctx, 4L * cap, 2);
			this.oVis = OwnNative.buffer(this.ctx, 4L * cap, 2);
			this.oKeptAddr = OwnNative.contents(this.oKept);
			this.oVisAddr = OwnNative.contents(this.oVis);
			MemoryUtil.memSet(this.oKeptAddr, 0, 4L * cap);
			MemoryUtil.memSet(this.oVisAddr, 0, 4L * cap);
			this.oCap = cap;
			this.oSampleAt = -1;
		}
		this.oFrame++;
		OwnNative.oracle(this.own, this.oKept, this.oVis, this.oFrame);
	}

	private void oracleLists(Config c, int base, long d, long arena, long table, long recs, long u, GpuTextureView atlas, GpuSampler sampler,
		GpuTextureView light, GpuSampler lightSampler, long lists, long args) {
		int k = (base & ~(K_MESH | K_MESH4)) | K_ORACLE | K_EQUAL;
		this.layer(c, 0, k, d, arena, table, recs, u, atlas, sampler, light, lightSampler, lists, args);
		this.layer(c, 1, k | 1, d, arena, table, recs, u, atlas, sampler, light, lightSampler, lists, args);
	}

	private void oracleEnd(Config c) {
		this.oSampleAt = this.oDraws;
		this.oCamX = this.oCullX;
		this.oCamY = this.oCullY;
		this.oCamZ = this.oCullZ;
		this.oConfig = c;
	}

	private static final double[] O_DIST = {16, 32, 64, 128, 256};
	private static final int[] O_EXT = {1, 2, 4, 8, 16};

	private void oracleRead() {
		int s = this.oFrame, n = Math.min(this.oCap, this.terrain.recCapacity()), slots = this.terrain.slotCapacity();
		long recs = this.terrain.recAddress(), master = this.terrain.masterAddress();
		long[] drawnU = new long[2], drawnQ = new long[2], visU = new long[2], visQ = new long[2];
		long[][] hd = new long[2][O_DIST.length + 1], he = new long[2][O_EXT.length + 1];
		long[][] hdAll = new long[2][O_DIST.length + 1], heAll = new long[2][O_EXT.length + 1];
		long[][] hStale = new long[2][O_DIST.length + 1];
		boolean tested = this.oConfig != null && this.oConfig.uocc && this.oTested;
		for (int r = 0; r < n; r++) {
			if (MemoryUtil.memGetInt(this.oKeptAddr + 4L * r) != s) continue;
			long a = recs + (long) r * OwnTerrain.REC_BYTES;
			int meta = MemoryUtil.memGetInt(a + 8), slot = MemoryUtil.memGetInt(a + 4), lo = MemoryUtil.memGetInt(a + 16), hi = MemoryUtil.memGetInt(a + 20);
			int quads = (meta & 63) + 1, layer = Math.min(1, (meta >> 9) & 3);
			boolean vis = MemoryUtil.memGetInt(this.oVisAddr + 4L * r) == s;
			drawnU[layer]++;
			drawnQ[layer] += quads;
			if (vis) {
				visU[layer]++;
				visQ[layer] += quads;
			}
			if (slot < 0 || slot >= slots) continue;
			long m = master + (long) slot * OwnTerrain.SECTION_BYTES;
			int lx = (lo & 255) - 16, ly = (lo >>> 8 & 255) - 16, lz = (lo >>> 16 & 255) - 16, hx = (hi & 255) - 16, hy = (hi >>> 8 & 255) - 16, hz = (hi >>> 16 & 255) - 16;
			double cx = MemoryUtil.memGetInt(m) + (lx + hx) / 2.0, cy = MemoryUtil.memGetInt(m + 4) + (ly + hy) / 2.0, cz = MemoryUtil.memGetInt(m + 8) + (lz + hz) / 2.0;
			double dist = Math.sqrt((cx - this.oCamX) * (cx - this.oCamX) + (cy - this.oCamY) * (cy - this.oCamY) + (cz - this.oCamZ) * (cz - this.oCamZ));
			int ext = Math.max(hx - lx, Math.max(hy - ly, hz - lz));
			int db = 0, eb = 0;
			while (db < O_DIST.length && dist >= O_DIST[db]) db++;
			while (eb < O_EXT.length && ext > O_EXT[eb]) eb++;
			hdAll[0][db]++;
			hdAll[1][db] += quads;
			heAll[0][eb]++;
			heAll[1][eb] += quads;
			if (!vis) {
				hd[0][db]++;
				hd[1][db] += quads;
				he[0][eb]++;
				he[1][eb] += quads;
				if (tested && MemoryUtil.memGetInt(this.oTestAddr + 4L * r) != this.oEpoch + 1) {
					hStale[0][db]++;
					hStale[1][db] += quads;
				}
			}
		}
		long du = drawnU[0] + drawnU[1], dq = drawnQ[0] + drawnQ[1], vu = visU[0] + visU[1], vq = visQ[0] + visQ[1];
		System.out.printf("mcopt-own oracle: sample %d (%s): drawn units %d quads %d (solid %d/%d, cutout %d/%d); visible units %d quads %d (%.1f%% of drawn quads; solid %d/%d, cutout %d/%d)%n",
			s, this.oConfig, du, dq, drawnU[0], drawnQ[0], drawnU[1], drawnQ[1], vu, vq, dq > 0 ? 100.0 * vq / dq : 0, visU[0], visQ[0], visU[1], visQ[1]);
		StringBuilder b = new StringBuilder("mcopt-own oracle: drawn but invisible by unit-centre distance (units/quads of drawn units/quads):");
		String[] dn = {"<16", "16-32", "32-64", "64-128", "128-256", ">=256"};
		for (int i = 0; i <= O_DIST.length; i++) b.append(String.format(" %s %d/%d of %d/%d", dn[i], hd[0][i], hd[1][i], hdAll[0][i], hdAll[1][i]));
		System.out.println(b);
		b = new StringBuilder("mcopt-own oracle: drawn but invisible by box extent in blocks (units/quads of drawn units/quads):");
		String[] en = {"<=1", "2", "3-4", "5-8", "9-16", ">16"};
		for (int i = 0; i <= O_EXT.length; i++) b.append(String.format(" %s %d/%d of %d/%d", en[i], he[0][i], he[1][i], heAll[0][i], heAll[1][i]));
		System.out.println(b);
		StringBuilder m = new StringBuilder("mcopt-own oracle data: uocc=").append(this.oConfig != null && this.oConfig.uocc ? 1 : 0)
			.append(" drawnU=").append(du).append(" drawnQ=").append(dq).append(" visU=").append(vu).append(" visQ=").append(vq);
		for (int i = 0; i <= O_DIST.length; i++) m.append(" invU").append(i).append('=').append(hd[0][i]).append(" invQ").append(i).append('=').append(hd[1][i])
			.append(" allU").append(i).append('=').append(hdAll[0][i]).append(" allQ").append(i).append('=').append(hdAll[1][i]);
		if (tested) for (int i = 0; i <= O_DIST.length; i++) m.append(" staleU").append(i).append('=').append(hStale[0][i]).append(" staleQ").append(i).append('=').append(hStale[1][i]);
		System.out.println(m);
	}

	private void stats(Config c) {
		int classes = c.classes();
		for (int layer = 0; layer < 2; layer++) {
			for (int cls = 0; cls < classes; cls++) {
				for (int lean = 0; lean < 2; lean++) {
					int id = (layer * classes + cls) * 2 + lean, units = MemoryUtil.memGetInt(this.argsAddress + id * 20L + 4);
					if (layer == 0) this.statSolid += units;
					else this.statCutout += units;
					if (lean == 1) this.statLean += units;
				}
			}
		}
		for (int bank = 0; bank < (c.uocc && SPLIT_NEAR > 0 ? 2 : 1); bank++) {
			for (int layer = 0; layer < 2; layer++) {
				for (int cls = 0; cls < classes; cls++) {
					for (int lean = 0; lean < 2; lean++) {
						long a = this.argsAddress + (bank * (long) c.listCount() + (layer * classes + cls) * 2 + lean) * 20L;
						this.statSlots[bank * 4 + layer * 2 + lean] += (long) (MemoryUtil.memGetInt(a) / 6) * MemoryUtil.memGetInt(a + 4);
					}
				}
			}
		}
		for (int i = 0; i < 14; i++) this.statBuckets[i] += MemoryUtil.memGetInt(this.argsAddress + (lists(c) * 8L + i) * 4);
		if (c.occ) for (int i = 0; i < 4; i++) this.statOcc[i] += MemoryUtil.memGetInt(this.argsAddress + (lists(c) * 8L + 14 + i) * 4);
		if (FACESTATS) for (int i = 0; i < 5; i++) this.statFace[i] += MemoryUtil.memGetInt(this.argsAddress + (lists(c) * 8L + 22 + i) * 4);
		this.statFunnelIn += MemoryUtil.memGetInt(this.argsAddress + (lists(c) * 8L + 27) * 4);
		this.statFunnelFacing += MemoryUtil.memGetInt(this.argsAddress + (lists(c) * 8L + 28) * 4);
		if (c.uocc) {
			this.statTested += MemoryUtil.memGetInt(this.argsAddress + (lists(c) * 8L + 18) * 4);
			this.statA1Quads += MemoryUtil.memGetInt(this.argsAddress + (lists(c) * 8L + 29) * 4) & 0xFFFFFFFFL;
			this.statA1Units += MemoryUtil.memGetInt(this.argsAddress + (lists(c) * 8L + 30) * 4) & 0xFFFFFFFFL;
			this.statA1Over += MemoryUtil.memGetInt(this.argsAddress + (lists(c) * 8L + 31) * 4) & 0xFFFFFFFFL;
			for (int id = 0; id < c.classes() * 2; id++)
				this.statA1Slots += (long) (MemoryUtil.memGetInt(this.argsAddress + id * 20L) / 6) * MemoryUtil.memGetInt(this.argsAddress + id * 20L + 4);
			for (int i = 0; i < lists(c); i++) this.statB += MemoryUtil.memGetInt(this.bArgsAddress + i * 20L + 4);
			this.statQuadsA += MemoryUtil.memGetInt(this.argsAddress + (lists(c) * 8L + 14) * 4);
			this.statQuadsB += MemoryUtil.memGetInt(this.bArgsAddress + lists(c) * 20L);
			int rd = MemoryUtil.memGetInt(this.bArgsAddress + lists(c) * 20L + 16);
			this.statRedraw += rd;
			this.statRedrawQuads += MemoryUtil.memGetInt(this.bArgsAddress + lists(c) * 20L + 20);
			if (rd > 0) this.statRedrawFrames++;
		}
		this.statFrames++;
		long now = System.nanoTime();
		if (now - this.statLast < 5_000_000_000L) return;
		System.out.printf("mcopt-own-frag stats: %s, units a frame solid %.0f cutout %.0f, lean %.0f%n", c, (double) this.statSolid / this.statFrames,
			(double) this.statCutout / this.statFrames, (double) this.statLean / this.statFrames);
		StringBuilder b = new StringBuilder("mcopt-own-frag buckets (units/quads a frame, +X -X +Y -Y +Z -Z any):");
		for (int i = 0; i < 7; i++) b.append(String.format(" %.0f/%.0f", (double) this.statBuckets[i * 2] / this.statFrames, (double) this.statBuckets[i * 2 + 1] / this.statFrames));
		System.out.println(b);
		{
			long listed = 0;
			for (int i = 0; i < 7; i++) listed += this.statBuckets[i * 2 + 1];
			System.out.printf("mcopt-own-frag funnel: quads a frame in the sections passed in %.0f, after the facing test %.0f, after the unit box test %.0f%n",
				(double) this.statFunnelIn / this.statFrames, (double) this.statFunnelFacing / this.statFrames, (double) listed / this.statFrames);
			this.statFunnelIn = this.statFunnelFacing = 0;
		}
		if (FACESTATS) {
			double n = this.statFrames, quads = this.statFace[0] / n, back = (this.statFace[1] + this.statFace[2]) / n;
			System.out.printf("mcopt-own-frag faces: quads listed a frame %.0f, back-facing %.0f (%.2f%%): in facing buckets %.0f, in the any bucket %.0f of its %.0f; degenerate %.0f%n",
				quads, back, quads > 0 ? 100 * back / quads : 0, this.statFace[1] / n, this.statFace[2] / n, this.statFace[3] / n, this.statFace[4] / n);
			java.util.Arrays.fill(this.statFace, 0);
		}
		if (c.uocc) {
			long listed = 0;
			for (int i = 0; i < 7; i++) listed += this.statBuckets[i * 2 + 1];
			System.out.printf("mcopt-own-frag uocc: units a frame tested %.0f, phase A %.0f, phase B %.0f; quads drawn A %.0f + B %.0f of %.0f listed%n",
				(double) this.statTested / this.statFrames, (double) (this.statSolid + this.statCutout) / this.statFrames, (double) this.statB / this.statFrames,
				(double) this.statQuadsA / this.statFrames, (double) this.statQuadsB / this.statFrames, (double) listed / this.statFrames);
			if (OwnProbe.bool("frag.bRedraw", B_REDRAW) || this.statRedraw > 0) {
				System.out.printf("mcopt-own-frag bRedraw: phase A cutout units redrawn in B %.1f a frame (%.0f quads), in %.1f%% of frames%n",
					(double) this.statRedraw / this.statFrames, (double) this.statRedrawQuads / this.statFrames, 100.0 * this.statRedrawFrames / this.statFrames);
			}
			this.statTested = this.statB = this.statQuadsA = this.statQuadsB = 0;
			System.out.printf("mcopt-own-frag a1: %s, a frame: units %.0f, real quads %.0f, class draws' slots %.0f (%.3f a real quad), overflowed lists %.2f%n", this.a1Exact ? "table draws" : "class draws",
				this.statA1Units / (double) this.statFrames, this.statA1Quads / (double) this.statFrames, this.statA1Slots / (double) this.statFrames,
				this.statA1Quads > 0 ? this.statA1Slots / (double) this.statA1Quads : 0, this.statA1Over / (double) this.statFrames);
			this.statA1Over = 0;
			this.statA1Quads = this.statA1Units = this.statA1Slots = 0;
			this.statRedraw = this.statRedrawQuads = this.statRedrawFrames = 0;
			double n = this.statFrames;
			long[] q = this.statSlots;
			System.out.printf("mcopt-own-frag slots at %d (quad slots a frame, rich/lean): near solid %.0f/%.0f, far solid %.0f/%.0f, near cutout %.0f/%.0f, far cutout %.0f/%.0f%n",
				System.currentTimeMillis(), q[0] / n, q[1] / n, q[4] / n, q[5] / n, q[2] / n, q[3] / n, q[6] / n, q[7] / n);
			java.util.Arrays.fill(this.statSlots, 0);
			if ((Integer.getInteger("mcopt.own.frag.uoccDebug", 0) & 8) != 0) {
				long diff = 0, tested = 0, vis = 0;
				for (long a : new long[] {this.bArgsAddress, this.pBArgsAddress}) {
					if (a == 0) continue;
					diff += MemoryUtil.memGetInt(a + lists(c) * 20L + 4) & 0xFFFFFFFFL;
					tested += MemoryUtil.memGetInt(a + lists(c) * 20L + 8) & 0xFFFFFFFFL;
					vis += MemoryUtil.memGetInt(a + lists(c) * 20L + 12) & 0xFFFFFFFFL;
				}
				System.out.printf("mcopt-own-frag testFast verify: units tested %d; frag_uocc_test passes, testFast doesn't: %d (must be 0); testFast passes, frag_uocc_test doesn't: %d%n", tested, diff, vis);
			}
		}
		if (c.occ) {
			System.out.printf("mcopt-own-frag occ: quads a frame, phase A solid %.0f cutout %.0f, phase B solid %.0f cutout %.0f%n", (double) this.statOcc[0] / this.statFrames,
				(double) this.statOcc[1] / this.statFrames, (double) this.statOcc[2] / this.statFrames, (double) this.statOcc[3] / this.statFrames);
			java.util.Arrays.fill(this.statOcc, 0);
		}
		java.util.Arrays.fill(this.statBuckets, 0);
		this.statFrames = this.statSolid = this.statCutout = this.statLean = 0;
		this.statLast = now;
	}
}
