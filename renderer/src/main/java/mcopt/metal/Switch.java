package mcopt.metal;

import java.util.function.Predicate;
import net.fabricmc.loader.api.FabricLoader;

public final class Switch {
	public static final String VERSION = "0.3.0-alpha.2";
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
