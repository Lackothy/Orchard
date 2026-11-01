package mcopt.metal.chunk;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class ChunkOpt {
	private ChunkOpt() { }

	public static final String SAVE_SKIP = mcopt.metal.chunkio.ChunkIo.SAVE_SKIP;
	public static final boolean SAVE_SKIP_ON = mcopt.metal.chunkio.ChunkIo.SAVE_SKIP_ON;
	public static final boolean SAVE_PROBE = mcopt.metal.chunkio.ChunkIo.SAVE_PROBE;
	public static final java.util.Set<String> MESH = meshSet(prop("mesh"));
	public static final int CLONES = Integer.getInteger("mcopt.chunk.clones", 0);
	public static final boolean CLONES_VERIFY = Boolean.getBoolean("mcopt.chunk.clonesVerify");
	public static final String CLONES_CLEANUP = prop("clonesCleanup");
	public static final boolean CLONES_CLEANUP_VERIFY = "verify".equals(CLONES_CLEANUP);
	public static final boolean CLONES_CLEANUP_AB = "ab".equals(CLONES_CLEANUP);
	public static final boolean CLONES_CLEANUP_ON = "true".equals(CLONES_CLEANUP) || CLONES_CLEANUP_VERIFY || CLONES_CLEANUP_AB;
	public static final String PARSE = prop("parse");
	public static final boolean PARSE_VERIFY = "verify".equals(PARSE);
	public static final boolean PARSE_FAST = "true".equals(PARSE) || PARSE_VERIFY;
	public static final boolean SERIALIZE_VERIFY = "verify".equals(prop("serialize"));
	public static final boolean SERIALIZE = "true".equals(prop("serialize")) || SERIALIZE_VERIFY;
	public static final boolean POI_VERIFY = "verify".equals(prop("poi"));
	public static final boolean POI = "true".equals(prop("poi")) || POI_VERIFY;
	public static final boolean TICKS = Boolean.getBoolean("mcopt.chunk.ticks");
	public static final boolean CHUNK_GET = Boolean.getBoolean("mcopt.chunk.chunkGet");
	public static final boolean ALLOC_LIGHT_MAP = Boolean.getBoolean("mcopt.alloc.lightMap");
	private static final boolean LIGHT_REQUESTED = "true".equals(prop("light")) || "verify".equals(prop("light"));
	public static final boolean LIGHT_VERIFY = "verify".equals(prop("light")) && !ALLOC_LIGHT_MAP;
	public static final boolean LIGHT = LIGHT_REQUESTED && !ALLOC_LIGHT_MAP;
	public static final boolean STATS = Boolean.getBoolean("mcopt.chunk.stats") || SAVE_PROBE || PARSE_VERIFY || POI_VERIFY || CLONES_VERIFY || LIGHT_VERIFY || SERIALIZE_VERIFY
		|| CLONES_CLEANUP_VERIFY || CLONES_CLEANUP_AB;

	static {
		if (LIGHT_REQUESTED && ALLOC_LIGHT_MAP) {
			System.out.println("mcopt-chunk: light off: mcopt.alloc.lightMap is on (both replace the light engine's section-map copies; they can't run together)");
		}
	}

	private static java.util.Set<String> meshSet(String v) {
		java.util.Set<String> out = new java.util.TreeSet<>();
		for (String s : v.split(",")) if (!s.isBlank()) out.add(s.trim());
		if (out.remove("all")) out.addAll(java.util.List.of("air", "bounds", "vis", "lambda"));
		return java.util.Collections.unmodifiableSet(out);
	}

	public static boolean mesh(String feature) {
		return MESH.contains(feature);
	}

	private static String prop(String name) {
		String v = System.getProperty("mcopt.chunk." + name);
		return v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
	}

	public static boolean mixinEnabled(String simpleName) {
		return switch (simpleName) {
			case "LevelSliceAirMixin" -> mesh("air");
			case "LevelSliceBoundsMixin" -> mesh("bounds");
			case "DirectionalVisGraphMixin", "BitArrayAccessor" -> mesh("vis");
			case "BlockRendererMixin" -> mesh("lambda");
			case "LevelBiomeSliceMixin" -> mesh("biome");
			case "ClonedChunkSectionCacheMixin" -> CLONES > 0;
			case "ClonedChunkSectionCacheCleanupMixin" -> CLONES_CLEANUP_ON;
			case "PalettedContainerFactoryMixin" -> PARSE_FAST || SERIALIZE;
			case "AcquirePoiMixin", "PoiSectionAccessor", "SectionStorageAccess" -> POI;
			case "ServerLevelTickMixin", "LevelRandAccessor" -> TICKS;
			case "ChunkAccessSectionMixin" -> CHUNK_GET;
			case "DataLayerStorageMapMixin", "BlockLightMapMixin", "SkyLightMapMixin" -> LIGHT;
			default -> false;
		};
	}

	static {
		if (STATS && Boolean.getBoolean("mcopt.chunk.statsLog")) {
			Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println("mcopt-chunk: stats " + snapshot()), "mcopt-chunk stats"));
		}
	}

	public static void count(String key, long n) {
		mcopt.metal.chunkio.ChunkIo.count(key, n);
	}

	public static void count(String key) {
		count(key, 1);
	}

	public static Map<String, Object> snapshot() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("flags", Map.of("saveSkip", SAVE_SKIP, "mesh", String.join(",", MESH), "clones", CLONES, "parse", PARSE, "clonesCleanup", CLONES_CLEANUP,
			"light", LIGHT_VERIFY ? "verify" : LIGHT ? "true" : LIGHT_REQUESTED ? "off (alloc.lightMap)" : ""));
		out.putAll(mcopt.metal.chunkio.ChunkIo.counters());
		return out;
	}
}
