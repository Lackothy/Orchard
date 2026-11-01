package mcopt.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendOp;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import java.lang.foreign.SymbolLookup;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

public final class MetalBridge {
	public static void waitIdle(Object encoder) {
		((MetalEncoder) encoder).waitIdle();
	}

	private MetalBridge() {
	}

	public static SymbolLookup library() {
		return Native.lookup();
	}

	public static long createHeadlessContext() {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long name = stack.nmalloc(1, 256), err = stack.nmalloc(1, 1024);
			long ctx = Native.create(name, 256, err, 1024);
			if (ctx == 0) throw new IllegalStateException("Metal unavailable: " + MemoryUtil.memUTF8(err));
			return ctx;
		}
	}

	public static long createHeadlessEncoder(long ctx) {
		return Native.encNew(ctx);
	}

	public static void commitAndWait(long enc) {
		long cmd = Native.encCommit(enc);
		Native.cmdWait(cmd);
		Native.release(cmd);
	}

	public static long newTexture(long ctx, GpuFormat format, int width, int height, boolean sampled) {
		return Native.textureNew(ctx, MetalConst.pixelFormat(format), width, height, 1, 1, 4 | (sampled ? 1 : 0), 0);
	}

	public static long newBuffer(long ctx, long size) {
		return Native.bufferNew(ctx, size);
	}

	public static long bufferContents(long buffer) {
		return Native.bufferContents(buffer);
	}

	public static void readTexture(long enc, long texture, int width, int height, int bytesPerPixel, long buffer) {
		Native.blitTextureToBuffer(enc, texture, 0, 0, 0, width, height, buffer, 0, width * bytesPerPixel);
	}

	public static void readTextureRegion(long enc, long texture, int x, int y, int w, int h, int bytesPerPixel, long buffer, long offset) {
		Native.blitTextureToBuffer(enc, texture, 0, x, y, w, h, buffer, offset, w * bytesPerPixel);
	}

	public static int pixelFormat(GpuFormat format) {
		return MetalConst.pixelFormat(format);
	}

	public static int vertexFormat(GpuFormat format) {
		return MetalConst.vertexFormat(format);
	}

	public static int blendFactor(BlendFactor factor) {
		return MetalConst.blendFactor(factor);
	}

	public static int blendOp(BlendOp op) {
		return MetalConst.blendOp(op);
	}

	public static int compare(CompareOp op) {
		return MetalConst.compare(op);
	}

	public static int primitive(PrimitiveTopology topology) {
		return MetalConst.primitive(topology);
	}

	public static int topologyClass(PrimitiveTopology topology) {
		return MetalConst.topologyClass(topology);
	}

	public static long textureHandle(GpuTexture texture) {
		return ((MetalTexture) texture).handle;
	}

	public static int textureFormat(GpuTexture texture) {
		return MetalConst.pixelFormat(texture.getFormat());
	}

	public static boolean shaderWritable(GpuTexture texture) {
		return texture instanceof MetalTexture t && t.shaderWrite;
	}

	public static long viewHandle(GpuTextureView view) {
		return ((MetalTexture.View) view).handle;
	}

	public static long samplerHandle(GpuSampler sampler) {
		return ((MetalSampler) sampler).handle();
	}

	public static float[] samplerParams(GpuSampler sampler) {
		MetalSampler s = (MetalSampler) sampler;
		double lod = s.getMaxLod().orElse(1000.0);
		return new float[] {MetalConst.addressMode(s.getAddressModeU()), MetalConst.addressMode(s.getAddressModeV()), MetalConst.filter(s.getMinFilter()),
			MetalConst.filter(s.getMagFilter()), lod > 0.25 ? 2 : 0, Math.max(1, s.getMaxAnisotropy()), (float) Math.max(0.25, lod)};
	}

	public static long bufferHandle(GpuBuffer buffer) {
		return ((MetalBuffer) buffer).handle;
	}

	public static @Nullable Object encoder(CommandEncoderBackend backend) {
		return backend instanceof MetalEncoder e ? e : null;
	}

	public static long useBuffer(Object encoder, GpuBuffer buffer) {
		return ((MetalEncoder) encoder).use(buffer).handle;
	}

	public static long bufferAddress(GpuBuffer buffer) {
		return ((MetalBuffer) buffer).address;
	}

	public static long enc(Object encoder) {
		return ((MetalEncoder) encoder).enc;
	}

	public static boolean tracing(Object encoder) {
		return ((MetalEncoder) encoder).tracing();
	}

	public static long ctx(Object encoder) {
		return ((MetalEncoder) encoder).ctx;
	}

	public static long deviceAllocated(long ctx) {
		return Native.deviceAllocated(ctx);
	}

	public static final float[] DONT_CARE = new float[0];

	public static int renderBegin(long enc, long[] colors, float @Nullable [][] clears, long depth, boolean clearDepth, float depthValue, int width, int height) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long handles = stack.nmalloc(8, Math.max(1, colors.length) * 8);
			long clearData = stack.ncalloc(4, Math.max(1, colors.length) * 5, 4);
			for (int i = 0; i < colors.length; i++) {
				MemoryUtil.memPutAddress(handles + i * 8L, colors[i]);
				float[] c = clears == null ? null : clears[i];
				if (c == DONT_CARE) {
					MemoryUtil.memPutFloat(clearData + i * 20L, 2);
				} else if (c != null) {
					long at = clearData + i * 20L;
					MemoryUtil.memPutFloat(at, 1);
					for (int k = 0; k < 4; k++) MemoryUtil.memPutFloat(at + 4 + k * 4L, c[k]);
				}
			}
			return Native.renderBegin(enc, colors.length, handles, clearData, depth, clearDepth ? 1 : 0, depthValue, width, height);
		}
	}

	public static void blitTextureToTexture(long enc, long src, long dst, int mip, int width, int height) {
		Native.blitTextureToTexture(enc, src, dst, mip, 0, 0, 0, 0, width, height);
	}

	public static void dropPendingClear(Object encoder, GpuTexture texture) {
		((MetalEncoder) encoder).dropPendingClear((MetalTexture) texture);
	}

	public static float takePendingDepthClear(Object encoder, GpuTexture texture) {
		MetalTexture t = (MetalTexture) texture;
		float value = (float) t.pendingDepthClear;
		((MetalEncoder) encoder).dropPendingClear(t);
		return value;
	}

	public static float @Nullable [] takePendingColorClear(Object encoder, GpuTexture texture) {
		return ((MetalEncoder) encoder).takePendingColorClear((MetalTexture) texture);
	}

	public static void discard(long enc, long texture) {
		Native.discard(enc, texture);
	}

	public static void setSubRect(Object encoder, @Nullable GpuTexture color, @Nullable GpuTexture depth, int width, int height) {
		((MetalEncoder) encoder).setSubRect(color, depth, width, height);
	}

	public static void flushClear(Object encoder, GpuTexture texture) {
		((MetalEncoder) encoder).flushClear(texture);
	}

	public static void index(long enc, GpuBuffer buffer, boolean intIndices) {
		Native.index(enc, ((MetalBuffer) buffer).handle, intIndices ? 1 : 0);
	}

	public static void drawIndexed(long enc, int indexCount, int instances, int firstIndex, int baseVertex, int firstInstance) {
		Native.drawIndexed(enc, indexCount, instances, firstIndex, baseVertex, firstInstance);
	}

	public static void vertexBuffer(long enc, int index, long buffer, long offset) {
		Native.vertexBuffer(enc, index, buffer, offset);
	}

	public static void bindOwnPipeline(long enc, Object backendPipeline, boolean hasDepth) {
		MetalPipeline p = (MetalPipeline) backendPipeline;
		Native.pipeline(enc, hasDepth ? p.withDepth : p.withoutDepth, p.depthState, p.cull ? 1 : 0, p.wireframe ? 1 : 0, p.depthBiasConstant, p.depthBiasSlope, p.primitive);
	}

	public static @Nullable String pipelineName(Object backendPipeline) {
		return backendPipeline instanceof MetalPipeline p ? p.name : null;
	}

	public static boolean reapplyPipeline(Object encoder) {
		return ((MetalEncoder) encoder).reapplyPipeline();
	}

	public static boolean restorePass(Object encoder) {
		return ((MetalEncoder) encoder).restorePass();
	}

	public static boolean inRenderPass(Object encoder) {
		return ((MetalEncoder) encoder).inRenderPass();
	}

	public static void release(long handle) {
		Native.release(handle);
	}

	public static long pipelineVariant(Object backendPipeline, int[] colorFormats, boolean depth, boolean pulled) {
		return ((MetalPipeline) backendPipeline).variant(colorFormats, depth, pulled);
	}

	public static java.util.Map<String, Object> compileStats() {
		return MetalPipeline.compileStats();
	}

	public static void captureWater(Object encoder) {
		((MetalEncoder) encoder).captureWater();
	}

	public static void flushHeldTerrainCopies(Object encoder) {
		((MetalEncoder) encoder).terrain.releaseHeld(false);
	}
}
