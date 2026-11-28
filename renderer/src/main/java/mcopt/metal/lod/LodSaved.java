package mcopt.metal.lod;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.ticks.ProtoChunkTicks;

final class LodSaved {
	static final boolean ON = Boolean.parseBoolean(System.getProperty("mcopt.lod.saved", "true"));
	private static final int IN_FLIGHT = 4;
	private static final double PRIORITY = 500;

	private final ServerLevel level;
	private final LodField field;
	private final LodClip clip;
	private final Path regions;
	private final int dataVersion;
	private static final long HEADER_NANOS = 30_000_000_000L;

	private record Header(long[] bits, long readAt) {
	}

	private final ConcurrentHashMap<Long, Header> saved = new ConcurrentHashMap<>();
	private final LinkedHashMap<Long, Integer> wanted = new LinkedHashMap<>();
	private int inFlight;
	private final AtomicInteger finding = new AtomicInteger();
	private volatile boolean closed;
	final AtomicLong read = new AtomicLong(), applied = new AtomicLong(), skipped = new AtomicLong(), nanos = new AtomicLong();

	LodSaved(ServerLevel level, MinecraftServer server, LodField field, LodClip clip) {
		this.level = level;
		this.field = field;
		this.clip = clip;
		this.regions = DimensionType.getStorageFolder(level.dimension(), server.getWorldPath(LevelResource.ROOT)).resolve("region");
		this.dataVersion = SharedConstants.getCurrentVersion().dataVersion().version();
	}

	void close() {
		this.closed = true;
	}

	synchronized int pending() {
		return this.finding.get() + this.wanted.size() + this.inFlight;
	}

	void arrived(int level, int tx, int tz) {
		if (this.closed) return;
		this.finding.incrementAndGet();
		this.field.work(PRIORITY, () -> {
			try {
				this.find(level, tx, tz);
			} finally {
				this.finding.decrementAndGet();
			}
		});
	}

	private void find(int level, int tx, int tz) {
		if (this.closed) return;
		int span = this.clip.span(level), cell = 1 << level, step = Math.max(1, cell >> 4);
		int c0x = Math.floorDiv(tx * span, 16), c0z = Math.floorDiv(tz * span, 16), chunks = span >> 4;
		int found = 0;
		ClientLevel client = Minecraft.getInstance().level;
		for (int z = 0; z < chunks; z += step) {
			for (int x = 0; x < chunks; x += step) {
				int cx = c0x + x, cz = c0z + z;
				long[] bits = this.region(cx >> 5, cz >> 5);
				int i = (cx & 31) + (cz & 31) * 32;
				if ((bits[i >> 6] >>> (i & 63) & 1) == 0 || client != null && client.getChunkSource().hasChunk(cx, cz)) continue;
				synchronized (this) {
					this.wanted.merge(ChunkPos.pack(cx, cz), 1 << level, (a, b) -> a | b);
				}
				found++;
			}
		}
		if (found > 0) this.pump();
	}

	private void pump() {
		while (!this.closed) {
			long key;
			int levels;
			synchronized (this) {
				if (this.inFlight >= IN_FLIGHT || this.wanted.isEmpty()) return;
				Iterator<Map.Entry<Long, Integer>> it = this.wanted.entrySet().iterator();
				Map.Entry<Long, Integer> e = it.next();
				it.remove();
				key = e.getKey();
				levels = e.getValue();
				this.inFlight++;
			}
			ChunkPos pos = ChunkPos.unpack(key);
			this.level.getChunkSource().chunkMap.read(pos).whenComplete((tag, error) ->
				this.field.work(PRIORITY, () -> {
					try {
						if (tag != null) this.load(pos, tag, levels);
					} finally {
						synchronized (this) {
							this.inFlight--;
						}
						this.pump();
					}
				}));
		}
	}

	private void load(ChunkPos pos, Optional<CompoundTag> saved, int levels) {
		CompoundTag tag = saved.orElse(null);
		if (tag == null || this.closed) return;
		long start = System.nanoTime();
		LodChunks.Summary sum;
		try {
			if (tag.getIntOr("DataVersion", -1) != this.dataVersion) {
				this.skip(pos, "saved by version " + tag.getIntOr("DataVersion", -1));
				return;
			}
			ChunkStatus saved0 = ChunkStatus.byName(tag.getStringOr("Status", "empty"));
			if (!saved0.isOrAfter(ChunkStatus.FEATURES)) {
				this.skip(pos, "only generated to " + saved0);
				return;
			}
			SerializableChunkData data = SerializableChunkData.parse(this.level, this.level.palettedContainerFactory(), tag);
			if (!data.chunkStatus().isOrAfter(ChunkStatus.FEATURES)) {
				this.skip(pos, "only generated to " + data.chunkStatus());
				return;
			}
			LevelChunkSection[] sections = new LevelChunkSection[this.level.getSectionsCount()];
			DataLayer[] sky = new DataLayer[sections.length], block = new DataLayer[sections.length];
			for (SerializableChunkData.SectionData s : data.sectionData()) {
				int i = this.level.getSectionIndexFromSectionY(s.y());
				if (i < 0 || i >= sections.length) continue;
				if (s.chunkSection() != null) sections[i] = s.chunkSection();
				sky[i] = s.skyLight();
				block[i] = s.blockLight();
			}
			ProtoChunk chunk = new ProtoChunk(pos, UpgradeData.EMPTY, sections, new ProtoChunkTicks<>(), new ProtoChunkTicks<>(), this.level,
				this.level.palettedContainerFactory(), null);
			chunk.setPersistedStatus(data.chunkStatus());
			Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.WORLD_SURFACE));
			sum = LodChunks.summarize(LodChunks.snapshot(chunk, sky, block));
		} catch (RuntimeException e) {
			this.skip(pos, e.toString());
			return;
		}
		this.read.incrementAndGet();
		this.nanos.addAndGet(System.nanoTime() - start);
		this.field.post(() -> {
			ClientLevel client = Minecraft.getInstance().level;
			if (this.closed || client != null && client.getChunkSource().hasChunk(pos.x(), pos.z())) return;
			for (int l = 0; l < this.clip.levels; l++) if ((levels >> l & 1) != 0) this.field.applyChunk(sum, l, l + 1);
			this.applied.incrementAndGet();
		});
	}

	private void skip(ChunkPos pos, String why) {
		if (this.skipped.incrementAndGet() <= 3) System.out.println("mcopt-lod: saved chunk " + pos + " left as predicted: " + why);
	}

	private long[] region(int rx, int rz) {
		long now = System.nanoTime();
		return this.saved.compute(ChunkPos.pack(rx, rz), (k, old) -> {
			if (old != null && now - old.readAt() < HEADER_NANOS) return old;
			long[] bits = new long[16];
			try (FileChannel f = FileChannel.open(this.regions.resolve("r." + rx + "." + rz + ".mca"), StandardOpenOption.READ)) {
				ByteBuffer b = ByteBuffer.allocate(4096);
				while (b.hasRemaining() && f.read(b) > 0) {
				}
				b.flip();
				for (int i = 0; i < 1024 && b.remaining() >= 4; i++) if (b.getInt() != 0) bits[i >> 6] |= 1L << (i & 63);
			} catch (IOException e) {
			}
			return new Header(bits, now);
		}).bits();
	}
}
