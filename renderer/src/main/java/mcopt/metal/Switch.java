// Orchard's own file (see NOTICE): not part of mcopt.
package mcopt.metal;

import java.util.function.Predicate;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Whether the renderer runs at all, now that it is part of Orchard's jar rather than a mod of its own the launcher could
 * leave out. Off, every mixin config plugin applies nothing and Profile writes no config, so the game is exactly
 * Mojang's: when the launcher passes {@code -Dorchard.renderer=mojang} (the instance is set to Mojang's renderer),
 * when Sodium or Iris is loaded (mcopt's {@code breaks: sodium}, which Orchard's jar can't declare), or on a computer
 * the native library can't run on (PlatformCheck), where mcopt stopped the game instead.
 */
public final class Switch {
	/** mcopt's own version at the commit Orchard took. */
	public static final String VERSION = "0.3.0-alpha.2";
	/** Why the renderer is off, as a sentence, or null when it is on. */
	public static final String WHY_OFF = whyOff(System.getProperty("orchard.renderer"), loaded(), PlatformCheck.problem());
	public static final boolean ON = WHY_OFF == null;

	private Switch() {
	}

	static String whyOff(String renderer, Predicate<String> loaded, String platformProblem) {
		if ("mojang".equals(renderer)) return "This instance is set to Mojang's renderer, in the launcher's More menu.";
		if (loaded.test("iris")) return "Iris is on, and Metal can't run beside it.";
		if (loaded.test("sodium")) return "Sodium is on, and Metal can't run beside it.";
		return platformProblem;
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
