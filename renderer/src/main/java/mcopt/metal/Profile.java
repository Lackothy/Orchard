// Modified for Russet: far terrain takes its small-GPU mode by itself under 8 GPU cores; nothing is applied or written when Switch has the renderer off, and beside Sodium only mcopt.metal is read. Metal Shaders stay off until asked for.
package mcopt.metal;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

public final class Profile {
	private static boolean applied;
	static final java.util.List<String> SMALL_OUT = java.util.List.of("mcopt.chunk.clones", "mcopt.chunk.clonesCleanup");
	static final String DEFAULT = "indie";

	private Profile() {
	}

	public static synchronized void apply() {
		if (applied) return;
		applied = true;
		if (!Switch.ON) {
			if (Switch.BACKEND) backendOnly();
			return;
		}
		applyFlags();
		if (Boolean.parseBoolean(System.getProperty("mcopt.shade"))) distantHorizons();
		else distantHorizonsOnOpenGl();
		smallGpuFarTerrain();
	}

	private static void backendOnly() {
		if (System.getProperty("mcopt.metal") == null) {
			Path cfg = gameDir().resolve("config").resolve("mcopt.properties");
			if (Files.isRegularFile(cfg)) {
				Properties file = new Properties();
				try (Reader r = Files.newBufferedReader(cfg, StandardCharsets.UTF_8)) {
					file.load(r);
				} catch (IOException e) {
					System.out.println("[mcopt] profile: can't read " + cfg + ": " + e);
				}
				String metal = file.getProperty("mcopt.metal");
				if (metal != null) System.setProperty("mcopt.metal", metal.trim());
			}
		}
		if (System.getProperty("mcopt.metal") == null && DhCompat.loaded()) {
			System.setProperty("mcopt.metal", "false");
			System.out.println("[mcopt] Distant Horizons detected beside Sodium, using Minecraft's own graphics API (-Dmcopt.metal=true overrides)");
		}
	}

	private static void smallGpuFarTerrain() {
		if (System.getProperty("mcopt.lod.small") == null && gpuCores() < 8) {
			System.setProperty("mcopt.lod.small", "true");
		}
	}

	private static void distantHorizons() {
		if (!DhCompat.loaded()) return;
		String version = DhCompat.version();
		if (Boolean.getBoolean("mcopt.lod") || System.getProperty("mcopt.lod.radius") != null) {
			System.out.println("[mcopt] Distant Horizons " + version + " detected: mcopt.lod (far terrain) turned off, DH draws the far terrain");
		}
		System.setProperty("mcopt.lod", "false");
		System.clearProperty("mcopt.lod.radius");
		String gl = null;
		if (DhCompat.irisLoaded()) gl = "Iris is loaded (DH then uses its OpenGL engine)";
		else if ("OPEN_GL".equals(DhCompat.renderingEngine(gameDir()))) gl = "its renderingEngine is OPEN_GL in config/DistantHorizons.toml";
		else if (!DhCompat.versionSupported()) gl = "only DH " + DhCompat.range() + " is tested on Metal (-Dmcopt.dh.anyVersion=true tries it anyway)";
		String explicit = System.getProperty("mcopt.metal");
		if (explicit != null) {
			System.out.println("[mcopt] Distant Horizons " + version + " detected: mcopt.metal=" + explicit + " (set explicitly)"
				+ (gl != null && Boolean.parseBoolean(explicit) ? "; WARNING: DH will likely crash on Metal here: " + gl : ""));
			if (gl == null && Boolean.parseBoolean(explicit)) vanillaFade();
			return;
		}
		if (gl != null) {
			System.setProperty("mcopt.metal", "false");
			System.out.println("[mcopt] Distant Horizons " + version + " detected, using OpenGL: " + gl + "; mcopt's other optimizations stay on");
		} else {
			System.out.println("[mcopt] Distant Horizons " + version + " detected: staying on Metal (DH's Blaze3D renderer runs on mcopt's backend; -Dmcopt.metal=false for OpenGL)");
			vanillaFade();
		}
	}

	private static void vanillaFade() {
		if (!Boolean.parseBoolean(System.getProperty("mcopt.shade")) || "false".equals(System.getProperty("mcopt.shade.dh.fixFade"))
			|| System.getProperty("mcopt.shade.dh.fade") != null) return;
		String done = DhCompat.fixVanillaFade(gameDir());
		if (done != null) System.out.println("[mcopt] Distant Horizons: " + done);
	}

	private static void distantHorizonsOnOpenGl() {
		if (System.getProperty("mcopt.metal") != null) return;
		String why;
		try {
			net.fabricmc.loader.api.FabricLoader loader = net.fabricmc.loader.api.FabricLoader.getInstance();
			if (!loader.isModLoaded("distanthorizons")) return;
			why = loader.isModLoaded("iris") ? "with Iris" : dhOpenGlEngine() ? "set to its OpenGL renderer" : null;
		} catch (Throwable t) {
			return;
		}
		if (why == null) return;
		System.setProperty("mcopt.metal", "false");
		System.out.println("[mcopt] mcopt: Distant Horizons " + why + " needs OpenGL, using OpenGL; mcopt's other optimizations stay on (-Dmcopt.metal=true overrides)");
	}

	private static boolean dhOpenGlEngine() {
		Path toml = gameDir().resolve("config").resolve("DistantHorizons.toml");
		if (!Files.isRegularFile(toml)) return false;
		try {
			for (String line : Files.readAllLines(toml, StandardCharsets.UTF_8)) {
				String t = line.strip();
				if (t.startsWith("renderingEngine")) return t.replace(" ", "").startsWith("renderingEngine=\"OPEN_GL\"");
			}
		} catch (IOException | RuntimeException e) {
			System.out.println("[mcopt] profile: can't read " + toml + ": " + e);
		}
		return false;
	}

	private static void applyFlags() {
		Properties file = new Properties();
		Path cfg = gameDir().resolve("config").resolve("mcopt.properties");
		if (Files.isRegularFile(cfg)) {
			try (Reader r = Files.newBufferedReader(cfg, StandardCharsets.UTF_8)) {
				file.load(r);
			} catch (IOException e) {
				System.out.println("[mcopt] profile: can't read " + cfg + ": " + e);
			}
		}
		String name = System.getProperty("mcopt.profile", file.getProperty("profile", "")).trim();
		if (name.isEmpty()) name = DEFAULT;
		if (name.equals("alpha") && System.getProperty("mcopt.profile") == null
			&& Boolean.parseBoolean(System.getProperty("mcopt.shade", file.getProperty("mcopt.shade", "false")).trim())) {
			name = "indie";
			alphaToIndie(cfg);
		}
		if (!Files.exists(cfg)) {
			writeDefault(cfg, name);
			if (Files.isRegularFile(cfg)) {
				try (Reader r = Files.newBufferedReader(cfg, StandardCharsets.UTF_8)) {
					file.load(r);
				} catch (IOException e) {
					System.out.println("[mcopt] profile: can't read " + cfg + ": " + e);
				}
			}
		}
		Map<String, String> flags = new TreeMap<>();
		if (!name.isEmpty() && !name.equals("none")) {
			Properties p = new Properties();
			try (InputStream in = Profile.class.getResourceAsStream("/mcopt/profiles/" + name + ".properties")) {
				if (in == null) {
					System.out.println("[mcopt] profile: no profile named '" + name + "' (ignored)");
				} else {
					p.load(in);
				}
			} catch (IOException e) {
				System.out.println("[mcopt] profile " + name + ": " + e);
			}
			p.stringPropertyNames().forEach(k -> flags.put(k, p.getProperty(k).trim()));
			if (name.equals("alpha")) tier(flags);
		}
		file.stringPropertyNames().stream().filter(k -> k.startsWith("mcopt.")).forEach(k -> flags.put(k, file.getProperty(k).trim()));
		if (flags.isEmpty()) return;
		StringBuilder set = new StringBuilder(), kept = new StringBuilder();
		flags.forEach((k, v) -> {
			if (System.getProperty(k) == null) {
				System.setProperty(k, v);
				set.append(' ').append(k).append('=').append(v);
			} else {
				kept.append(' ').append(k).append('=').append(System.getProperty(k));
			}
		});
		System.out.println("[mcopt] profile " + (name.isEmpty() ? "(config only)" : name) + ": set" + set
			+ (kept.isEmpty() ? "" : "; kept from the command line:" + kept));
	}

	private static void tier(Map<String, String> flags) {
		int cores = gpuCores();
		long mem = memBytes();
		boolean small = cores < 10 || mem <= 8L << 30;
		StringBuilder out = new StringBuilder();
		if (small) for (String k : SMALL_OUT) if (flags.remove(k) != null) out.append(' ').append(k);
		System.out.println("[mcopt] profile alpha: tier " + (small ? "small" : "full") + " (gpu cores "
			+ (cores < 0 ? "unknown" : cores) + ", ram " + (mem < 0 ? "unknown" : String.format("%.1f GB", mem / (double) (1L << 30))) + ")"
			+ (out.isEmpty() ? "" : "; left out:" + out));
	}

	private static int gpuCores = -2;

	public static synchronized int gpuCores() {
		if (gpuCores == -2) {
			String s = run("/usr/sbin/ioreg", "-rd1", "-c", "AGXAccelerator");
			java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"gpu-core-count\"\\s*=\\s*(\\d+)").matcher(s);
			gpuCores = m.find() ? Integer.parseInt(m.group(1)) : -1;
		}
		return gpuCores;
	}

	private static long memBytes() {
		try {
			return Long.parseLong(run("/usr/sbin/sysctl", "-n", "hw.memsize").trim());
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	private static String run(String... cmd) {
		try {
			Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null"))).start();
			byte[] b = p.getInputStream().readAllBytes();
			p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
			return new String(b, StandardCharsets.UTF_8);
		} catch (Exception e) {
			return "";
		}
	}

	private static void alphaToIndie(Path cfg) {
		try {
			java.util.List<String> lines = Files.readAllLines(cfg, StandardCharsets.UTF_8);
			boolean changed = false;
			for (int i = 0; i < lines.size(); i++) {
				if (lines.get(i).replace(" ", "").equals("profile=alpha")) {
					lines.set(i, "profile=indie");
					changed = true;
				}
			}
			if (!changed) return;
			Path tmp = cfg.resolveSibling(cfg.getFileName() + ".tmp");
			Files.write(tmp, lines, StandardCharsets.UTF_8);
			Files.move(tmp, cfg, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
			System.out.println("[mcopt] profile: " + cfg + " said profile=alpha (written by an older version); now profile=indie, "
				+ "the terrain renderer the shaders light");
		} catch (IOException | RuntimeException e) {
			System.out.println("[mcopt] profile: using indie instead of alpha; can't update " + cfg + ": " + e);
		}
	}

	private static void writeDefault(Path cfg, String name) {
		String text = """
			# mcopt settings. Written on first launch; edit freely.
			#
			# The indie profile: mcopt's own terrain renderer on Apple Metal (no Sodium), plus measured startup and
			# thread-priority options. To turn all of it off (vanilla's renderer), change the next line to:  profile=none
			profile=%s
			#
			# Single switches win over the profile, e.g. our renderer off but the rest on:
			#mcopt.own=false
			#
			# Metal Shaders (mcopt-shade): lighting, shadows, sky, clouds, water, god rays. Off unless asked for (Russet's Video Settings: Metal renderer > Shaders).
			#mcopt.shade=true
			# Quality preset: performance, balanced, quality or ultra.
			#mcopt.shade.preset=balanced
			# Art style: epic, storybook or natural.
			#mcopt.shade.look=epic
			#
			# Far terrain (EXPERIMENTAL, off by default; for Macs with 10 or more GPU cores): remove the # below.
			#mcopt.lod=true
			""".formatted(name);
		try {
			Files.createDirectories(cfg.getParent());
			Files.writeString(cfg, text, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
			System.out.println("[mcopt] profile: wrote " + cfg + " (profile=" + name + ")");
		} catch (IOException e) {
			System.out.println("[mcopt] profile: can't write " + cfg + ": " + e);
		}
	}

	private static Path gameDir() {
		try {
			return net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir();
		} catch (Throwable t) {
			return Path.of("").toAbsolutePath();
		}
	}
}
