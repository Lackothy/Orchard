package mcopt.metal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DhCompat {
	public static final String MOD_ID = "distanthorizons";
	public static final int[] MIN = {3, 3, 3}, MAX = {3, 3, 4};

	private DhCompat() {
	}

	public static boolean loaded() {
		try {
			return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded(MOD_ID);
		} catch (Throwable t) {
			return false;
		}
	}

	public static boolean irisLoaded() {
		try {
			return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("iris");
		} catch (Throwable t) {
			return false;
		}
	}

	public static String version() {
		try {
			return net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(MOD_ID)
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("");
		} catch (Throwable t) {
			return "";
		}
	}

	public static boolean versionSupported() {
		return Boolean.getBoolean("mcopt.dh.anyVersion") || inRange(version());
	}

	static boolean inRange(String v) {
		Matcher m = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)").matcher(v == null ? "" : v.trim());
		if (!m.find()) return false;
		int[] x = {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
		return compare(x, MIN) >= 0 && compare(x, MAX) <= 0;
	}

	private static int compare(int[] a, int[] b) {
		for (int i = 0; i < 3; i++) if (a[i] != b[i]) return Integer.compare(a[i], b[i]);
		return 0;
	}

	public static String range() {
		return MIN[0] + "." + MIN[1] + "." + MIN[2] + "-" + MAX[0] + "." + MAX[1] + "." + MAX[2];
	}

	public static String renderingEngine(Path gameDir) {
		Path toml = gameDir.resolve("config").resolve("DistantHorizons.toml");
		if (!Files.isRegularFile(toml)) return "";
		try {
			Matcher m = Pattern.compile("(?m)^\\s*renderingEngine\\s*=\\s*\"?([A-Za-z0-9_]+)\"?").matcher(Files.readString(toml, StandardCharsets.UTF_8));
			return m.find() ? m.group(1).toUpperCase(java.util.Locale.ROOT) : "";
		} catch (IOException | RuntimeException e) {
			return "";
		}
	}

	public static String fixVanillaFade(Path gameDir) {
		Path toml = gameDir.resolve("config").resolve("DistantHorizons.toml");
		try {
			if (!Files.exists(toml)) {
				Files.createDirectories(toml.getParent());
				Files.writeString(toml, "_version = 5\n\n[client.advanced.graphics.quality]\n\tvanillaFadeMode = \"NONE\"\n", StandardCharsets.UTF_8,
					java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
				return "wrote " + toml + " with vanillaFadeMode = \"NONE\" (DH's default DOUBLE_PASS hides the near world under the shaders)";
			}
			String text = Files.readString(toml, StandardCharsets.UTF_8);
			Matcher version = Pattern.compile("(?m)^\\s*_version\\s*=\\s*(\\d+)").matcher(text);
			if (version.find() && !version.group(1).equals("5")) return null;
			Matcher key = Pattern.compile("(?m)^(\\s*vanillaFadeMode\\s*=\\s*)\"?([A-Za-z_]+)\"?").matcher(text);
			String fixed;
			boolean present = key.find();
			if (present) {
				if (!key.group(2).equalsIgnoreCase("DOUBLE_PASS")) return null;
				fixed = text.substring(0, key.start()) + key.group(1) + "\"NONE\"" + text.substring(key.end());
			} else {
				Matcher table = Pattern.compile("(?m)^\\s*\\[client\\.advanced\\.graphics\\.quality\\][^\\n]*\\n").matcher(text);
				fixed = table.find() ? text.substring(0, table.end()) + "\tvanillaFadeMode = \"NONE\"\n" + text.substring(table.end())
					: text + (text.endsWith("\n") || text.isEmpty() ? "" : "\n") + "\n[client.advanced.graphics.quality]\n\tvanillaFadeMode = \"NONE\"\n";
			}
			Files.writeString(toml, fixed, StandardCharsets.UTF_8);
			return "set vanillaFadeMode = \"NONE\" in " + toml + " (it was " + (present ? "" : "DH's default ") + "DOUBLE_PASS, which hides the near world under the shaders)";
		} catch (IOException | RuntimeException e) {
			return "can't check vanillaFadeMode in " + toml + ": " + e;
		}
	}
}
