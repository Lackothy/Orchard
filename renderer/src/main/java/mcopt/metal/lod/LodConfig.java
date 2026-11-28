// Modified for Russet: far terrain, its reach and its trees can be changed while the game runs (set), where LIVE; GRAIN, FAR_SHADE, QUALITY_DISTANCE, WATER_UNDER and GLOW.
package mcopt.metal.lod;

public final class LodConfig {
	public static final boolean LIVE = mcopt.metal.Switch.ON && !mcopt.metal.shade.Shade.ENABLED && !mcopt.metal.DhCompat.loaded();
	public static volatile boolean ENABLED = Boolean.getBoolean("mcopt.lod") || System.getProperty("mcopt.lod.radius") != null;
	public static final boolean SMALL = Boolean.getBoolean("mcopt.lod.small");
	public static volatile int RADIUS_CHUNKS = Integer.getInteger("mcopt.lod.radius", 512);
	public static final int N = Integer.getInteger("mcopt.lod.n", SMALL ? 512 : 2048);
	public static final int THREADS = Integer.getInteger("mcopt.lod.threads", Math.max(2, Runtime.getRuntime().availableProcessors() - 4));
	public static final int CROWN_LEVELS = Integer.getInteger("mcopt.lod.crownLevels", SMALL ? 2 : 1);
	public static final boolean SEGMENTED = "segmented".equals(System.getProperty("mcopt.lod.walk", "serial"));
	public static final boolean ROWS = "rows".equals(System.getProperty("mcopt.lod.walk", "serial"));
	public static final int ROW_SEGS = Math.max(1, Integer.getInteger("mcopt.lod.rowSegs", 48));
	public static final boolean NEAR_DETAIL = Boolean.parseBoolean(System.getProperty("mcopt.lod.nearDetail", "true"));
	public static final boolean TEXTURES = Boolean.parseBoolean(System.getProperty("mcopt.lod.textures", "true"));
	public static final boolean PLANTS = TEXTURES && Boolean.parseBoolean(System.getProperty("mcopt.lod.plants", "true"));
	public static final int TREE_LEVELS = Integer.getInteger("mcopt.lod.treeLevels", 1);
	public static volatile float GRAIN = Float.parseFloat(System.getProperty("mcopt.lod.grain", "0.15"));
	public static volatile float FAR_SHADE = Float.parseFloat(System.getProperty("mcopt.lod.farShade", "0.6"));
	public static volatile boolean WATER_UNDER = Boolean.parseBoolean(System.getProperty("mcopt.lod.waterUnder", "true"));
	public static volatile boolean GLOW = Boolean.getBoolean("mcopt.lod.glow");
	public static volatile int QUALITY_DISTANCE = Integer.getInteger("mcopt.lod.qualityDistance", 512);
	public static final double CROWN_SHADE = Double.parseDouble(System.getProperty("mcopt.lod.crownShade", "0.7"));
	public static final boolean FINE_DENSITY = Boolean.parseBoolean(System.getProperty("mcopt.lod.fineDensity", "true"));
	public static final int FINE_LEVELS = Integer.getInteger("mcopt.lod.fineLevels", 2);
	public static final boolean VERIFY = Boolean.getBoolean("mcopt.lod.verify");
	public static final boolean DISK_CACHE = Boolean.parseBoolean(System.getProperty("mcopt.lod.cache", "true"));
	public static final String CACHE_DIR = System.getProperty("mcopt.lod.cacheDir");
	public static volatile boolean TREES = Boolean.parseBoolean(System.getProperty("mcopt.lod.trees", "true"));
	public static final double FOG_START = Double.parseDouble(System.getProperty("mcopt.lod.fogStart", "-1"));
	public static final double HAZE = Double.parseDouble(System.getProperty("mcopt.lod.haze", "1.0"));
	public static final boolean MESH = "mesh".equals(System.getProperty("mcopt.lod.render", "walk"));
	public static final boolean STATS = Boolean.getBoolean("mcopt.lod.stats");
	public static final boolean DRAW = Boolean.parseBoolean(System.getProperty("mcopt.lod.draw", "true"));
	public static final String DUMP = System.getProperty("mcopt.lod.dump");
	public static final long DUMP_FRAME = Long.getLong("mcopt.lod.dumpFrame", 600);
	public static final boolean CHUNKS = Boolean.parseBoolean(System.getProperty("mcopt.lod.chunks", "true"));

	public static final int PROBE_MS = Integer.getInteger("mcopt.lod.probe", 0);
	static volatile int generation;

	private LodConfig() {
	}

	public static synchronized void set(boolean on, int radiusChunks, boolean trees) {
		if (!LIVE || on == ENABLED && radiusChunks == RADIUS_CHUNKS && trees == TREES) return;
		ENABLED = on;
		RADIUS_CHUNKS = radiusChunks;
		TREES = trees;
		generation++;
	}

	public static void setQuality(int distance, boolean glow) {
		QUALITY_DISTANCE = distance;
		GLOW = glow;
	}

	public static double reachBlocks() {
		return RADIUS_CHUNKS * 16.0;
	}
}
