// Modified for Russet: fragTOcc and fragTReady, for see-through sections tested against the depth pyramid.
package mcopt.metal.own;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.invoke.MethodHandle;
import mcopt.metal.MetalBridge;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

final class OwnNative {
	private static final Linker LINKER = Linker.nativeLinker();

	private static final MethodHandle TL_RESTART = fn("mc_tl_restart", false, JAVA_INT, JAVA_LONG, JAVA_INT);
	private static final MethodHandle NEW = fn("mco_new", false, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_INT);
	private static final MethodHandle BUFFER = fn("mco_buffer", false, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle CONTENTS = fn("mco_contents", true, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle RELEASE = fn("mco_release", false, null, JAVA_LONG);
	private static final MethodHandle CULL = fn("mco_cull", false, null, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG,
		JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT);
	private static final MethodHandle OCC = fn("mco_occ", false, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG,
		JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle DRAW = fn("mco_draw", false, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG,
		JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle QUADS_A = fn("mco_quads_a", false, null, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG,
		JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle QUADS_B = fn("mco_quads_b", false, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG,
		JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG);

	private static final MethodHandle SOLID_OCC = fn("mco_solid_occ", false, null, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT);
	private static final MethodHandle QSTATS = fn("mco_qstats", false, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG,
		JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle QUADS_PRE = fn("mco_quads_pre", false, null, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG,
		JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle GPUTIME = fn("mco_set_gputime", false, null, JAVA_INT);
	private static final MethodHandle QREC_VERIFY = fn("mco_qrec_verify", false, null, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle HIZ_TILED = fn("mco_set_hiz_tiled", false, null, JAVA_INT);
	private static final MethodHandle SPLIT = fn("mco_split", false, JAVA_INT, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle FRAG_OCC = fn("mco_frag_occ", false, JAVA_INT, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle FRAG_MASKS = fn("mco_frag_masks", false, null, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle LAST_COMPUTE_GROUP = fn("mc_last_compute_group", false, JAVA_INT);
	private static final MethodHandle FRAG_FACE = fn("mco_frag_face", false, null, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle ORACLE = fn("mco_oracle", false, null, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle SET_VCOUNT = fn("mco_set_vcount", false, null, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle ORACLE_TEST = fn("mco_oracle_test", false, null, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle FRAG_TIE_RING = fn("mco_frag_tie_ring", false, null, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle FRAG_CLEAR = fn("mco_frag_clear", false, null, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle FRAG_TIE_VERIFY = fn("mco_frag_tie_verify", false, null, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle FRAG_UOCC_BUFFERS = fn("mco_frag_uocc_buffers", false, null, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle FRAG_T_OCC = fn("mco_frag_t_occ", false, null, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle FRAG_T_READY = fn("mco_frag_t_ready", false, JAVA_INT, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle FRAG_A1_EXACT = fn("mco_frag_a1_exact", false, null, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
	private static final MethodHandle FRAG_EXACT_MASK = fn("mco_frag_exact_mask", false, null, JAVA_LONG, JAVA_INT, JAVA_INT);
	private static final MethodHandle FRAG_EXACT_TELEMETRY = fn("mco_frag_exact_telemetry", false, null, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle FRAG_UOCC = fn("mco_frag_uocc", false, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG,
		JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_INT);
	private static final MethodHandle FRAG_CULL = fn("mco_frag_cull", false, null, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG,
		JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT);

	private OwnNative() {
	}

	private static MethodHandle fn(String name, boolean critical, MemoryLayout result, MemoryLayout... args) {
		FunctionDescriptor d = result == null ? FunctionDescriptor.ofVoid(args) : FunctionDescriptor.of(result, args);
		var symbol = MetalBridge.library().find(name).orElseThrow(() -> new IllegalStateException("missing native symbol " + name));
		return critical ? LINKER.downcallHandle(symbol, d, Linker.Option.critical(false)) : LINKER.downcallHandle(symbol, d);
	}

	private static RuntimeException rethrow(Throwable t) {
		if (t instanceof RuntimeException r) return r;
		if (t instanceof Error e) throw e;
		return new IllegalStateException(t);
	}

	static long create(long ctx, String source, boolean compact, boolean fat, boolean cpuClip) {
		if (OwnCapture.ON) {
			mcopt.metal.FrameCapture.Own.compact = compact;
			mcopt.metal.FrameCapture.Own.fat = fat;
			mcopt.metal.FrameCapture.Own.cpuClip = cpuClip;
			mcopt.metal.FrameCapture.Own.exactPos = source.startsWith("#define OWN_EXACT_POS 1\n");
			int at = 0;
			while (source.startsWith("#define ", at) && source.indexOf('\n', at) > 0) at = source.indexOf('\n', at) + 1;
			mcopt.metal.FrameCapture.Own.prefix = source.substring(0, at);
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long err = stack.nmalloc(1, 16384);
			MemoryUtil.memPutByte(err, (byte) 0);
			long src = MemoryUtil.memAddress(MemoryUtil.memUTF8(source));
			long own;
			try {
				own = (long) NEW.invokeExact(ctx, src, compact ? 1 : 0, fat ? 1 : 0, cpuClip ? 1 : 0, err, 16384);
			} catch (Throwable t) {
				throw rethrow(t);
			}
			if (own == 0) throw new IllegalStateException(MemoryUtil.memUTF8(err));
			return own;
		}
	}

	static long buffer(long ctx, long size, boolean shared) {
		return buffer(ctx, size, shared ? 1 : 0);
	}

	static long buffer(long ctx, long size, int kind) {
		try {
			long b = (long) BUFFER.invokeExact(ctx, size, kind);
			if (b != 0) {
				LIVE.put(b, size);
				LIVE_BYTES.addAndGet(size);
			}
			return b;
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	private static final java.util.concurrent.ConcurrentHashMap<Long, Long> LIVE = new java.util.concurrent.ConcurrentHashMap<>();
	private static final java.util.concurrent.atomic.AtomicLong LIVE_BYTES = new java.util.concurrent.atomic.AtomicLong();

	static long liveBufferBytes() {
		return LIVE_BYTES.get();
	}

	static int liveBuffers() {
		return LIVE.size();
	}

	static long contents(long buffer) {
		try {
			return (long) CONTENTS.invokeExact(buffer);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void release(long obj) {
		Long n = LIVE.remove(obj);
		if (n != null) LIVE_BYTES.addAndGet(-n);
		try {
			RELEASE.invokeExact(obj);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void cull(long own, long enc, long frame, int frameLength, long sections, long recs, long args, long lists, long mask, int sectionCount,
		long prevVis, long curVis, long tested, int visWords, long fat) {
		cull(own, enc, frame, frameLength, sections, recs, args, lists, mask, sectionCount, prevVis, curVis, tested, visWords, fat, 0);
	}

	static void cull(long own, long enc, long frame, int frameLength, long sections, long recs, long args, long lists, long mask, int sectionCount,
		long prevVis, long curVis, long tested, int visWords, long fat, int recThreads) {
		if (OwnCapture.ON) OwnCapture.unsupported("cull");
		try {
			CULL.invokeExact(own, enc, frame, frameLength, sections, recs, args, lists, mask, sectionCount, prevVis, curVis, tested, visWords, fat, recThreads);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static int occ(long own, long enc, long frame, int frameLength, long sections, long recs, long args, long lists, long curVis, long tested, long hiz,
		long fat, int stage) {
		if (OwnCapture.ON) OwnCapture.unsupported("occ");
		try {
			return (int) OCC.invokeExact(own, enc, frame, frameLength, sections, recs, args, lists, curVis, tested, hiz, fat, stage);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	private static final MethodHandle PRE_COMMIT = fn("mco_pre_commit", false, null, JAVA_LONG);
	private static final MethodHandle TL_A1SPLIT_REQ = fn("mco_tl_a1split_req", false, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT);
	private static final MethodHandle TL_A1SPLIT_WRITTEN = fn("mco_tl_a1split_written", false, JAVA_INT, JAVA_LONG);
	private static final MethodHandle TL_A1SPLIT_TEST = fn("mco_tl_a1split_test", false, null, JAVA_INT);
	private static final MethodHandle VGROUP_FAIL_TEST = fn("mco_vgroup_fail_test", false, null, JAVA_INT, JAVA_INT);
	private static final MethodHandle VGROUP_STATS = fn("mco_vgroup_stats", false, null, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle TL_RESTART_OK = fn("mc_tl_restart_ok", false, JAVA_INT, JAVA_LONG);
	private static final MethodHandle ANIM_COPY_MIPS = fn("mco_anim_copy_mips", false, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle ANIM_BLIT_MIPS = fn("mco_anim_blit_mips", false, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle ANIM_KERNEL_READY = fn("mco_anim_kernel_ready", false, JAVA_INT, JAVA_LONG);
	private static final MethodHandle ANIM_COPY = fn("mco_anim_copy", false, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT);

	static boolean animKernelReady(long own) {
		try {
			return (int) ANIM_KERNEL_READY.invokeExact(own) != 0;
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static boolean animBlitMips(long own, long enc, long src, long dst, long rects, int count) {
		String e0 = OwnCapture.ON && OwnCapture.active() ? OwnCapture.pre(enc) : null;
		int r;
		try {
			r = (int) ANIM_BLIT_MIPS.invokeExact(own, enc, src, dst, rects, count);
		} catch (Throwable t) {
			throw rethrow(t);
		}
		if (e0 != null) OwnCapture.anim(e0, enc, "blitMips", src, dst, 0, rects, 7 * count, count, 0, 0, r);
		return r != 0;
	}

	static boolean animCopyMips(long own, long enc, long src, long dst, long rects, int count) {
		String e0 = OwnCapture.ON && OwnCapture.active() ? OwnCapture.pre(enc) : null;
		int r;
		try {
			r = (int) ANIM_COPY_MIPS.invokeExact(own, enc, src, dst, rects, count);
		} catch (Throwable t) {
			throw rethrow(t);
		}
		if (e0 != null) OwnCapture.anim(e0, enc, "copyMips", src, dst, 0, rects, 7 * count, count, 0, 0, r);
		return r != 0;
	}

	static boolean animCopy(long own, long enc, long src, long dst, int mip, long rects, int count, int maxW, int maxH) {
		String e0 = OwnCapture.ON && OwnCapture.active() ? OwnCapture.pre(enc) : null;
		int r;
		try {
			r = (int) ANIM_COPY.invokeExact(own, enc, src, dst, mip, rects, count, maxW, maxH);
		} catch (Throwable t) {
			throw rethrow(t);
		}
		if (e0 != null) OwnCapture.anim(e0, enc, "copy", src, dst, mip, rects, 6 * count, count, maxW, maxH, r);
		return r != 0;
	}

	static boolean tlA1SplitReq(long own, long out, int list, int num, int den) {
		try {
			return (int) TL_A1SPLIT_REQ.invokeExact(own, out, list, num, den) == 1;
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static boolean tlA1SplitWritten(long own) {
		try {
			return (int) TL_A1SPLIT_WRITTEN.invokeExact(own) == 1;
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void tlA1SplitTest(boolean fail) {
		try {
			TL_A1SPLIT_TEST.invokeExact(fail ? 1 : 0);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void vGroupStats(long own, long out) {
		try {
			VGROUP_STATS.invokeExact(own, out);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void vGroupFailTest(boolean a, boolean b) {
		try {
			VGROUP_FAIL_TEST.invokeExact(a ? 1 : 0, b ? 1 : 0);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static int tlRestartOk(long enc) {
		try {
			return (int) TL_RESTART_OK.invokeExact(enc);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void preCommit(long enc) {
		String e0 = OwnCapture.ON && OwnCapture.active() ? OwnCapture.pre(enc) : null;
		try {
			PRE_COMMIT.invokeExact(enc);
		} catch (Throwable t) {
			throw rethrow(t);
		}
		if (e0 != null) OwnCapture.preCommit(e0, enc);
	}

	static void fragCull(long own, long enc, long frame, int frameLength, long fragFrame, int fragLength, long sections, long recs, long args, long lists,
		long mask, int sectionCount, int listCount) {
		fragCull(own, enc, frame, frameLength, fragFrame, fragLength, sections, recs, args, lists, mask, sectionCount, listCount, 0, 0, 0, 0, 0);
	}

	static void fragCull(long own, long enc, long frame, int frameLength, long fragFrame, int fragLength, long sections, long recs, long args, long lists,
		long mask, int sectionCount, int listCount, int lanes, long vArgs, long vLists, long sig, int listCap) {
		String e0 = null;
		if (OwnCapture.ON) {
			OwnCapture.frameStart();
			if (OwnCapture.active()) e0 = OwnCapture.pre(enc);
		}
		try {
			FRAG_CULL.invokeExact(own, enc, frame, frameLength, fragFrame, fragLength, sections, recs, args, lists, mask, sectionCount, listCount, lanes, vArgs,
				vLists, sig, listCap);
		} catch (Throwable t) {
			throw rethrow(t);
		}
		if (e0 != null) OwnCapture.fragCull(e0, enc, frame, frameLength, fragFrame, fragLength, sections, recs, args, lists, mask, sectionCount, listCount, lanes,
			vArgs, vLists, sig, listCap);
	}

	static void solidOcc(long own, long enc, long params, int length, long cols, int width, int height, int span) {
		if (OwnCapture.ON && length > 0) OwnCapture.unsupported("solidOcc");
		try {
			SOLID_OCC.invokeExact(own, enc, params, length, cols, width, height, span);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static int qstats(long own, long enc, long frame, int frameLength, long sections, long recs, long args, long lists, long arena, long stats, int threads) {
		if (OwnCapture.ON) OwnCapture.unsupported("qstats");
		try {
			return (int) QSTATS.invokeExact(own, enc, frame, frameLength, sections, recs, args, lists, arena, stats, threads);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void quadsPre(long own, long enc, long frame, int frameLength, long sections, long recs, long args, long tested, long idx, long qargs, long arena) {
		if (OwnCapture.ON) OwnCapture.unsupported("quadsPre");
		try {
			QUADS_PRE.invokeExact(own, enc, frame, frameLength, sections, recs, args, tested, idx, qargs, arena);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void qrecVerify(long own, long enc, long sections, long recs, long arena, long stats, int slots) {
		if (OwnCapture.ON) OwnCapture.unsupported("qrecVerify");
		try {
			QREC_VERIFY.invokeExact(own, enc, sections, recs, arena, stats, slots);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void setGpuTime(boolean on) {
		try {
			GPUTIME.invokeExact(on ? 1 : 0);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void setHizTiled(boolean on, boolean top, boolean gather) {
		if (OwnCapture.ON) mcopt.metal.FrameCapture.Own.hizBits = (on ? 1 : 0) | (top ? 2 : 0) | (gather ? 4 : 0) | (Integer.getInteger("mcopt.own.frag.uoccDebug", 0) & 255) << 8
			| (Boolean.getBoolean("mcopt.own.frag.uoccEnc") ? 1 << 16 : 0) | (Boolean.getBoolean("mcopt.own.frag.testFast") ? 1 << 17 : 0)
			| (Boolean.getBoolean("mcopt.own.frag.testBox") ? 1 << 18 : 0) | (Boolean.getBoolean("mcopt.own.frag.testGather") ? 1 << 19 : 0);
		try {
			HIZ_TILED.invokeExact((on ? 1 : 0) | (top ? 2 : 0) | (gather ? 4 : 0) | (Integer.getInteger("mcopt.own.frag.uoccDebug", 0) & 255) << 8
				| (Boolean.getBoolean("mcopt.own.frag.uoccEnc") ? 1 << 16 : 0)
				| (OwnProbe.bool("frag.testFast", Boolean.getBoolean("mcopt.own.frag.testFast")) ? 1 << 17 : 0)
				| (Boolean.getBoolean("mcopt.own.frag.testBox") ? 1 << 18 : 0)
				| (OwnProbe.bool("frag.testGather", Boolean.getBoolean("mcopt.own.frag.testGather")) ? 1 << 19 : 0));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static int split(long own, long enc) {
		String e0 = OwnCapture.ON && OwnCapture.active() ? OwnCapture.pre(enc) : null;
		int r;
		try {
			r = (int) SPLIT.invokeExact(own, enc);
		} catch (Throwable t) {
			throw rethrow(t);
		}
		if (e0 != null) OwnCapture.split(e0, enc, r);
		return r;
	}

	static int fragOcc(long own, long enc) {
		if (OwnCapture.ON) OwnCapture.unsupported("fragOcc");
		try {
			return (int) FRAG_OCC.invokeExact(own, enc);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static int lastComputeGroup() {
		try {
			return (int) LAST_COMPUTE_GROUP.invokeExact();
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void fragFace(long own, long arena, boolean on) {
		if (OwnCapture.ON) OwnCapture.unsupported("fragFace");
		try {
			FRAG_FACE.invokeExact(own, arena, on ? 1 : 0);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void setVcount(long own, long counters) {
		if (OwnCapture.ON) OwnCapture.unsupported("setVcount");
		try {
			SET_VCOUNT.invokeExact(own, counters);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void oracle(long own, long kept, long vis, int frame) {
		if (OwnCapture.ON) OwnCapture.unsupported("oracle");
		try {
			ORACLE.invokeExact(own, kept, vis, frame);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void oracleTest(long own, long test) {
		if (OwnCapture.ON) OwnCapture.unsupported("oracleTest");
		try {
			ORACLE_TEST.invokeExact(own, test);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void fragExactMask(long own, int lo, int hi) {
		try {
			if (OwnCapture.ON && OwnCapture.active()) OwnCapture.fragExactMask(lo, hi);
			FRAG_EXACT_MASK.invokeExact(own, lo, hi);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void fragExactTelemetry(long own, long out) {
		try {
			FRAG_EXACT_TELEMETRY.invokeExact(own, out);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void fragA1Exact(long own, int mode, int lists, int listCap, int set) {
		try {
			if (OwnCapture.ON && OwnCapture.active()) OwnCapture.fragA1Exact(mode, lists, listCap, set);
			FRAG_A1_EXACT.invokeExact(own, mode, lists, listCap, set);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void fragTieRing(long own, long ring) {
		try {
			if (OwnCapture.ON && OwnCapture.active()) OwnCapture.fragTieRing(ring);
			FRAG_TIE_RING.invokeExact(own, ring);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void fragClear(long own, long buffer) {
		try {
			if (OwnCapture.ON && OwnCapture.active()) OwnCapture.fragClear(buffer);
			FRAG_CLEAR.invokeExact(own, buffer);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void fragTieVerify(long own, long lists, boolean on) {
		try {
			if (OwnCapture.ON && OwnCapture.active()) OwnCapture.fragTieVerify(lists, on);
			FRAG_TIE_VERIFY.invokeExact(own, lists, on ? 1 : 0);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void fragUoccBuffers(long own, long occVis, long tList, long bArgs, long listsB) {
		if (OwnCapture.ON && OwnCapture.active()) OwnCapture.fragUoccBuffers(occVis, tList, bArgs, listsB);
		try {
			FRAG_UOCC_BUFFERS.invokeExact(own, occVis, tList, bArgs, listsB);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void fragTOcc(long own, long head, long headAt, long runs, long vis, long list, long out, long args, long argsAt) {
		try {
			FRAG_T_OCC.invokeExact(own, head, headAt, runs, vis, list, out, args, argsAt);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static boolean fragTReady(long own, long enc) {
		try {
			return (int) FRAG_T_READY.invokeExact(own, enc) != 0;
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static int fragUocc(long own, long enc, long cullFrame, int cullLength, long fragFrame, int fragLength, long clip, long sections, long recs, long args,
		int lists, int fine) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long err = stack.nmalloc(1, 2048);
			MemoryUtil.memPutByte(err, (byte) 0);
			int r;
			String e0 = OwnCapture.ON && OwnCapture.active() ? OwnCapture.pre(enc) : null;
			try {
				r = (int) FRAG_UOCC.invokeExact(own, enc, cullFrame, cullLength, fragFrame, fragLength, clip, sections, recs, args, lists, fine, err, 2048);
			} catch (Throwable t) {
				throw rethrow(t);
			}
			if (e0 != null) OwnCapture.fragUocc(e0, enc, cullFrame, cullLength, fragFrame, fragLength, clip, sections, recs, args, lists, fine, r);
			if (r < 0) throw new IllegalStateException(MemoryUtil.memUTF8(err));
			return r;
		}
	}

	static void fragMasks(long own, long masks, long stats, long statsOffset, boolean on) {
		if (OwnCapture.ON && OwnCapture.active()) OwnCapture.fragMasks(masks, stats, statsOffset, on);
		try {
			FRAG_MASKS.invokeExact(own, masks, stats, statsOffset, on ? 1 : 0);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static int draw(long own, long enc, int kind, long frame, int frameLength, long arena, long sections, long recs, long lists, long args, long argsOffset,
		long uniforms, long atlas, long atlasSampler, long light, long lightSampler) {
		return draw(own, enc, kind, frame, frameLength, arena, sections, recs, lists, args, argsOffset, uniforms, atlas, atlasSampler, light, lightSampler, 0);
	}

	static void quadsA(long own, long enc, long frame, int frameLength, long recs, long args, long tested, long prevVis, long curVis, int visWords, long idx,
		long qargs, long tfat) {
		if (OwnCapture.ON) OwnCapture.unsupported("quadsA");
		try {
			QUADS_A.invokeExact(own, enc, frame, frameLength, recs, args, tested, prevVis, curVis, visWords, idx, qargs, tfat);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static int quadsB(long own, long enc, long frame, int frameLength, long sections, long recs, long args, long tested, long prevVis, long curVis, long idx,
		long qargs, long arena, long boxes, long tfat) {
		if (OwnCapture.ON) OwnCapture.unsupported("quadsB");
		try {
			return (int) QUADS_B.invokeExact(own, enc, frame, frameLength, sections, recs, args, tested, prevVis, curVis, idx, qargs, arena, boxes, tfat);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static int draw(long own, long enc, int kind, long frame, int frameLength, long arena, long sections, long recs, long lists, long args, long argsOffset,
		long uniforms, long atlas, long atlasSampler, long light, long lightSampler, long indices) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long err = stack.nmalloc(1, 2048);
			MemoryUtil.memPutByte(err, (byte) 0);
			int r;
			String e0 = OwnCapture.ON && OwnCapture.active() ? OwnCapture.pre(enc) : null;
			try {
				r = (int) DRAW.invokeExact(own, enc, kind, frame, frameLength, arena, sections, recs, lists, args, argsOffset, uniforms, atlas, atlasSampler, light,
					lightSampler, indices, err, 2048);
			} catch (Throwable t) {
				throw rethrow(t);
			}
			if (e0 != null) OwnCapture.draw(e0, enc, kind, frame, frameLength, arena, sections, recs, lists, args, argsOffset, uniforms, atlas, atlasSampler, light,
				lightSampler, indices, r);
			if (r < 0) throw new IllegalStateException(MemoryUtil.memUTF8(err));
			return r;
		}
	}
	static int tlRestart(long enc, int mode) {
		try {
			return (int) TL_RESTART.invokeExact(enc, mode);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

}
