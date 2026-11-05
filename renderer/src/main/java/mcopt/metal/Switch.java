// Orchard's own file (see NOTICE): not part of mcopt.
package mcopt.metal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Predicate;
import net.fabricmc.loader.api.FabricLoader;

/**
 * How much of the renderer runs, now that it is part of Orchard Metal's jar rather than a mod of its own.
 * <ul>
 * <li>All of it ({@link #ON}) by itself.</li>
 * <li>Only the Metal backend ({@link #BACKEND} without ON) beside Sodium, which then draws the terrain through the
 * backend as it would through OpenGL's: mcopt's {@code breaks: sodium}, which this jar doesn't declare, now keeps
 * out only the terrain renderer, far terrain and shaders. Iris needs Sodium, and stays asleep when options.txt prefers
 * Vulkan (it then applies only its key for the shader pack screen), so it is let through only then.</li>
 * <li>None of it, with every mixin config plugin applying nothing and Profile writing no config, so the game is exactly
 * Mojang's: when the game is started with {@code -Dorchard.renderer=mojang} (the game tests do, for Mojang's), when
 * Iris is awake, or on a computer the native library can't run on (PlatformCheck), where mcopt stopped the game
 * instead.</li>
 * </ul>
 */
public final class Switch {
	/** mcopt's own version at the commit Orchard took. */
	public static final String VERSION = "0.3.0-alpha.2";
	/** Why the renderer is off, as a sentence, or null when it is on. */
	public static final String WHY_OFF = whyOff(System.getProperty("orchard.renderer"), loaded(), vulkanPreferred(options()), PlatformCheck.problem());
	/** Why only the backend runs, as a sentence, or null when all of the renderer runs or none of it. */
	public static final String WHY_BACKEND_ONLY = WHY_OFF == null ? backendOnly(loaded()) : null;
	/** The Metal backend runs. */
	public static final boolean BACKEND = WHY_OFF == null;
	/** All of the renderer runs: the backend, its own terrain, far terrain and shaders. */
	public static final boolean ON = BACKEND && WHY_BACKEND_ONLY == null;

	private Switch() {
	}

	static String whyOff(String renderer, Predicate<String> loaded, boolean vulkanPreferred, String platformProblem) {
		if ("mojang".equals(renderer)) return "The game was started with -Dorchard.renderer=mojang, which asks for Minecraft's own renderer.";
		if (loaded.test("iris") && !vulkanPreferred) return "Iris is on, and Metal can't run beside it. Set Graphics API to Vulkan and restart: Iris then steps aside, and Metal draws with Sodium.";
		return platformProblem;
	}

	static String backendOnly(Predicate<String> loaded) {
		if (loaded.test("sodium") || loaded.test("iris")) return "Sodium is on, so it draws the terrain, on Metal. The renderer's own terrain, far terrain and shaders stay off.";
		return null;
	}

	/** Whether options.txt asks for Vulkan, read the way Iris reads it before choosing its mixins. */
	static boolean vulkanPreferred(Path options) {
		if (options == null || !Files.isRegularFile(options)) return false;
		try {
			for (String line : Files.readAllLines(options, StandardCharsets.UTF_8)) {
				if (line.startsWith("preferredGraphicsBackend:")) return line.toLowerCase(Locale.ROOT).contains("vulkan");
			}
		} catch (IOException | RuntimeException e) {
			System.out.println("[Orchard] Can't read " + options + ": " + e);
		}
		return false;
	}

	private static Path options() {
		try {
			return FabricLoader.getInstance().getGameDir().resolve("options.txt");
		} catch (Throwable t) {
			return null;
		}
	}

	private static Predicate<String> loaded() {
		try {
			FabricLoader loader = FabricLoader.getInstance();
			return loader::isModLoaded;
		} catch (Throwable t) {
			return mod -> false;
		}
	}
}
