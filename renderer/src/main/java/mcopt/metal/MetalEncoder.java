package mcopt.metal;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

final class MetalEncoder implements CommandEncoderBackend {
	private static final int MAX_IN_FLIGHT = Integer.getInteger("mcopt.metal.inFlight", 2);
	private static long TRACE_SUBMIT = TracePhase.fixed();
	private static final boolean ENC_LOG = Boolean.getBoolean("mcopt.own.int.encLog");
	private static final int PASS_TEST_N, PASS_TEST_MODE;
	static {
		String[] pt = System.getProperty("mcopt.metal.passTest", "0,0").split(",");
		PASS_TEST_N = Integer.parseInt(pt[0].trim());
		PASS_TEST_MODE = pt.length > 1 ? Integer.parseInt(pt[1].trim()) : 0;
	}
	private boolean passTestPending;
	private String[] encLabels = new String[128];
	private long[][] encStats = new long[128][4];
	private int encGroupOpen = -1;

	boolean encLogOn() {
		return ENC_LOG;
	}

	void encBind(MetalTexture t) {
		if (this.encGroupOpen < 0 || this.encGroupOpen >= 128) return;
		long[] st = this.encStats[this.encGroupOpen];
		st[1]++;
		if (t.encLastBind != this.submitIndex) st[2]++;
		if (t.encLastBind < this.submitIndex - 30) st[3]++;
		t.encLastBind = this.submitIndex;
	}
	private static final long CPU_COPY_MAX = 256 << 10;
	private static final int PROFILE_FRAMES = 100, PROFILE_ENCODERS = Math.max(1, Integer.getInteger("mcopt.metal.profileEncoders", 16));
	private static final boolean GPU_TIMES = Boolean.getBoolean("mcopt.metal.gpuTimes");
	static final boolean PRESENT_QUEUE = "true".equals(System.getProperty("mcopt.metal.presentQueue")) || "acquire".equals(System.getProperty("mcopt.metal.presentQueue"));
	static final boolean PRESENT_ACQUIRE = "acquire".equals(System.getProperty("mcopt.metal.presentQueue"));
	private static final boolean STATS = Boolean.getBoolean("mcopt.metal.stats");
	private static final boolean LABELS = Boolean.getBoolean("mcopt.metal.labels");
	private String passLabel = "";

	final long ctx;
	final long enc;
	final MetalTransientMemory transientMemory;
	final MetalTerrain terrain;
	private final ArrayDeque<Frame> inFlight = new ArrayDeque<>();
	private List<Runnable> afterThisFrame = new ArrayList<>();
	private final Set<MetalTexture> pendingClears = new HashSet<>();
	int @Nullable [] passFormats;
	static final boolean LAZY_CLEARS = Boolean.getBoolean("mcopt.metal.lazyClears");
	private long[] passColors = new long[8];
	private int passColorCount, passWidth, passHeight;
	private long passDepth;
	private boolean passLazy;
	private long lazySplits, lazySplitLog;
	private long submitIndex;
	private long completedIndex = -1;
	private int encoderIndex;
	private @Nullable MetalRenderPass currentPass;
	private final double[] profileSums = new double[1 + 2 * PROFILE_ENCODERS];
	private final double[] profileAt = new double[4 * PROFILE_ENCODERS];
	private final int[] profileAtN = new int[4 * PROFILE_ENCODERS];
	private int profiledFrames;
	private long waitNanos, statStart, statTerrainFrames;
	private int statFrames, statPresents;

	private record Frame(long index, long cmd, List<Runnable> after) {
	}

	private @Nullable GpuTexture subColor, subDepth;
	private int subWidth, subHeight;

	void setSubRect(@Nullable GpuTexture color, @Nullable GpuTexture depth, int width, int height) {
		boolean on = width > 0 && height > 0 && (color != null || depth != null);
		this.subColor = on ? color : null;
		this.subDepth = on ? depth : null;
		this.subWidth = on ? width : 0;
		this.subHeight = on ? height : 0;
	}

	private boolean inSubRect(List<RenderPassDescriptor.@Nullable Attachment<Optional<Vector4fc>>> colors, RenderPassDescriptor.@Nullable Attachment<OptionalDouble> depth) {
		if (this.subWidth == 0) return false;
		for (var c : colors) {
			if (c != null && c.textureView().texture() == this.subColor && c.textureView().baseMipLevel() == 0) return true;
		}
		return depth != null && depth.textureView().texture() == this.subDepth;
	}

	MetalEncoder(long ctx) {
		this.ctx = ctx;
		this.enc = Native.encNew(ctx);
		if (GPU_TIMES) GpuTimes.initialize();
		this.transientMemory = new MetalTransientMemory(ctx, this);
		this.terrain = new MetalTerrain(this);
	}

	boolean tracing() {
		return this.submitIndex == TRACE_SUBMIT;
	}

	private static final boolean FRAME_DEPTH_DISCARD = Boolean.getBoolean("mcopt.metal.frameDepthDiscard");
	private boolean presentedThisSubmit;

	void trace(String op, Object... args) {
		if (!this.tracing()) return;
		StringBuilder line = new StringBuilder("mcopt-metal trace: ").append(op);
		for (Object a : args) line.append(' ').append(a instanceof GpuTexture t ? t.getLabel() : a instanceof GpuTextureView v ? v.texture().getLabel() : a);
		System.out.println(line);
	}

	MetalBuffer use(GpuBuffer buffer) {
		MetalBuffer b = (MetalBuffer) buffer;
		b.lastUse = this.submitIndex;
		return b;
	}

	private boolean idle(MetalBuffer b) {
		return b.lastUse <= this.completedIndex;
	}

	void releaseLater(long handle) {
		this.afterThisFrame.add(() -> Native.release(handle));
	}

	void afterGpuFinishes(Runnable r) {
		this.afterThisFrame.add(r);
	}

	@Override
	public void submit() {
		if (this.currentPass != null) throw new IllegalStateException("Cannot submit inside a render pass");
		this.trace("submit");
		this.transientMemory.endSubmit();
		long samples = 0;
		int sampleCount = 0;
		if (profiled(this.submitIndex)) {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				long count = stack.ncalloc(4, 1, 4);
				if (ENC_LOG) {
					long names = stack.ncalloc(1, 128 * 64, 1);
					Native.profileLabels(this.enc, names, 64, 128);
					for (int g = 0; g < 128; g++) {
						String native_ = MemoryUtil.memASCII(names + g * 64L);
						if (native_.isEmpty()) continue;
						String java = this.encLabels[g];
						this.encLabels[g] = native_.length() > 2 || java == null ? native_ : native_ + java;
						long[] st = this.encStats[g];
						if (st[0] + st[1] > 0) this.encLabels[g] += " d=" + st[0] + " tb=" + st[1] + " first=" + st[2] + " cold=" + st[3];
					}
				}
				samples = Native.profileEnd(this.enc, count);
				sampleCount = MemoryUtil.memGetInt(count);
			}
		}
		this.terrain.releaseHeld(true);
		if (this.presentedThisSubmit && mcopt.metal.own.OwnProbe.bool("metal.frameDepthDiscard", FRAME_DEPTH_DISCARD)) {
			net.minecraft.client.renderer.GameRenderer game = net.minecraft.client.Minecraft.getInstance().gameRenderer;
			GpuTexture depth = game != null ? game.mainRenderTarget().getDepthTexture() : null;
			if (depth instanceof MetalTexture t) Native.discard(this.enc, t.handle);
		}
		this.presentedThisSubmit = false;
		MetalEvents.Operation commitEvent = MetalEvents.begin("commit", this.submitIndex, 0);
		long cmd;
		try {
			if (GPU_TIMES) GpuTimes.submit(this.submitIndex, this.encoderIndex);
			cmd = Native.encCommit(this.enc);
		} finally {
			MetalEvents.end(commitEvent);
		}
		this.terrain.endFrame();
		if (samples != 0) {
			long s = samples;
			int n = sampleCount;
			if (ENC_LOG) {
				long submit = this.submitIndex;
				String[] labels = this.encLabels;
				this.afterThisFrame.add(() -> FrameLog.encoders(this.ctx, submit, s, n, labels));
			} else {
				this.afterThisFrame.add(() -> this.accumulateProfile(cmd, s, n));
			}
		}
		this.inFlight.add(new Frame(this.submitIndex++, cmd, this.afterThisFrame));
		if (TracePhase.PHASE != null && TRACE_SUBMIT < 0 && TracePhase.in()) TRACE_SUBMIT = this.submitIndex + TracePhase.OFFSET;
		if (TRACE_SUBMIT >= 0) Native.rpLog(this.submitIndex == TRACE_SUBMIT);
		if (mcopt.metal.cpu.Cpu.FENCE_STATS) mcopt.metal.cpu.Cpu.fenceTick(this.submitIndex);
		this.afterThisFrame = new ArrayList<>();
		this.encoderIndex = 0;
		if (PASS_TEST_N > 0 && profiled(this.submitIndex) && this.submitIndex % 20 == 0) this.passTestPending = true;
		if (ENC_LOG) {
			this.encLabels = new String[128];
			this.encStats = new long[128][4];
			this.encGroupOpen = -1;
		}
		if (profiled(this.submitIndex) && !Native.profileBegin(this.enc, 512)) System.out.println("mcopt-metal trace: no GPU timestamps on this device");
		if (this.passTestPending) {
			Native.passTest(this.enc, PASS_TEST_N, PASS_TEST_MODE);
			this.passTestPending = false;
		}
		while (this.inFlight.size() > MAX_IN_FLIGHT) this.retire(this.inFlight.poll(), WaitStats.INFLIGHT);
		if (STATS) this.stats();
		if (WaitStats.ON) WaitStats.frame(this.submitIndex - 1);
	}

	private void stats() {
		long now = System.nanoTime();
		this.statFrames++;
		if (this.statStart == 0) this.statStart = now;
		if (now - this.statStart < 1_000_000_000L) return;
		double frameMs = (now - this.statStart) / 1e6 / this.statFrames;
		System.out.printf("mcopt-metal stats: %d fps, %.3f ms/frame, %.3f ms of it waiting on the GPU, %d presents%s%n", this.statFrames,
			frameMs, this.waitNanos / 1e6 / this.statFrames, this.statPresents, (PRESENT_QUEUE ? " (queued" + (PRESENT_ACQUIRE ? ", acquired on the present side" : "") + "; skipped so far " + Native.presentSkipped() + ", dropped " + Native.presentDropped() + ")" : "")
			+ (MetalSurface.PACE_ADAPT ? String.format(" (pace margin +%.2f ms learned)", Native.paceExtraMs()) : ""));
		if (MetalTerrain.OCC) {
			long[] t = this.terrain.lastCompletedCounts();
			System.out.printf("mcopt-metal stats: terrain drew %d of %d quads (%.1f%%), %d before the split, %d chunks (%.1f quads each), %d frames drew terrain%n", t[0], t[1],
				100.0 * t[0] / Math.max(1, t[1]), t[2], t[3], (double) t[1] / Math.max(1, t[3]), this.terrain.terrainFrames - this.statTerrainFrames);
			this.statTerrainFrames = this.terrain.terrainFrames;
		}
		this.statStart = now;
		this.statFrames = 0;
		this.statPresents = 0;
		this.waitNanos = 0;
	}

	private static boolean profiled(long submit) {
		return ENC_LOG || TRACE_SUBMIT >= 0 && submit >= TRACE_SUBMIT && submit < TRACE_SUBMIT + PROFILE_FRAMES;
	}

	private void accumulateProfile(long cmd, long samples, int count) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long out = stack.nmalloc(8, Math.max(1, count) * 8);
			Native.profileRead(this.ctx, samples, count, out);
			this.profileSums[0] += Native.cmdGpuMicros(cmd);
			for (int e = 0; e < Math.min(count / 4, PROFILE_ENCODERS); e++) {
				double vs = MemoryUtil.memGetDouble(out + e * 32L), ve = MemoryUtil.memGetDouble(out + e * 32L + 8);
				double fs = MemoryUtil.memGetDouble(out + e * 32L + 16), fe = MemoryUtil.memGetDouble(out + e * 32L + 24);
				if (vs >= 0) this.profileSums[1 + 2 * e] += ve - vs;
				if (fs >= 0) this.profileSums[2 + 2 * e] += fe - fs;
				double[] at = {vs, ve, fs, fe};
				for (int k = 0; k < 4; k++) if (at[k] >= 0) { this.profileAt[4 * e + k] += at[k]; this.profileAtN[4 * e + k]++; }
			}
		}
		if (++this.profiledFrames < PROFILE_FRAMES) return;
		double[] avg = Arrays.stream(this.profileSums).map(v -> v / PROFILE_FRAMES).toArray();
		System.out.println(String.format("mcopt-metal gpu: submits %d-%d took %.0f us on the GPU on average", TRACE_SUBMIT, TRACE_SUBMIT + PROFILE_FRAMES - 1, avg[0]));
		for (int e = 0; e < PROFILE_ENCODERS && avg[1 + 2 * e] + avg[2 + 2 * e] > 0; e++) {
			double[] at = new double[4];
			for (int k = 0; k < 4; k++) at[k] = this.profileAtN[4 * e + k] > 0 ? this.profileAt[4 * e + k] / this.profileAtN[4 * e + k] : -1;
			System.out.println(String.format("mcopt-metal gpu: #%d vertex %6.0f us, fragment %6.0f us; at vertex %.0f-%.0f, fragment %.0f-%.0f us", e, avg[1 + 2 * e], avg[2 + 2 * e],
				at[0], at[1], at[2], at[3]));
		}
	}

	private void retire(Frame frame) {
		this.retire(frame, WaitStats.DRAIN);
	}

	private void retire(Frame frame, int site) {
		long waitStart = System.nanoTime();
		MetalEvents.Operation event = MetalEvents.begin("wait", frame.index, 0);
		try {
			Native.cmdWait(frame.cmd);
			if (WaitStats.ON) WaitStats.wait(site, System.nanoTime() - waitStart, this.submitIndex - frame.index);
			if (GPU_TIMES) GpuTimes.retire(frame.index, frame.cmd);
			if (mcopt.metal.shade.ShadeBindings.ON && mcopt.metal.shade.taa.ShadeTaa.ON) mcopt.metal.shade.taa.ShadeTaa.gpuFrame(Native.cmdGpuStart(frame.cmd), Native.cmdGpuEnd(frame.cmd));
			if (event != null) event.gpuMicros = Native.cmdGpuMicros(frame.cmd);
		} finally {
			MetalEvents.end(event);
		}
		this.waitNanos += System.nanoTime() - waitStart;
		frame.after.forEach(Runnable::run);
		Native.release(frame.cmd);
		this.completedIndex = frame.index;
	}

	void waitIdle() {
		while (!this.inFlight.isEmpty()) this.retire(this.inFlight.poll());
	}

	@Override
	public TransientMemory transientMemory() {
		return this.transientMemory;
	}

	@Override
	public RenderPassBackend createRenderPass(RenderPassDescriptor descriptor) {
		if (FrameLog.ON) FrameLog.op(FrameLog.PASSES, 1);
		MetalHooks.Redirect redirect = MetalHooks.hasRedirectors() ? MetalHooks.redirect(descriptor) : null;
		PassDelegate keptDelegate = redirect != null && redirect.keepsAttachments() ? redirect.delegate() : null;
		if (keptDelegate != null) redirect = null;
		List<RenderPassDescriptor.@Nullable Attachment<Optional<Vector4fc>>> colors = redirect != null ? List.of() : descriptor.colorAttachments();
		RenderPassDescriptor.@Nullable Attachment<OptionalDouble> depth = redirect != null && redirect.depth() != 0 ? null : descriptor.depthAttachment();
		boolean lazy = LAZY_CLEARS && redirect == null && keptDelegate == null;
		for (MetalTexture t : List.copyOf(this.pendingClears)) {
			if (t.isClosed()) this.pendingClears.remove(t);
			else if (!lazy && !this.isAttachment(t, colors, depth)) this.flushClear(t);
		}
		if (FrameLog.ON && FrameLog.inTick()) {
			var c0 = descriptor.colorAttachments().isEmpty() ? null : descriptor.colorAttachments().get(0);
			MetalTexture.View v0 = c0 == null ? null : (MetalTexture.View) c0.textureView();
			String target = v0 == null ? "-" : v0.texture().getLabel() + " mip " + v0.baseMipLevel() + " " + v0.getWidth(0) + "x" + v0.getHeight(0);
			FrameLog.tickPass(descriptor.label().get() + " -> " + target + " area " + descriptor.renderArea(), v0 == null ? 0 : (long) v0.getWidth(0) * v0.getHeight(0));
		}
		int width = 0, height = 0;
		long subPixels = 0;
		boolean sub = false;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long colorHandles = stack.nmalloc(8, Math.max(1, colors.size()) * 8);
			long clears = stack.ncalloc(4, Math.max(1, colors.size()) * 5, 4);
			for (int i = 0; i < colors.size(); i++) {
				var attachment = colors.get(i);
				long handle = 0;
				if (attachment != null) {
					MetalTexture.View view = (MetalTexture.View) attachment.textureView();
					handle = view.handle;
					width = view.getWidth(0);
					height = view.getHeight(0);
					Vector4fc clear = attachment.clearValue().orElse(view.baseMipLevel() == 0 ? view.metalTexture().pendingColorClear : null);
					view.metalTexture().pendingColorClear = null;
					if (clear != null) putClear(clears + i * 20L, clear);
				}
				MemoryUtil.memPutAddress(colorHandles + i * 8L, handle);
			}
			long depthHandle = 0;
			boolean clearDepth = false;
			double depthValue = 0;
			if (depth != null) {
				MetalTexture.View view = (MetalTexture.View) depth.textureView();
				MetalTexture texture = view.metalTexture();
				depthHandle = view.handle;
				if (colors.isEmpty()) {
					width = view.getWidth(0);
					height = view.getHeight(0);
				}
				OptionalDouble explicit = depth.clearValue();
				clearDepth = explicit.isPresent() || !Double.isNaN(texture.pendingDepthClear);
				depthValue = clearDepth ? explicit.orElse(texture.pendingDepthClear) : 0;
				texture.pendingDepthClear = Double.NaN;
			}
			this.forgetSettledClears();
			sub = mcopt.metal.shade.ShadeBindings.ON && this.subWidth != 0 && this.inSubRect(descriptor.colorAttachments(), descriptor.depthAttachment());
			if (sub && redirect == null) {
				subPixels = (long) width * height;
				width = Math.min(width, this.subWidth);
				height = Math.min(height, this.subHeight);
			}
			int colorCount = colors.size();
			if (redirect != null) {
				colorHandles = stack.nmalloc(8, Math.max(1, redirect.colors().length) * 8);
				clears = stack.ncalloc(4, Math.max(1, redirect.colors().length) * 5, 4);
				for (int i = 0; i < redirect.colors().length; i++) {
					MemoryUtil.memPutAddress(colorHandles + i * 8L, redirect.colors()[i]);
					float[] c = redirect.clears() == null ? null : redirect.clears()[i];
					if (c != null && c.length == 0) MemoryUtil.memPutFloat(clears + i * 20L, 2);
					else if (c != null) putClear(clears + i * 20L, new org.joml.Vector4f(c[0], c[1], c[2], c[3]));
				}
				colorCount = redirect.colors().length;
				width = redirect.width();
				height = redirect.height();
				if (sub) {
					subPixels = (long) width * height;
					width = Math.min(width, this.subWidth);
					height = Math.min(height, this.subHeight);
				}
				if (redirect.depth() != 0) {
					depthHandle = redirect.depth();
					clearDepth = !Float.isNaN(redirect.depthClear());
					depthValue = clearDepth ? redirect.depthClear() : 0;
				}
			}
			if (mcopt.metal.cpu.Cpu.PASS_AB) {
				Native.cpuFlags(mcopt.metal.cpu.Cpu.nativeFlags((mcopt.metal.cpu.Ab.frame & 1) == 1));
				mcopt.metal.cpu.Cpu.PASS_TIMER.begin();
			}
			int continued = Native.renderBegin(this.enc, colorCount, colorHandles, clears, depthHandle, clearDepth ? 1 : 0, (float) depthValue, width, height);
			if (mcopt.metal.cpu.Cpu.PASS_AB) mcopt.metal.cpu.Cpu.PASS_TIMER.end();
			if (mcopt.metal.shade.ShadeBindings.ON && continued == 0) mcopt.metal.shade.ShadeBindings.bind(this.ctx, this.enc);
			this.passLazy = lazy && colorCount <= this.passColors.length;
			if (this.passLazy) {
				for (int i = 0; i < colorCount; i++) this.passColors[i] = MemoryUtil.memGetAddress(colorHandles + i * 8L);
				this.passColorCount = colorCount;
				this.passDepth = depthHandle;
				this.passWidth = width;
				this.passHeight = height;
			}
			if (redirect != null && redirect.delegate() != null) redirect.delegate().begin(this.enc, depthHandle != 0);
			if (keptDelegate != null) keptDelegate.begin(this.enc, depthHandle != 0);
			MetalHooks.Labeler labeler = MetalHooks.labeler;
			if (labeler != null && continued == 0) labeler.label(this.enc, "game " + descriptor.label().get());
			if (LABELS) {
				String l = descriptor.label().get();
				this.passLabel = continued == 0 ? "game " + l : this.passLabel.length() < 200 ? this.passLabel + " + " + l : this.passLabel;
				try (MemoryStack ls = MemoryStack.stackPush()) {
					Native.renderLabel(this.enc, MemoryUtil.memAddress(ls.UTF8(this.passLabel)));
				}
			}
			if (ENC_LOG && continued == 0) {
				int c = Native.profileCount(this.enc);
				if (c >= 4 && c / 4 - 1 < this.encLabels.length) this.encLabels[c / 4 - 1] = descriptor.label().get();
				this.encGroupOpen = c >= 4 ? c / 4 - 1 : -1;
			}
			if (this.submitIndex == TRACE_SUBMIT) {
				StringBuilder targets = new StringBuilder();
				for (int i = 0; i < colors.size(); i++) {
					var c = colors.get(i);
					targets.append(c == null ? "-" : c.textureView().texture().getLabel() + (MemoryUtil.memGetFloat(clears + i * 20L) != 0 ? "(clear)" : "")).append(' ');
				}
				if (depth != null) targets.append("depth=").append(depth.textureView().texture().getLabel()).append(clearDepth ? "(clear)" : "");
				String how = switch (continued) { case 1 -> "pass (merged)"; case 2 -> "pass (merged, depth cleared in place)"; default -> "pass #" + this.encoderIndex; };
				this.trace(how, "'" + descriptor.label().get() + "'", targets, width + "x" + height);
			}
			if (continued == 0) this.encoderIndex++;
		}
		var area = descriptor.renderArea();
		int[] formats = redirect != null && redirect.delegate() == null ? redirect.colorFormats() : null;
		this.passFormats = formats;
		if (redirect != null && redirect.depth() != 0) {
			this.currentPass = new MetalRenderPass(this, true, 0, 0, sub ? width : redirect.width(), sub ? height : redirect.height(), redirect.delegate(), formats);
			return this.currentPass;
		}
		if (subPixels != 0) {
			this.currentPass = new MetalRenderPass(this, depth != null, area.x(), area.y(), Math.min(area.width(), width), Math.min(area.height(), height),
				redirect != null ? redirect.delegate() : keptDelegate, formats);
			return this.currentPass;
		}
		this.currentPass = new MetalRenderPass(this, depth != null, area.x(), area.y(), area.width(), area.height(),
			redirect != null ? redirect.delegate() : keptDelegate, formats);
		return this.currentPass;
	}

	private boolean isAttachment(MetalTexture t, List<RenderPassDescriptor.@Nullable Attachment<Optional<Vector4fc>>> colors,
		RenderPassDescriptor.@Nullable Attachment<OptionalDouble> depth) {
		for (var c : colors) {
			if (c != null && c.textureView().texture() == t && c.textureView().baseMipLevel() == 0) return true;
		}
		return depth != null && depth.textureView().texture() == t;
	}

	private void forgetSettledClears() {
		this.pendingClears.removeIf(t -> !t.hasPendingClear());
	}

	private static void putClear(long at, Vector4fc c) {
		MemoryUtil.memPutFloat(at, 1);
		MemoryUtil.memPutFloat(at + 4, c.x());
		MemoryUtil.memPutFloat(at + 8, c.y());
		MemoryUtil.memPutFloat(at + 12, c.z());
		MemoryUtil.memPutFloat(at + 16, c.w());
	}

	@Override
	public void submitRenderPass() {
		if (this.currentPass == null) throw new IllegalStateException("No render pass to submit");
		this.currentPass.end();
		if (ENC_LOG && this.encGroupOpen >= 0 && this.encGroupOpen < 128) this.encStats[this.encGroupOpen][0] += this.currentPass.draws;
		this.trace("  end, draws:", this.currentPass.draws, "triangles:", this.currentPass.indices / 3);
		this.currentPass = null;
	}

	boolean inRenderPass() {
		return this.currentPass != null;
	}

	boolean reapplyPipeline() {
		if (this.currentPass == null) return false;
		this.currentPass.reapplyPipeline();
		return true;
	}

	boolean restorePass() {
		if (this.currentPass == null) return false;
		this.currentPass.restoreAfterSplit();
		return true;
	}

	void captureWater() {
		if (this.currentPass != null) this.currentPass.captureWater();
	}

	void dropPendingClear(MetalTexture t) {
		t.pendingColorClear = null;
		t.pendingDepthClear = Double.NaN;
		this.pendingClears.remove(t);
	}

	float @Nullable [] takePendingColorClear(MetalTexture t) {
		Vector4fc c = t.pendingColorClear;
		if (c == null) return null;
		t.pendingColorClear = null;
		if (!t.hasPendingClear()) this.pendingClears.remove(t);
		return new float[] {c.x(), c.y(), c.z(), c.w()};
	}

	void flushClearInPass(MetalTexture t) {
		if (!t.hasPendingClear()) return;
		if (this.currentPass == null || !this.passLazy) {
			this.flushClear(t);
			return;
		}
		this.flushClear(t);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long colors = stack.nmalloc(8, Math.max(1, this.passColorCount) * 8);
			long clears = stack.ncalloc(4, Math.max(1, this.passColorCount) * 5, 4);
			for (int i = 0; i < this.passColorCount; i++) MemoryUtil.memPutAddress(colors + i * 8L, this.passColors[i]);
			Native.renderBegin(this.enc, this.passColorCount, colors, clears, this.passDepth, 0, 0, this.passWidth, this.passHeight);
		}
		this.currentPass.restoreAfterSplit();
		this.lazySplits++;
		long now = System.nanoTime();
		if (now - this.lazySplitLog > 10_000_000_000L) {
			System.out.println("mcopt-metal: lazy clears split a pass " + this.lazySplits + " times so far (" + t.getLabel() + ")");
			this.lazySplitLog = now;
		}
	}

	void flushClear(MetalTexture t) {
		if (!t.hasPendingClear()) return;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			boolean depthFormat = t.getFormat().hasDepthAspect();
			long colors = stack.nmalloc(8, 8);
			long clears = stack.ncalloc(4, 5, 4);
			MemoryUtil.memPutAddress(colors, depthFormat ? 0 : t.handle);
			if (t.pendingColorClear != null) putClear(clears, t.pendingColorClear);
			boolean clearDepth = !Double.isNaN(t.pendingDepthClear);
			int continued = Native.renderBegin(this.enc, depthFormat ? 0 : 1, colors, clears, depthFormat ? t.handle : 0, clearDepth ? 1 : 0,
				clearDepth ? (float) t.pendingDepthClear : 0, t.getWidth(0), t.getHeight(0));
			this.trace(continued == 0 ? "flushClear #" + this.encoderIndex : "flushClear (in place)", t);
			if (continued == 0) this.encoderIndex++;
		}
		t.pendingColorClear = null;
		t.pendingDepthClear = Double.NaN;
		this.pendingClears.remove(t);
	}

	void flushClear(GpuTexture t) {
		this.flushClear((MetalTexture) t);
	}

	private void deferClear(GpuTexture texture, @Nullable Vector4fc color, double depth) {
		MetalTexture t = (MetalTexture) texture;
		if (t.getMipLevels() != 1) {
			for (int mip = 0; mip < t.getMipLevels(); mip++) this.clearRegion(t, color, null, depth, 0, 0, t.getWidth(mip), t.getHeight(mip), mip);
			return;
		}
		if (color != null) t.pendingColorClear = color;
		if (!Double.isNaN(depth)) t.pendingDepthClear = depth;
		this.pendingClears.add(t);
		Native.discard(this.enc, t.handle);
		this.trace("deferClear", t);
	}

	@Override
	public void clearColorTexture(GpuTexture colorTexture, Vector4fc clearColor) {
		this.deferClear(colorTexture, clearColor, Double.NaN);
	}

	@Override
	public void clearColorAndDepthTextures(GpuTexture colorTexture, Vector4fc clearColor, GpuTexture depthTexture, double clearDepth) {
		this.deferClear(colorTexture, clearColor, Double.NaN);
		this.deferClear(depthTexture, null, clearDepth);
	}

	@Override
	public void clearColorAndDepthTextures(GpuTexture colorTexture, Vector4fc clearColor, GpuTexture depthTexture, double clearDepth,
		int x, int y, int width, int height, int mip) {
		if (mip == 0 && x == 0 && y == 0 && width == colorTexture.getWidth(0) && height == colorTexture.getHeight(0)) {
			this.clearColorAndDepthTextures(colorTexture, clearColor, depthTexture, clearDepth);
		} else {
			this.clearRegion((MetalTexture) colorTexture, clearColor, (MetalTexture) depthTexture, clearDepth, x, y, width, height, mip);
		}
	}

	@Override
	public void clearDepthTexture(GpuTexture depthTexture, double clearDepth) {
		this.deferClear(depthTexture, null, clearDepth);
	}

	private void clearRegion(@Nullable MetalTexture color, @Nullable Vector4fc clearColor, @Nullable MetalTexture depth, double clearDepth,
		int x, int y, int width, int height, int mip) {
		this.trace("clearRect", color, depth, x, y, width, height, mip);
		if (color != null) this.flushClear(color);
		if (depth != null) this.flushClear(depth);
		Vector4fc c = clearColor != null ? clearColor : new org.joml.Vector4f();
		Native.clearRect(this.enc, color != null ? color.handle : 0, depth != null ? depth.handle : 0, c.x(), c.y(), c.z(), c.w(),
			Double.isNaN(clearDepth) ? -1 : (float) clearDepth, x, y, width, height, mip);
	}

	@Override
	public void writeToBuffer(GpuBufferSlice destination, ByteBuffer data) {
		if (FrameLog.ON) {
			FrameLog.op(FrameLog.BUF_WRITES, 1);
			FrameLog.op(FrameLog.BUF_KB, data.remaining() >> 10);
		}
		MetalEvents.Operation event = MetalEvents.begin("upload", this.submitIndex, data.remaining());
		try {
			MetalBuffer dst = (MetalBuffer) destination.buffer();
			if (event != null) event.arena = dst.arena;
			boolean whole = destination.offset() == 0 && data.remaining() == dst.size();
			if (data.remaining() <= CPU_COPY_MAX && (this.idle(dst) || whole && dst.rename())) {
				this.trace("writeToBuffer (cpu)", data.remaining());
				MemoryUtil.memCopy(MemoryUtil.memAddress(data), dst.address + destination.offset(), data.remaining());
				if (dst.arena) this.terrain.invalidate(dst, destination.offset(), data.remaining());
				return;
			}
			this.trace("writeToBuffer", data.remaining());
			GpuBufferSlice staging = this.transientMemory.uploadGpu(data, 1L, GpuBuffer.USAGE_COPY_SRC);
			this.blitBuffer(this.use(staging.buffer()), staging.offset(), this.use(dst), destination.offset(), data.remaining());
		} finally {
			MetalEvents.end(event);
		}
	}

	private void blitBuffer(MetalBuffer src, long srcOffset, MetalBuffer dst, long dstOffset, long size) {
		if (dst.arena && this.terrain.split && !this.terrain.culledThisFrame()) {
			this.terrain.hold(src, srcOffset, dst, dstOffset, size);
		} else {
			if (src.arena || dst.arena) this.terrain.releaseHeld(false);
			Native.blitBuffer(this.enc, src.handle, srcOffset, dst.handle, dstOffset, size);
		}
		if (dst.arena) this.terrain.invalidate(dst, dstOffset, size);
	}

	@Override
	public void copyToBuffer(GpuBufferSlice source, GpuBufferSlice target) {
		if (FrameLog.ON) {
			FrameLog.op(FrameLog.BUF_COPIES, 1);
			FrameLog.op(FrameLog.COPY_KB, source.length() >> 10);
		}
		MetalEvents.Operation event = MetalEvents.begin("copy", this.submitIndex, source.length());
		try {
			MetalBuffer src = (MetalBuffer) source.buffer(), dst = (MetalBuffer) target.buffer();
			if (event != null) event.arena = src.arena || dst.arena;
			if (src.arena || dst.arena) MetalEvents.arenaTransfer("copy", source.length(), src.size(), dst.size());
			if (source.length() <= CPU_COPY_MAX && src != dst && this.idle(src) && this.idle(dst)) {
				this.trace("copyToBuffer (cpu)", source.length());
				MemoryUtil.memCopy(src.address + source.offset(), dst.address + target.offset(), source.length());
				if (dst.arena) this.terrain.invalidate(dst, target.offset(), source.length());
				return;
			}
			this.trace("copyToBuffer", source.length());
			this.blitBuffer(this.use(src), source.offset(), this.use(dst), target.offset(), source.length());
		} finally {
			MetalEvents.end(event);
		}
	}

	@Override
	public void writeToTexture(GpuTexture destination, ByteBuffer source, int mip, int layer, int x, int y, int width, int height) {
		if (FrameLog.ON) {
			FrameLog.op(FrameLog.TEX_WRITES, 1);
			FrameLog.op(FrameLog.TEX_PX, (long) width * height);
		}
		this.trace("writeToTexture", destination, width + "x" + height);
		this.flushClear(destination);
		GpuBufferSlice staging = this.transientMemory.uploadGpu(source, 16L, GpuBuffer.USAGE_COPY_SRC);
		int bytesPerRow = width * destination.getFormat().blockSize();
		Native.blitBufferToTexture(this.enc, this.use(staging.buffer()).handle, staging.offset(), bytesPerRow, bytesPerRow * height,
			((MetalTexture) destination).handle, layer, mip, x, y, width, height);
	}

	@Override
	public void copyBufferToTexture(GpuBufferSlice source, int sourceX, int sourceY, int sourceWidth, int sourceHeight, GpuTexture destination,
		int destX, int destY, int copyWidth, int copyHeight, int mip, int layer) {
		this.trace("copyBufferToTexture", destination, copyWidth + "x" + copyHeight);
		this.flushClear(destination);
		int texel = destination.getFormat().blockSize();
		long skip = (sourceX + (long) sourceY * sourceWidth) * texel;
		Native.blitBufferToTexture(this.enc, this.use(source.buffer()).handle, source.offset() + skip, sourceWidth * texel,
			sourceWidth * texel * sourceHeight, ((MetalTexture) destination).handle, layer, mip, destX, destY, copyWidth, copyHeight);
	}

	@Override
	public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mip) {
		this.copyTextureToBuffer(source, destination, offset, callback, mip, 0, 0, source.getWidth(mip), source.getHeight(mip));
	}

	@Override
	public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mip, int x, int y, int width, int height) {
		this.trace("copyTextureToBuffer", source, width + "x" + height);
		this.flushClear(source);
		Native.blitTextureToBuffer(this.enc, ((MetalTexture) source).handle, mip, x, y, width, height, this.use(destination).handle, offset,
			width * source.getFormat().blockSize());
		this.afterGpuFinishes(callback);
	}

	@Override
	public void copyTextureToTexture(GpuTexture source, GpuTexture destination, int mip, int destX, int destY, int sourceX, int sourceY, int width, int height) {
		if (FrameLog.ON) FrameLog.op(FrameLog.TEX_COPIES, 1);
		this.trace("copyTextureToTexture", source, destination, width + "x" + height);
		this.flushClear(source);
		this.flushClear(destination);
		Native.blitTextureToTexture(this.enc, ((MetalTexture) source).handle, ((MetalTexture) destination).handle, mip, destX, destY, sourceX, sourceY,
			width, height);
	}

	@Override
	public GpuFence createFence() {
		long current = this.submitIndex;
		long index = mcopt.metal.cpu.Cpu.FENCE && mcopt.metal.cpu.Cpu.fenceActive && current > 0 && Native.encEmpty(this.enc) ? current - 1 : current;
		if (mcopt.metal.cpu.Cpu.FENCE_STATS) mcopt.metal.cpu.Cpu.fenceMade(index != current);
		return new GpuFence() {
			@Override
			public boolean awaitCompletion(long timeoutNs) {
				if (MetalEncoder.this.completedIndex >= index) return true;
				if (index == MetalEncoder.this.submitIndex) {
					if (timeoutNs == 0) return false;
					throw new IllegalStateException("Cannot wait on a fence for the current submit");
				}
				boolean cpuStats = mcopt.metal.cpu.Cpu.FENCE_STATS && timeoutNs != 0;
				long t0 = WaitStats.ON || cpuStats ? System.nanoTime() : 0;
				while (MetalEncoder.this.completedIndex < index) {
					Frame oldest = MetalEncoder.this.inFlight.peek();
					if (timeoutNs == 0 && !Native.cmdDone(oldest.cmd)) return false;
					MetalEncoder.this.retire(MetalEncoder.this.inFlight.poll(), WaitStats.FENCE);
				}
				if (WaitStats.ON) WaitStats.fenceCaller(System.nanoTime() - t0);
				if (cpuStats) mcopt.metal.cpu.Cpu.fenceWaited(System.nanoTime() - t0);
				return true;
			}

			@Override
			public void close() {
			}
		};
	}

	@Override
	public void writeTimestamp(GpuQueryPool pool, int index) {
	}

	void presentAcquire(long layer, GpuTextureView view) {
		MetalEvents.Operation event = MetalEvents.begin("present", this.submitIndex, 0);
		try {
			this.trace("present", view);
			this.presentedThisSubmit = true;
			this.flushClear(view.texture());
			if (Native.presentQueuedAcquire(this.enc, layer, ((MetalTexture.View) view).handle, GPU_TIMES && this.submitIndex < Integer.MAX_VALUE ? this.submitIndex : -1) == 0) return;
			if (GPU_TIMES) GpuTimes.presentQueued(this.submitIndex);
			this.statPresents++;
		} finally {
			MetalEvents.end(event);
		}
	}

	void presentTexture(long drawable, GpuTextureView view) {
		MetalEvents.Operation event = MetalEvents.begin("present", this.submitIndex, 0);
		try {
			this.trace("present", view);
			this.presentedThisSubmit = true;
			this.flushClear(view.texture());
			if (PRESENT_QUEUE) {
				if (Native.presentQueued(this.enc, drawable, ((MetalTexture.View) view).handle) == 0) return;
				if (GPU_TIMES) GpuTimes.present(this.submitIndex, drawable);
			} else {
				if (GPU_TIMES) GpuTimes.present(this.submitIndex, drawable);
				Native.present(this.enc, drawable, ((MetalTexture.View) view).handle);
			}
			this.statPresents++;
		} finally {
			MetalEvents.end(event);
		}
	}
}
