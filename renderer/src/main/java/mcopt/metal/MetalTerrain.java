package mcopt.metal;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

final class MetalTerrain {
	private static final String MODE = System.getProperty("mcopt.metal.occ", "auto");
	static final boolean OCC = false;
	static final int QUADS_SLOT = 20, REGIONS_SLOT = 21, ARENAS_SLOT = 22;
	private static final int CHUNK_QUADS = 64, MAX_ARENAS = 8, REGION_BYTES = 20, CHUNK_BYTES = 8, VERTEX_BYTES = 20;
	private static final int TABLES_BYTES = 2 * 5 * MAX_ARENAS * 8, FRAME_BYTES = 96;
	private static final int BOUNDS_BYTES = 8;
	private static final int FRAMES = 3, MAX_FLUSHES = 4;

	private final MetalEncoder encoder;
	private final long handle;
	private final MetalOccPick pick = new MetalOccPick();
	boolean split = OCC;
	long terrainPixels;
	private final MetalBuffer indices;
	private final List<MetalBuffer> arenas = new ArrayList<>(MAX_ARENAS);
	private final Map<MetalBuffer, ArenaCache> caches = new HashMap<>();
	private final Set<MetalBuffer> cleared = new HashSet<>();
	private final long pushConstants = MemoryUtil.nmemCalloc(1, REGION_BYTES);
	private long chunks = MemoryUtil.nmemAlloc(CHUNK_BYTES * 8192L), regions = MemoryUtil.nmemAlloc(REGION_BYTES * 1024L);
	private int chunkCapacity = 8192, regionCapacity = 1024, chunkCount, regionCount;
	private final long[][] secondArgs = new long[FRAMES][MAX_FLUSHES];
	private final MetalBuffer[][] work = new MetalBuffer[FRAMES][MAX_FLUSHES];
	private final long[][] args = new long[FRAMES][MAX_FLUSHES];
	private final int[] slotFlushes = new int[FRAMES];
	private final long[] requested = new long[FRAMES], requestedChunks = new long[FRAMES];
	private int frame, flushes;
	private long frames;
	long terrainFrames;
	final List<Drawn> drawnForProbe = new ArrayList<>();
	private final List<Waiting> waiting = new ArrayList<>(MAX_FLUSHES);
	private final long waitingTables = MemoryUtil.nmemAlloc((long) MAX_FLUSHES * (TABLES_BYTES + FRAME_BYTES));
	private final List<Copy> held = new ArrayList<>();
	private final MetalBuffer[] clouds = new MetalBuffer[FRAMES];
	static final long CLOUD_FACES = 256;
	private long cloudsFrame = -1;

	private record Copy(long src, long srcOffset, long dst, long dstOffset, long size) {
	}

	record Drawn(MetalBuffer work, long quads, long args, MetalBuffer[] arenas) {
	}

	private record Waiting(MetalPipeline p, Runnable restore, MetalBuffer chunks, long chunksOffset, int chunkCount, MetalBuffer regions, long regionsOffset,
		List<MetalBuffer> arenas, long tables, MetalBuffer work, Work second) {
	}

	private static final class ArenaCache {
		final MetalBuffer bounds;
		final MetalBuffer boxes;
		final MetalBuffer[] visible = new MetalBuffer[2];
		boolean stale;

		ArenaCache(MetalEncoder encoder, MetalBuffer arena) {
			long slots = arena.size() / (4L * VERTEX_BYTES) + 2;
			this.bounds = zeroed(encoder, slots * BOUNDS_BYTES);
			this.boxes = zeroed(encoder, slots * BOUNDS_BYTES);
			for (int i = 0; i < 2; i++) this.visible[i] = zeroed(encoder, (slots / 32 + 1) * 4);
		}

		private static MetalBuffer zeroed(MetalEncoder encoder, long size) {
			MetalBuffer b = new MetalBuffer(encoder, Native.bufferNew(encoder.ctx, size), 0, size);
			MemoryUtil.memSet(b.address, 0, size);
			return b;
		}

		void close() {
			this.bounds.close();
			this.boxes.close();
			for (MetalBuffer v : this.visible) v.close();
		}
	}

	MetalTerrain(MetalEncoder encoder) {
		this.encoder = encoder;
		int size = CHUNK_QUADS * 6 * 2;
		long shared = Native.bufferNew(encoder.ctx, size), address = Native.bufferContents(shared);
		for (int q = 0, i = 0; q < CHUNK_QUADS; q++) {
			for (int corner : new int[] {0, 1, 2, 2, 3, 0}) MemoryUtil.memPutShort(address + 2L * i++, (short) (q * 4 + corner));
		}
		this.indices = new MetalBuffer(encoder, Native.bufferPrivate(encoder.ctx, shared, size), GpuBuffer.USAGE_INDEX, size, 0L);
		Native.release(shared);
		if (!OCC) {
			this.handle = 0;
			return;
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long err = stack.nmalloc(1, 1024);
			this.handle = unavailable();
			if (this.handle == 0) throw new IllegalStateException("terrain culling: " + MemoryUtil.memUTF8(err));
		}
	}

	void invalidate(MetalBuffer arena, long offset, long length) {
		ArenaCache cache = this.caches.get(arena);
		if (cache == null) return;
		if (!this.split) {
			cache.stale = true;
			return;
		}
		long first = firstSlot(offset), last = Math.min(cache.bounds.size() / BOUNDS_BYTES - 1, lastSlot(offset, length));
		if (last < first) return;
		this.zero(cache.bounds, first, last);
		this.zero(cache.boxes, Math.max(0, first - (CHUNK_QUADS - 1)), last);
	}

	private void zero(MetalBuffer cache, long first, long last) {
		long at = first * BOUNDS_BYTES, size = (last - first + 1) * BOUNDS_BYTES;
		if (this.culledThisFrame()) Native.fillBuffer(this.encoder.enc, this.encoder.use(cache).handle, at, size, 0);
		else Native.preFill(this.encoder.enc, this.encoder.use(cache).handle, at, size, 0);
	}

	private static long firstSlot(long offset) {
		return Math.max(0, offset / VERTEX_BYTES - 3) >> 2;
	}

	private static long lastSlot(long offset, long length) {
		return ((offset + length + VERTEX_BYTES - 1) / VERTEX_BYTES - 1) >> 2;
	}

	void hold(MetalBuffer src, long srcOffset, MetalBuffer dst, long dstOffset, long size) {
		this.held.add(new Copy(src.handle, srcOffset, dst.handle, dstOffset, size));
		ArenaCache cache = this.caches.get(dst);
		if (cache == null) return;
		MetalBuffer last = cache.visible[(int) (this.terrainFrames & 1) ^ 1];
		long from = firstSlot(dstOffset) >> 3, to = Math.min(last.size() - 1, lastSlot(dstOffset, size) >> 3);
		if (to >= from) Native.preFill(this.encoder.enc, this.encoder.use(last).handle, from, to - from + 1, 0);
	}

	void releaseHeld(boolean pre) {
		for (Copy c : this.held) {
			if (pre) Native.preBlitBuffer(this.encoder.enc, c.src, c.srcOffset, c.dst, c.dstOffset, c.size);
			else Native.blitBuffer(this.encoder.enc, c.src, c.srcOffset, c.dst, c.dstOffset, c.size);
		}
		this.held.clear();
	}

	boolean culledThisFrame() {
		return this.flushes > 0;
	}

	void pushConstants(long address, int length) {
		MemoryUtil.memCopy(address, this.pushConstants, Math.min(length, REGION_BYTES));
	}

	void record(GpuBufferSlice vertexBuffer, IntBuffer indexCounts, IntBuffer vertexOffsets, int drawCount) {
		if (mcopt.metal.cpu.Cpu.RECORD && indexCounts.isDirect() && vertexOffsets.isDirect()) {
			boolean fast = !mcopt.metal.cpu.Cpu.RECORD_AB || (this.frames & 1) == 1;
			long t0 = mcopt.metal.cpu.Cpu.RECORD_AB ? System.nanoTime() : 0;
			if (fast) this.recordFast(vertexBuffer, indexCounts, vertexOffsets, drawCount);
			else this.recordPlain(vertexBuffer, indexCounts, vertexOffsets, drawCount);
			if (mcopt.metal.cpu.Cpu.RECORD_AB) mcopt.metal.cpu.Cpu.recordAb(fast, System.nanoTime() - t0, this.frames);
			return;
		}
		this.recordPlain(vertexBuffer, indexCounts, vertexOffsets, drawCount);
	}

	private void recordFast(GpuBufferSlice vertexBuffer, IntBuffer indexCounts, IntBuffer vertexOffsets, int drawCount) {
		MetalBuffer arena = (MetalBuffer) vertexBuffer.buffer();
		int arenaIndex = this.lastArenaIndex;
		if (arenaIndex >= this.arenas.size() || this.arenas.get(arenaIndex) != arena) {
			arenaIndex = this.arenas.indexOf(arena);
			if (arenaIndex < 0) {
				if (this.arenas.size() == MAX_ARENAS) throw new IllegalStateException("more than " + MAX_ARENAS + " Sodium geometry arenas");
				arenaIndex = this.arenas.size();
				this.arenas.add(arena);
			}
			this.lastArenaIndex = arenaIndex;
		}
		if (this.regionCount == this.regionCapacity) this.regions = MemoryUtil.nmemRealloc(this.regions, (long) (this.regionCapacity *= 2) * REGION_BYTES);
		MemoryUtil.memCopy(this.pushConstants, this.regions + (long) this.regionCount * REGION_BYTES, REGION_BYTES);
		int region = this.regionCount++;
		long base = vertexBuffer.offset() / VERTEX_BYTES;
		long counts = MemoryUtil.memAddress(indexCounts), offsets = MemoryUtil.memAddress(vertexOffsets);
		int needed = 0;
		for (int i = 0; i < drawCount; i++) needed += (MemoryUtil.memGetInt(counts + 4L * i) / 6 + CHUNK_QUADS - 1) / CHUNK_QUADS;
		while (this.chunkCount + needed > this.chunkCapacity) this.chunks = MemoryUtil.nmemRealloc(this.chunks, (long) (this.chunkCapacity *= 2) * CHUNK_BYTES);
		int tag = arenaIndex << 6 | region << 9;
		long at = this.chunks + (long) this.chunkCount * CHUNK_BYTES;
		for (int i = 0; i < drawCount; i++) {
			int quads = MemoryUtil.memGetInt(counts + 4L * i) / 6;
			long first = base + MemoryUtil.memGetInt(offsets + 4L * i);
			for (int q = 0; q < quads; q += CHUNK_QUADS) {
				MemoryUtil.memPutInt(at, (int) (first + q * 4L));
				MemoryUtil.memPutInt(at + 4, (Math.min(CHUNK_QUADS, quads - q) - 1) | tag);
				at += CHUNK_BYTES;
			}
		}
		this.chunkCount += needed;
	}

	private int lastArenaIndex;

	private void recordPlain(GpuBufferSlice vertexBuffer, IntBuffer indexCounts, IntBuffer vertexOffsets, int drawCount) {
		MetalBuffer arena = (MetalBuffer) vertexBuffer.buffer();
		int arenaIndex = this.arenas.indexOf(arena);
		if (arenaIndex < 0) {
			if (this.arenas.size() == MAX_ARENAS) throw new IllegalStateException("more than " + MAX_ARENAS + " Sodium geometry arenas");
			arenaIndex = this.arenas.size();
			this.arenas.add(arena);
		}
		if (this.regionCount == this.regionCapacity) this.regions = MemoryUtil.nmemRealloc(this.regions, (long) (this.regionCapacity *= 2) * REGION_BYTES);
		MemoryUtil.memCopy(this.pushConstants, this.regions + (long) this.regionCount * REGION_BYTES, REGION_BYTES);
		int region = this.regionCount++;
		long base = vertexBuffer.offset() / VERTEX_BYTES;
		for (int i = 0; i < drawCount; i++) {
			int quads = indexCounts.get(indexCounts.position() + i) / 6;
			long first = base + vertexOffsets.get(vertexOffsets.position() + i);
			for (int q = 0; q < quads; q += CHUNK_QUADS) {
				if (this.chunkCount == this.chunkCapacity) this.chunks = MemoryUtil.nmemRealloc(this.chunks, (long) (this.chunkCapacity *= 2) * CHUNK_BYTES);
				long at = this.chunks + (long) this.chunkCount++ * CHUNK_BYTES;
				MemoryUtil.memPutInt(at, (int) (first + q * 4L));
				MemoryUtil.memPutInt(at + 4, (Math.min(CHUNK_QUADS, quads - q) - 1) | arenaIndex << 6 | region << 9);
			}
		}
	}

	void flush(long enc, MetalPipeline p, GpuBufferSlice globals, int width, int height, Runnable restore) {
		if (this.chunkCount > 0) this.occlude(enc, p, globals, width, height, restore);
		this.chunkCount = 0;
		this.regionCount = 0;
		this.arenas.clear();
	}

	void endFrame() {
		this.slotFlushes[this.frame] = this.flushes;
		if (this.flushes > 0) this.terrainFrames++;
		this.cleared.clear();
		this.frame = (this.frame + 1) % FRAMES;
		this.flushes = 0;
		this.frames++;
		if (OCC && !MODE.equals("true")) this.split = this.pick.next(this.terrainPixels);
		mcopt.metal.cpu.Cpu.terrainSplitting = this.split;
		this.terrainPixels = 0;
		this.caches.entrySet().removeIf(e -> {
			if (!e.getKey().isClosed()) return false;
			e.getValue().close();
			return true;
		});
	}

	long[] lastCompletedCounts() {
		long drawn = 0, first = 0;
		for (int i = 0; i < this.slotFlushes[this.frame]; i++) {
			long work = this.work[this.frame][i].address, quads = 64L * MemoryUtil.memGetInt(work + this.args[this.frame][i] + 4);
			first += quads;
			drawn += quads + 64L * MemoryUtil.memGetInt(work + this.secondArgs[this.frame][i] + 4);
		}
		return new long[] {drawn, this.requested[this.frame], first, this.requestedChunks[this.frame]};
	}

	private void occlude(long enc, MetalPipeline p, GpuBufferSlice globals, int width, int height, Runnable restore) {
		this.countRequested();
		MetalTransientMemory memory = this.encoder.transientMemory;
		GpuBufferSlice chunkSlice = memory.uploadGpu(MemoryUtil.memByteBuffer(this.chunks, this.chunkCount * CHUNK_BYTES), 16L, GpuBuffer.USAGE_UNIFORM);
		GpuBufferSlice regionSlice = memory.uploadGpu(MemoryUtil.memByteBuffer(this.regions, this.regionCount * REGION_BYTES), 16L, GpuBuffer.USAGE_UNIFORM);
		MetalBuffer chunkBuffer = this.encoder.use(chunkSlice.buffer()), regionBuffer = this.encoder.use(regionSlice.buffer());
		int parity = (int) (this.terrainFrames & 1);
		long addresses = this.waitingTables + (long) this.waiting.size() * (TABLES_BYTES + FRAME_BYTES), handles = addresses + TABLES_BYTES / 2,
			frame = addresses + TABLES_BYTES;
		this.arenaTables(addresses, parity);
		frameData(frame, globals, width, height, this.chunkCount, this.frames);
		this.clearVisibility(enc, parity);
		Work first = new Work(this.chunkCount), second = first.at(first.size);
		MetalBuffer work = this.workBuffer(second.size, first.args);
		this.secondArgs[this.frame][this.flushes - 1] = second.args;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			unavailable();
		}
		List<MetalBuffer> arenas = List.copyOf(this.arenas);
		this.drawPulled(enc, p, arenas, addresses, work, first.quads, first.args, regionBuffer, regionSlice.offset());
		this.waiting.add(new Waiting(p, restore, chunkBuffer, chunkSlice.offset(), this.chunkCount, regionBuffer, regionSlice.offset(), arenas, addresses, work,
			second));
	}

	void split(long enc, Runnable rebind) {
		if (this.waiting.isEmpty()) return;
		this.releaseHeld(false);
		long compute = unavailable();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			for (int step = 0; step < 4; step++) {
				for (Waiting w : this.waiting) {
					unavailable();
				}
			}
		}
		Native.renderResume(enc, compute);
		for (Waiting w : this.waiting) {
			w.restore.run();
			this.drawPulled(enc, w.p, w.arenas, w.tables, w.work, w.second.quads, w.second.args, w.regions, w.regionsOffset);
		}
		this.waiting.clear();
		rebind.run();
	}

	private void countRequested() {
		if (this.flushes == 0) this.requested[this.frame] = this.requestedChunks[this.frame] = 0;
		this.requestedChunks[this.frame] += this.chunkCount;
		for (int i = 0; i < this.chunkCount; i++) this.requested[this.frame] += (MemoryUtil.memGetInt(this.chunks + i * (long) CHUNK_BYTES + 4) & 63) + 1;
	}

	private void arenaTables(long addresses, int parity) {
		long handles = addresses + TABLES_BYTES / 2;
		MemoryUtil.memSet(addresses, 0, TABLES_BYTES);
		for (int i = 0; i < this.arenas.size(); i++) {
			MetalBuffer arena = this.arenas.get(i);
			ArenaCache cache = this.caches.computeIfAbsent(arena, a -> new ArenaCache(this.encoder, a));
			if (cache.stale) {
				Native.preFill(this.encoder.enc, this.encoder.use(cache.bounds).handle, 0, cache.bounds.size(), 0);
				Native.preFill(this.encoder.enc, this.encoder.use(cache.boxes).handle, 0, cache.boxes.size(), 0);
				cache.stale = false;
			}
			MetalBuffer[] tables = {arena, cache.bounds, cache.visible[parity ^ 1], cache.visible[parity], cache.boxes};
			for (int t = 0; t < tables.length; t++) {
				MetalBuffer b = this.encoder.use(tables[t]);
				MemoryUtil.memPutLong(addresses + (t * MAX_ARENAS + i) * 8L, Native.gpuAddress(b.handle));
				MemoryUtil.memPutLong(handles + (t * MAX_ARENAS + i) * 8L, b.handle);
			}
		}
	}

	@Nullable MetalBuffer cullClouds(GpuBufferSlice faces, int count, GpuBufferSlice modelView, GpuBufferSlice projection, GpuBufferSlice info) {
		if (this.cloudsFrame == this.frames) return null;
		this.cloudsFrame = this.frames;
		long size = CLOUD_FACES + cloudFacesLength(count);
		MetalBuffer out = this.clouds[this.frame];
		if (out == null || out.size() < size) {
			if (out != null) out.close();
			out = new MetalBuffer(this.encoder, Native.bufferNew(this.encoder.ctx, size + size / 2), 0, size + size / 2);
			this.clouds[this.frame] = out;
		}
		unavailable();
		return out;
	}

	static long cloudFacesLength(int count) {
		return (3L * count + 255) & ~255L;
	}

	private void clearVisibility(long enc, int parity) {
		for (MetalBuffer arena : this.arenas) {
			if (!this.cleared.add(arena)) continue;
			MetalBuffer next = this.encoder.use(this.caches.get(arena).visible[parity]);
			Native.preFill(enc, next.handle, 0, next.size(), 0);
		}
	}

	private static void frameData(long frame, GpuBufferSlice globals, int width, int height, int count, long number) {
		MemoryUtil.memSet(frame, 0, FRAME_BYTES);
		long g = ((MetalBuffer) globals.buffer()).address + globals.offset();
		Matrix4f viewProj = new Matrix4f().set(MemoryUtil.memFloatBuffer(g, 16)).mul(new Matrix4f().set(MemoryUtil.memFloatBuffer(g + 64, 16)));
		viewProj.get(MemoryUtil.memFloatBuffer(frame, 16));
		MemoryUtil.memPutFloat(frame + 64, width);
		MemoryUtil.memPutFloat(frame + 68, height);
		MemoryUtil.memPutInt(frame + 72, count);
		MemoryUtil.memPutInt(frame + 84, (int) number);
	}

	private void drawPulled(long enc, MetalPipeline p, List<MetalBuffer> arenas, long addresses, MetalBuffer work, long list, long args, MetalBuffer regions,
		long regionsOffset) {
		Native.pipeline(enc, p.pulled, p.depthState, p.cull ? 1 : 0, 0, p.depthBiasConstant, p.depthBiasSlope, p.primitive);
		for (MetalBuffer arena : arenas) Native.useResource(enc, arena.handle);
		Native.bytes(enc, ARENAS_SLOT, addresses, MAX_ARENAS * 8);
		Native.buffer(enc, QUADS_SLOT, work.handle, list);
		Native.buffer(enc, REGIONS_SLOT, regions.handle, regionsOffset);
		Native.index(enc, this.encoder.use(this.indices).handle, 0);
		Native.drawIndexedIndirect(enc, work.handle, args, 1);
	}

	private MetalBuffer workBuffer(long size, long args) {
		if (this.flushes == MAX_FLUSHES) throw new IllegalStateException("more than " + MAX_FLUSHES + " terrain flushes in a frame");
		MetalBuffer b = this.work[this.frame][this.flushes];
		if (b == null || b.size() < size) {
			if (b != null) b.close();
			long capacity = size + size / 2;
			b = new MetalBuffer(this.encoder, Native.bufferNew(this.encoder.ctx, capacity), 0, capacity);
			this.work[this.frame][this.flushes] = b;
		}
		this.args[this.frame][this.flushes++] = args;
		return this.encoder.use(b);
	}

	private record Work(long masks, long counts, long offsets, long args, long quads, long size) {
		Work(int n) {
			this(0, align(8L * n), align(8L * n) + align(4L * n), align(8L * n) + 2 * align(4L * n), align(8L * n) + 2 * align(4L * n) + 32,
				align(8L * n) + 2 * align(4L * n) + 32 + 8L * CHUNK_QUADS * n);
		}

		Work at(long base) {
			return new Work(base + this.masks, base + this.counts, base + this.offsets, base + this.args, base + this.quads, base + this.size);
		}

		long offsets(MemoryStack stack) {
			long o = stack.nmalloc(8, 5 * 8);
			long[] v = {this.masks, this.counts, this.offsets, this.quads, this.args};
			for (int i = 0; i < v.length; i++) MemoryUtil.memPutLong(o + i * 8L, v[i]);
			return o;
		}

		private static long align(long x) {
			return (x + 15) & ~15L;
		}
	}

	private static long unavailable() {
		throw new IllegalStateException("terrain occlusion is not part of this build");
	}
}
