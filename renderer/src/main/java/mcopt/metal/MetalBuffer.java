package mcopt.metal;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.backend.common.BaseGpuBuffer;
import java.util.ArrayDeque;
import org.lwjgl.system.MemoryUtil;

class MetalBuffer extends BaseGpuBuffer {
	long handle, address;
	long lastUse = -1;
	boolean arena;
	private final MetalEncoder encoder;
	private boolean closed, mapped;
	private final ArrayDeque<Long> spares = new ArrayDeque<>();

	static long allocate(long ctx, long size) {
		return Native.bufferNew(ctx, size > 0 ? (size + 15) & ~15L : size);
	}

	MetalBuffer(MetalEncoder encoder, long handle, @GpuBuffer.Usage int usage, long size) {
		this(encoder, handle, usage, size, Native.bufferContents(handle));
	}

	MetalBuffer(MetalEncoder encoder, long handle, @GpuBuffer.Usage int usage, long size, long address) {
		super(usage, size);
		this.encoder = encoder;
		this.handle = handle;
		this.address = address;
	}

	@Override
	public boolean isClosed() {
		return this.closed;
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			this.encoder.releaseLater(this.handle);
			for (long spare : this.spares) this.encoder.releaseLater(spare);
			this.spares.clear();
		}
	}

	boolean rename() {
		if (this.mapped || this.arena) return false;
		long old = this.handle;
		Long spare = this.spares.poll();
		this.handle = spare != null ? spare : allocate(this.encoder.ctx, this.size());
		this.address = Native.bufferContents(this.handle);
		this.lastUse = -1;
		this.encoder.afterGpuFinishes(() -> {
			if (this.closed) Native.release(old);
			else this.spares.add(old);
		});
		return true;
	}

	@Override
	public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
		if (this.closed) throw new IllegalStateException("Buffer already closed");
		if (offset < 0 || length < 0 || offset + length > this.size()) throw new IllegalArgumentException("Mapping out of range");
		this.mapped = true;
		return new GpuBufferSlice.MappedView(this.slice(offset, length), MemoryUtil.memByteBuffer(this.address + offset, Math.toIntExact(length)), () -> {});
	}
}
