package mcopt.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.DeviceFeatures;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.DeviceLimits;
import com.mojang.renderpearl.api.device.DeviceType;
import com.mojang.renderpearl.api.device.HintsAndWorkarounds;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

final class MetalDevice implements GpuDeviceBackend {
	private static final long ARENA_RESERVE = Long.getLong("mcopt.metal.arenaReserve", 256L << 20);
	private static final long ARENA_RESERVE_MIN = 64L << 20;
	private boolean arenaReserved;
	private final long ctx;
	private final MetalEncoder encoder;
	private final DeviceInfo info;

	MetalDevice() throws BackendCreationException {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long name = stack.nmalloc(1, 256), err = stack.nmalloc(1, 1024);
			this.ctx = Native.create(name, 256, err, 1024);
			if (this.ctx == 0) throw new BackendCreationException("Metal unavailable: " + MemoryUtil.memUTF8(err), BackendCreationException.Reason.PLATFORM_ERROR);
			this.info = new DeviceInfo(
				MemoryUtil.memUTF8(name), "Apple", "Metal 3 (mcopt)", true, "Metal", 1.0F,
				new DeviceLimits(16, 256, 16384, Native.maxBufferLength(this.ctx), Integer.MAX_VALUE, 8, Integer.MAX_VALUE),
				new DeviceFeatures(true, true, true, true, true, true, true, true),
				Set.of(),
				new HintsAndWorkarounds(false, false, true, false),
				DeviceType.INTEGRATED);
		}
		this.encoder = new MetalEncoder(this.ctx);
		String diag = System.getProperty("mcopt.metal.cbdiag");
		if (diag != null) Native.diagEnable(Double.parseDouble(diag));
		if (MetalPipeline.GPU_COUNT) Native.gpuCountEnable(MetalPipeline.GPU_COUNT_PATH, Integer.getInteger("mcopt.metal.gpuCount.every", 1),
			!"false".equals(System.getProperty("mcopt.metal.gpuCount.groups")));
		String math = System.getProperty("mcopt.shade.mathMode");
		if (math != null && !math.equals("fast")) {
			Native.mathMode(math.equals("safe") ? 0 : 1);
			System.out.println("[mcopt] metal: shaders compile with " + (math.equals("safe") ? "safe" : "relaxed") + " math");
		}
		if (mcopt.metal.cpu.Cpu.PASS || mcopt.metal.cpu.Cpu.CMD_AHEAD) Native.cpuFlags(mcopt.metal.cpu.Cpu.nativeFlags(mcopt.metal.cpu.Cpu.PASS));
		if (mcopt.metal.shade.Shade.ENABLED) mcopt.metal.shade.ShadeCompile.start(this.ctx);
	}

	private boolean reserveArena(int delayMs) {
		if (!Native.poolAdd(this.ctx, ARENA_RESERVE, delayMs)) return false;
		if (MetalTerrain.OCC) {
			long cache = (ARENA_RESERVE / 80 + 2) * 8;
			for (int i = 0; i < 2; i++) Native.poolAdd(this.ctx, cache, delayMs);
		}
		return true;
	}

	@Override
	public GpuSurfaceBackend createSurface(long windowHandle, BooleanSupplier isIconified) {
		return new MetalSurface(this.ctx, this.encoder, windowHandle);
	}

	@Override
	public CommandEncoderBackend createCommandEncoder() {
		return this.encoder;
	}

	@Override
	public GpuSampler createSampler(AddressMode u, AddressMode v, FilterMode min, FilterMode mag, int maxAnisotropy, OptionalDouble maxLod) {
		return MetalSampler.create(this.ctx, u, v, min, mag, maxAnisotropy, maxLod);
	}

	private static long residentBytes, residentCount;

	@Override
	public GpuTexture createTexture(@Nullable String label, @GpuTexture.Usage int usage, GpuFormat format, int width, int height, int layers, int mips) {
		int mtlUsage = ((usage & GpuTexture.USAGE_TEXTURE_BINDING) != 0 ? 1 : 0) | ((usage & GpuTexture.USAGE_RENDER_ATTACHMENT) != 0 ? 4 : 0);
		if (MetalTerrain.OCC && format == GpuFormat.D32_FLOAT) mtlUsage |= 1;
		if (mcopt.metal.own.AnimCopy.creatingAnimatedAtlas) mtlUsage |= 2;
		boolean cube = (usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0;
		long handle = Native.textureNew(this.ctx, MetalConst.pixelFormat(format), width, height, layers, mips, Math.max(1, mtlUsage), cube ? 1 : 0);
		if (handle == 0) throw new IllegalStateException("Couldn't create " + width + "x" + height + " " + format + " texture " + label);
		MetalTexture texture = new MetalTexture(this.encoder, handle, usage, label == null ? "" : label, format, width, height, layers, mips);
		texture.shaderWrite = (mtlUsage & 2) != 0;
		if (ResidentAnim.creating && Native.textureResident(this.ctx, handle, 1)) {
			texture.resident = true;
			long bytes = 0;
			for (int m = 0; m < mips; m++) bytes += (long) Math.max(1, width >> m) * Math.max(1, height >> m) * format.blockSize() * Math.max(1, layers);
			texture.residentBytes = bytes;
			residentBytes += bytes;
			if (++residentCount % 100 == 0) System.out.println("mcopt-metal: " + residentCount + " animation frame textures resident, " + residentBytes / 1024 + " KB");
		}
		return texture;
	}

	@Override
	public GpuTextureView createTextureView(GpuTexture texture, int baseMip, int mips) {
		return new MetalTexture.View(this.encoder, (MetalTexture) texture, baseMip, mips);
	}

	@Override
	public GpuBuffer createBuffer(@Nullable Supplier<String> label, @GpuBuffer.Usage int usage, long size) {
		MetalEvents.Operation event = MetalEvents.begin("allocate", -1, size);
		boolean arena = label != null && "Arena buffer".equals(label.get());
		MetalBuffer buffer;
		try {
			long handle = 0, actual = size;
			if (arena && ARENA_RESERVE > 0 && size >= ARENA_RESERVE_MIN) {
				if (size <= ARENA_RESERVE && size * 2 >= ARENA_RESERVE) handle = Native.poolTake(size, ARENA_RESERVE);
				if (handle != 0) {
					actual = Native.bufferLength(handle);
					this.arenaReserved = false;
				}
				if (!this.arenaReserved) this.arenaReserved = this.reserveArena(handle != 0 ? 1000 : 0);
			}
			buffer = new MetalBuffer(this.encoder, handle != 0 ? handle : MetalBuffer.allocate(this.ctx, size), usage, actual);
		} finally {
			MetalEvents.end(event);
		}
		buffer.arena = MetalTerrain.OCC && arena;
		if (buffer.arena) MetalEvents.arenaTransfer("allocate", size, 0, size);
		return buffer;
	}

	@Override
	public GpuBuffer createBuffer(@Nullable Supplier<String> label, @GpuBuffer.Usage int usage, ByteBuffer data) {
		MetalBuffer buffer = new MetalBuffer(this.encoder, MetalBuffer.allocate(this.ctx, data.remaining()), usage, data.remaining());
		MemoryUtil.memCopy(MemoryUtil.memAddress(data), buffer.address, data.remaining());
		return buffer;
	}

	@Override
	public List<String> getLastDebugMessages() {
		return List.of();
	}

	@Override
	public boolean isDebuggingEnabled() {
		return false;
	}

	@Override
	public BackendRenderPipeline.Pending compilePipeline(BackendRenderPipeline.CreateInfo info) {
		MetalPipeline pipeline = MetalPipeline.compile(this.encoder, info);
		return () -> pipeline;
	}

	@Override
	public void close() {
		this.encoder.waitIdle();
	}

	@Override
	public GpuQueryPool createTimestampQueryPool(int size) {
		return new GpuQueryPool() {
			@Override
			public int size() {
				return size;
			}

			@Override
			public OptionalLong getValue(int index) {
				return OptionalLong.empty();
			}

			@Override
			public OptionalLong[] getValues(int index, int count) {
				OptionalLong[] values = new OptionalLong[count];
				java.util.Arrays.fill(values, OptionalLong.empty());
				return values;
			}

			@Override
			public void close() {
			}
		};
	}

	@Override
	public long getTimestampCalibrationOffset() {
		return 0;
	}

	@Override
	public DeviceInfo getDeviceInfo() {
		return this.info;
	}

	static void residentReleased(MetalTexture t) {
		residentBytes -= t.residentBytes;
		residentCount--;
	}

	public static long[] residentAnimation() {
		return new long[] {residentCount, residentBytes};
	}
}
