package mcopt.metal;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

final class MetalRenderPass implements RenderPassBackend {
	private static final int MAX_UNIFORMS = MetalConst.PUSH_CONSTANTS_INDEX;

	private final MetalEncoder encoder;
	private final long enc;
	private final int areaX, areaY, areaWidth, areaHeight;
	private @Nullable MetalPipeline pipeline;
	private final Object[] pending = new Object[MAX_UNIFORMS];
	private final Object[] bound = new Object[MAX_UNIFORMS];
	private int dirtyUpTo;
	int draws;
	long indices;
	private final Map<GpuBufferSlice, Long> texelViews = new HashMap<>();
	private @Nullable GpuBufferSlice vertexBuffer0;
	private @Nullable MetalPipeline recording;
	private long indexHandle;
	private int indexInt;
	private final int[] scissor = new int[4];
	private final @Nullable GpuBufferSlice[] vertexBuffers = new GpuBufferSlice[4];
	private final byte[] pushConstants = new byte[128];
	private int pushConstantsLength;
	private boolean waterCaptured;

	private final boolean hasDepth;
	private final @Nullable PassDelegate delegate;
	private int @Nullable [] slots;

	private final int @Nullable [] formats;

	MetalRenderPass(MetalEncoder encoder, boolean hasDepth, int x, int y, int width, int height, @Nullable PassDelegate delegate, int @Nullable [] formats) {
		this.encoder = encoder;
		this.formats = formats;
		this.enc = encoder.enc;
		this.hasDepth = hasDepth;
		this.delegate = delegate;
		this.areaX = x;
		this.areaY = y;
		this.areaWidth = width;
		this.areaHeight = height;
		this.enableScissor(x, y, width, height);
	}

	@Override
	public void pushDebugGroup(Supplier<String> label) {
	}

	@Override
	public void popDebugGroup() {
	}

	@Override
	public void setPipeline(BackendRenderPipeline pipeline) {
		MetalPipeline p = (MetalPipeline) pipeline;
		if (this.delegate != null) {
			this.pipeline = p;
			this.slots = this.delegate.setPipeline(this.enc, p, p.uniforms);
			Arrays.fill(this.pending, 0, p.uniforms.size(), null);
			Arrays.fill(this.bound, null);
			this.dirtyUpTo = p.uniforms.size();
			return;
		}
		boolean terrain = p.pulled != 0 && this.hasDepth, record = terrain && this.encoder.terrain.split;
		if (terrain) this.encoder.terrain.terrainPixels = (long) this.areaWidth * this.areaHeight;
		this.flushTerrain(record);
		if (this.encoder.tracing()) this.encoder.trace("  pipeline", p.name, "after draws:", this.draws, "triangles:", this.indices / 3);
		if (mcopt.metal.shade.water.ShadeWater.ON && this.hasDepth && !this.waterCaptured && mcopt.metal.shade.water.ShadeWater.translucentTerrain(p.name)) {
			this.waterCaptured = true;
			if (mcopt.metal.shade.water.ShadeWater.capture(this.encoder.ctx, this.enc)) this.restoreAfterSplit();
		}
		this.pipeline = p;
		this.recording = record ? p : null;
		Native.pipeline(this.enc, p.state(this.formats, this.hasDepth), p.depthState, p.cull ? 1 : 0, p.wireframe ? 1 : 0, p.depthBiasConstant, p.depthBiasSlope, p.primitive);
		Arrays.fill(this.pending, 0, p.uniforms.size(), null);
		this.dirtyUpTo = p.uniforms.size();
	}

	void reapplyPipeline() {
		if (mcopt.metal.shade.ShadeBindings.ON) mcopt.metal.shade.ShadeBindings.bind(this.encoder.ctx, this.enc);
		MetalPipeline p = this.pipeline;
		if (p == null) return;
		if (this.delegate != null) {
			this.slots = this.delegate.setPipeline(this.enc, p, p.uniforms);
			return;
		}
		Native.pipeline(this.enc, p.state(this.formats, this.hasDepth), p.depthState, p.cull ? 1 : 0, p.wireframe ? 1 : 0, p.depthBiasConstant, p.depthBiasSlope, p.primitive);
	}

	void end() {
		this.flushTerrain(false);
		if (this.delegate != null) this.delegate.end(this.enc);
	}

	private boolean delegated() {
		return this.delegate == null || this.slots != null && this.delegate.beforeDraw(this.enc);
	}

	private void flushTerrain(boolean more) {
		MetalPipeline p = this.recording;
		if (p == null) return;
		this.encoder.terrain.flush(this.enc, p, (GpuBufferSlice) Objects.requireNonNull(this.bound[uniform(p, "u_Globals")]), this.areaWidth, this.areaHeight,
			this.restorer(p));
		if (!more) this.encoder.terrain.split(this.enc, this::rebind);
		if (this.indexHandle != 0) Native.index(this.enc, this.indexHandle, this.indexInt);
		Arrays.fill(this.bound, MetalTerrain.QUADS_SLOT, MetalTerrain.ARENAS_SLOT + 1, null);
		this.recording = null;
	}

	void restoreAfterSplit() {
		Arrays.fill(this.bound, null);
		for (int i = this.pending.length - 1; i >= 0; i--) {
			if (this.pending[i] != null) {
				this.dirtyUpTo = Math.max(this.dirtyUpTo, i + 1);
				break;
			}
		}
		Native.scissor(this.enc, this.scissor[0], this.scissor[1], this.scissor[2], this.scissor[3]);
		for (int slot = 0; slot < this.vertexBuffers.length; slot++) {
			GpuBufferSlice vb = this.vertexBuffers[slot];
			if (vb != null) Native.vertexBuffer(this.enc, MetalConst.VERTEX_BUFFER_BASE + slot, this.encoder.use(vb.buffer()).handle, vb.offset());
		}
		if (this.indexHandle != 0) Native.index(this.enc, this.indexHandle, this.indexInt);
		if (this.pushConstantsLength > 0) {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				ByteBuffer bytes = stack.malloc(this.pushConstantsLength).put(this.pushConstants, 0, this.pushConstantsLength).flip();
				Native.bytes(this.enc, MetalConst.PUSH_CONSTANTS_INDEX, MemoryUtil.memAddress(bytes), this.pushConstantsLength);
			}
		}
		this.reapplyPipeline();
	}

	void captureWater() {
		if (!mcopt.metal.shade.water.ShadeWater.ON || !this.hasDepth || this.waterCaptured || this.delegate != null) return;
		this.waterCaptured = true;
		if (mcopt.metal.shade.water.ShadeWater.capture(this.encoder.ctx, this.enc)) this.restoreAfterSplit();
	}

	private void rebind() {
		Arrays.fill(this.bound, null);
		this.dirtyUpTo = Objects.requireNonNull(this.pipeline).uniforms.size();
		this.bindUniforms(0);
		Native.scissor(this.enc, this.scissor[0], this.scissor[1], this.scissor[2], this.scissor[3]);
		for (int slot = 0; slot < this.vertexBuffers.length; slot++) {
			GpuBufferSlice vb = this.vertexBuffers[slot];
			if (vb != null) Native.vertexBuffer(this.enc, MetalConst.VERTEX_BUFFER_BASE + slot, this.encoder.use(vb.buffer()).handle, vb.offset());
		}
		if (this.pushConstantsLength == 0) return;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			ByteBuffer bytes = stack.malloc(this.pushConstantsLength).put(this.pushConstants, 0, this.pushConstantsLength).flip();
			Native.bytes(this.enc, MetalConst.PUSH_CONSTANTS_INDEX, MemoryUtil.memAddress(bytes), this.pushConstantsLength);
		}
	}

	private Runnable restorer(MetalPipeline p) {
		Object[] values = Arrays.copyOf(this.bound, p.uniforms.size());
		int[] scissor = this.scissor.clone();
		return () -> {
			for (int i = 0; i < values.length; i++) this.bindUniform(p.uniforms.get(i), i, values[i]);
			Native.scissor(this.enc, scissor[0], scissor[1], scissor[2], scissor[3]);
		};
	}

	private static int uniform(MetalPipeline p, String name) {
		for (int i = 0; i < p.uniforms.size(); i++) {
			if (p.uniforms.get(i).name().equals(name)) return i;
		}
		throw new IllegalStateException(p.name + " has no uniform " + name);
	}

	@Override
	public void setUniform(int index, @Nullable Object value) {
		this.pending[index] = value;
		this.dirtyUpTo = Math.max(this.dirtyUpTo, index + 1);
	}

	@Override
	public void pushConstants(ByteBuffer value) {
		if (this.recording != null) {
			this.encoder.terrain.pushConstants(MemoryUtil.memAddress(value), value.remaining());
			return;
		}
		Native.bytes(this.enc, MetalConst.PUSH_CONSTANTS_INDEX, MemoryUtil.memAddress(value), value.remaining());
		if (MetalTerrain.OCC && value.remaining() <= this.pushConstants.length) {
			this.pushConstantsLength = value.remaining();
			value.get(value.position(), this.pushConstants, 0, this.pushConstantsLength);
		}
	}

	@Override
	public void enableScissor(int x, int y, int width, int height) {
		Native.scissor(this.enc, x, y, width, height);
		this.scissor[0] = x;
		this.scissor[1] = y;
		this.scissor[2] = width;
		this.scissor[3] = height;
	}

	@Override
	public void disableScissor() {
		this.enableScissor(this.areaX, this.areaY, this.areaWidth, this.areaHeight);
	}

	@Override
	public void setVertexBuffer(int slot, @Nullable GpuBufferSlice vertexBuffer) {
		if (vertexBuffer == null) return;
		if (MetalConst.VERTEX_BUFFER_BASE + slot > 30) throw new IllegalArgumentException("Metal backend supports vertex buffer slots 0-3, got " + slot);
		if (slot == 0) this.vertexBuffer0 = vertexBuffer;
		if (this.recording != null) return;
		Native.vertexBuffer(this.enc, MetalConst.VERTEX_BUFFER_BASE + slot, this.encoder.use(vertexBuffer.buffer()).handle, vertexBuffer.offset());
		this.vertexBuffers[slot] = vertexBuffer;
	}

	@Override
	public void setIndexBuffer(GpuBuffer indexBuffer, IndexType indexType) {
		this.indexHandle = this.encoder.use(indexBuffer).handle;
		this.indexInt = indexType == IndexType.INT ? 1 : 0;
		Native.index(this.enc, this.indexHandle, this.indexInt);
	}

	private void bindUniforms(int drawCount) {
		this.draws += drawCount;
		if (this.dirtyUpTo == 0) return;
		if (MetalEncoder.LAZY_CLEARS) {
			for (int i = 0; i < this.dirtyUpTo; i++) {
				if (this.pending[i] instanceof TextureViewAndSampler ts && ts.view() instanceof MetalTexture.View v && v.metalTexture().hasPendingClear()) {
					this.encoder.flushClearInPass(v.metalTexture());
				}
			}
		}
		List<BindGroupLayout.UniformDescription> uniforms = Objects.requireNonNull(this.pipeline).uniforms;
		for (int i = 0; i < this.dirtyUpTo && i < uniforms.size(); i++) {
			Object value = this.pending[i];
			if (value == null) throw new IllegalStateException("Missing uniform " + uniforms.get(i).name());
			if (value.equals(this.bound[i])) continue;
			this.bound[i] = value;
			this.bindUniform(uniforms.get(i), i, value);
		}
		this.dirtyUpTo = 0;
	}

	private void bindUniform(BindGroupLayout.UniformDescription uniform, int index, Object value) {
		int i = index;
		if (this.delegate != null) {
			if (this.slots == null || index >= this.slots.length || this.slots[index] < 0) return;
			i = this.slots[index];
		}
		switch (uniform.type()) {
			case UNIFORM_BUFFER -> {
				GpuBufferSlice slice = (GpuBufferSlice) value;
				Native.buffer(this.enc, i, this.encoder.use(slice.buffer()).handle, slice.offset());
			}
			case COMBINED_IMAGE_SAMPLER -> {
				TextureViewAndSampler ts = (TextureViewAndSampler) value;
				Native.texture(this.enc, i, ((MetalTexture.View) ts.view()).handle, ((MetalSampler) ts.sampler()).handle());
				if (this.encoder.encLogOn()) this.encoder.encBind(((MetalTexture.View) ts.view()).metalTexture());
				if (this.delegate != null) this.delegate.texture(this.enc, i, ts.view());
			}
			case TEXEL_BUFFER -> {
				GpuBufferSlice slice = (GpuBufferSlice) value;
				var format = Objects.requireNonNull(uniform.gpuFormat());
				long view = TexelViews.ON && TexelViews.active ? TexelViews.get(this.encoder, slice, MetalConst.pixelFormat(format), format.blockSize())
					: this.texelViews.computeIfAbsent(slice, s -> {
					long handle = Native.textureBuffer(this.encoder.ctx, this.encoder.use(s.buffer()).handle, MetalConst.pixelFormat(format), s.offset(),
						s.length(), format.blockSize());
					this.encoder.releaseLater(handle);
					return handle;
				});
				Native.texture(this.enc, i, view, 0);
			}
		}
	}

	@Override
	public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {
		if (!this.delegated()) return;
		this.bindUniforms(1);
		this.indices += (long) indexCount * instanceCount;
		String name = Objects.requireNonNull(this.pipeline).name;
		boolean clouds = name.equals("minecraft:pipeline/clouds") || name.equals("minecraft:pipeline/flat_clouds");
		if (clouds && mcopt.metal.shade.clouds.ShadeClouds.ON && mcopt.metal.shade.clouds.ShadeClouds.replacesVanilla()) return;
		if (this.encoder.terrain.split && clouds && instanceCount == 1 && firstIndex == 0 && vertexOffset == 0 && this.drawClouds(indexCount / 6)) return;
		if (indexCount <= 0 || instanceCount <= 0) return;
		Native.drawIndexed(this.enc, indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
		MetalDrawTap.Listener tap = MetalDrawTap.listener;
		if (tap != null && this.formats != null) this.tap(tap, Objects.requireNonNull(this.pipeline), indexCount, vertexOffset);
	}

	private void tap(MetalDrawTap.Listener tap, MetalPipeline p, int indexCount, int vertexOffset) {
		GpuBufferSlice vb = this.vertexBuffer0;
		if (p.tapUv < 0 || vb == null || this.delegate != null) return;
		if (!(this.bound[p.tapSampler] instanceof TextureViewAndSampler ts)) return;
		tap.draw(p.name, this.encoder.use(vb.buffer()).handle, vb.offset() + (long) vertexOffset * p.tapStride, indexCount / 6, p.tapStride, p.tapUv,
			((MetalTexture.View) ts.view()).handle);
	}

	private boolean drawClouds(int faces) {
		MetalPipeline p = Objects.requireNonNull(this.pipeline);
		int at = uniform(p, "CloudFaces");
		MetalBuffer kept = this.encoder.terrain.cullClouds((GpuBufferSlice) this.bound[at], faces, (GpuBufferSlice) this.bound[uniform(p, "DynamicTransforms")],
			(GpuBufferSlice) this.bound[uniform(p, "Projection")], (GpuBufferSlice) this.bound[uniform(p, "CloudInfo")]);
		if (kept == null) return false;
		this.bindUniform(p.uniforms.get(at), at, kept.slice(MetalTerrain.CLOUD_FACES, MetalTerrain.cloudFacesLength(faces)));
		this.bound[at] = null;
		this.dirtyUpTo = Math.max(this.dirtyUpTo, at + 1);
		Native.drawIndexedIndirect(this.enc, kept.handle, 0, 1);
		return true;
	}

	@Override
	public void multiDrawIndexed(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
		if (!this.delegated()) return;
		this.bindUniforms(drawCount);
		Native.multiDrawIndexed(this.enc, MemoryUtil.memAddress(drawParameters), drawCount, instanceCount, firstInstance);
	}

	@Override
	public void multiDrawIndexed(PointerBuffer firstIndexOffsets, IntBuffer indexCounts, IntBuffer vertexOffsets, int drawCount) {
		if (!this.delegated()) return;
		this.bindUniforms(drawCount);
		if (this.encoder.tracing()) for (int i = 0; i < drawCount; i++) this.indices += indexCounts.get(indexCounts.position() + i);
		if (this.recording != null) {
			this.encoder.terrain.record(Objects.requireNonNull(this.vertexBuffer0), indexCounts, vertexOffsets, drawCount);
			return;
		}
		Native.multiDrawIndexedSeparate(this.enc, MemoryUtil.memAddress(firstIndexOffsets), MemoryUtil.memAddress(indexCounts),
			MemoryUtil.memAddress(vertexOffsets), drawCount);
	}

	@Override
	public void drawIndexedIndirect(GpuBufferSlice commands, int drawCount) {
		if (!this.delegated()) return;
		this.bindUniforms(drawCount);
		Native.drawIndexedIndirect(this.enc, this.encoder.use(commands.buffer()).handle, commands.offset(), drawCount);
	}

	@Override
	public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
		if (!this.delegated()) return;
		this.bindUniforms(1);
		this.indices += (long) vertexCount * instanceCount;
		if (vertexCount <= 0 || instanceCount <= 0) return;
		Native.draw(this.enc, vertexCount, instanceCount, firstVertex, firstInstance);
	}

	@Override
	public void multiDraw(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
		if (!this.delegated()) return;
		this.bindUniforms(drawCount);
		Native.multiDraw(this.enc, MemoryUtil.memAddress(drawParameters), drawCount, instanceCount, firstInstance);
	}

	@Override
	public void multiDraw(IntBuffer firstVertices, IntBuffer vertexCounts, int drawCount) {
		if (!this.delegated()) return;
		this.bindUniforms(drawCount);
		Native.multiDrawSeparate(this.enc, MemoryUtil.memAddress(firstVertices), MemoryUtil.memAddress(vertexCounts), drawCount);
	}

	@Override
	public void drawIndirect(GpuBufferSlice commands, int drawCount) {
		if (!this.delegated()) return;
		this.bindUniforms(drawCount);
		Native.drawIndirect(this.enc, this.encoder.use(commands.buffer()).handle, commands.offset(), drawCount);
	}

	@Override
	public void writeTimestamp(GpuQueryPool pool, int index) {
	}
}
