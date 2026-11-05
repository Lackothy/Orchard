package mcopt.metal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Predicate;
import net.fabricmc.loader.api.FabricLoader;

public final class Switch {
	public static final String VERSION = "0.3.0-alpha.2";
	public static final String WHY_OFF = whyOff(System.getProperty("russet.renderer"), loaded(), vulkanPreferred(options()), PlatformCheck.problem());
	public static final String WHY_BACKEND_ONLY = WHY_OFF == null ? backendOnly(loaded()) : null;
	public static final boolean BACKEND = WHY_OFF == null;
	public static final boolean ON = BACKEND && WHY_BACKEND_ONLY == null;

	private Switch() {
	}

	static String whyOff(String renderer, Predicate<String> loaded, boolean vulkanPreferred, String platformProblem) {
		if ("mojang".equals(renderer)) return "The game was started with -Drusset.renderer=mojang, which asks for Minecraft's own renderer.";
		if (loaded.test("iris") && !vulkanPreferred) return "Iris is on, and Metal can't run beside it. Set Graphics API to Vulkan and restart: Iris then steps aside, and Metal draws with Sodium.";
		return platformProblem;
	}

	static String backendOnly(Predicate<String> loaded) {
		if (loaded.test("sodium") || loaded.test("iris")) return "Sodium is on, so it draws the terrain, on Metal. The renderer's own terrain, far terrain and shaders stay off.";
		return null;
	}

	static boolean vulkanPreferred(Path options) {
		if (options == null || !Files.isRegularFile(options)) return false;
		try {
			for (String line : Files.readAllLines(options, StandardCharsets.UTF_8)) {
				if (line.startsWith("preferredGraphicsBackend:")) return line.toLowerCase(Locale.ROOT).contains("vulkan");
			}
		} catch (IOException | RuntimeException e) {
			System.out.println("[Russet] Can't read " + options + ": " + e);
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
