package mcopt.metal.own;

import java.util.HashMap;
import java.util.Map;

public final class OwnProbe {
	static final boolean ON = Long.getLong("mcopt.own.probe", 0) > 0;
	private static volatile Map<String, String> overrides = Map.of();

	private OwnProbe() {
	}

	static boolean select(String m) {
		if (m.equals("base")) {
			overrides = Map.of();
			return true;
		}
		if (!m.contains("=")) {
			overrides = Map.of();
			return false;
		}
		Map<String, String> o = new HashMap<>();
		for (String kv : m.split("\\+")) {
			int i = kv.indexOf('=');
			if (i > 0) o.put(kv.substring(0, i).trim(), kv.substring(i + 1).trim());
		}
		overrides = o;
		return true;
	}

	public static void configureWarm(String modes, long milliseconds) {
		if (!Boolean.getBoolean("mcopt.bench.warm") || !ON)
			throw new IllegalStateException("warm requires mcopt.bench.warm=true and mcopt.own.probe>0");
		String[] names = modes.split("/", -1);
		if (names.length < 1 || names.length > 8 || milliseconds < 100 || milliseconds > 5000)
			throw new IllegalArgumentException("1..8 modes, 100..5000 ms required");
		for (String name : names) {
			if (name.equals("base")) continue;
			for (String pair : name.split("\\+", -1)) {
				String[] kv = pair.split("=", -1);
				if (kv.length != 2) throw new IllegalArgumentException("expected key=value: " + pair);
				switch (kv[0]) {
					case "frag.splitNear" -> { if (OwnFrag.SPLIT_NEAR <= 0) throw new IllegalArgumentException("splitNear needs positive startup split/banks"); int n = Integer.parseInt(kv[1]); if (n < 1 || n > 4096) throw new IllegalArgumentException("splitNear 1..4096"); }
					case "frag.pyrSkip" -> { int n = Integer.parseInt(kv[1]); if (n < 0 || n > 2) throw new IllegalArgumentException("pyrSkip 0..2"); }
					case "frag.fine" -> { if (!java.util.Set.of("1", "2", "4").contains(kv[1])) throw new IllegalArgumentException("fine 1/2/4"); }
					case "frag.testLate", "frag.msub", "frag.testFast", "frag.testGather", "hizTop", "hizGather", "frag.uocc", "frag.fogVert" -> {
						if (!kv[1].equals("true") && !kv[1].equals("false")) throw new IllegalArgumentException("boolean required");
					}
					default -> throw new IllegalArgumentException("not a warm-safe probe key: " + kv[0]);
				}
			}
		}
		OwnTerrain.configureWarm(names, milliseconds);
	}

	public static boolean bool(String key, boolean dflt) {
		if (!ON) return dflt;
		String v = overrides.get(key);
		return v == null ? dflt : Boolean.parseBoolean(v);
	}

	static String string(String key, String dflt) {
		if (!ON) return dflt;
		String v = overrides.get(key);
		return v == null ? dflt : v;
	}

	static int integer(String key, int dflt) {
		if (!ON) return dflt;
		String v = overrides.get(key);
		return v == null ? dflt : Integer.parseInt(v);
	}
}
