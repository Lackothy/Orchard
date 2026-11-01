package mcopt.metal;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.invoke.MethodHandle;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static java.lang.foreign.ValueLayout.JAVA_INT;

public final class Qos {
	private static final Map<String, Integer> LEVELS = new HashMap<>();
	private static MethodHandle setSelf;

	static final String DEFAULT_MODE = "on";

	static {
		if (!"off".equals(System.getProperty("mcopt.qos", DEFAULT_MODE))) {
			LEVELS.put("render", 0x21);
			LEVELS.put("server", 0x19);
		}
		for (String role : new String[] {"render", "server", "mesh", "cull", "worker", "io"}) {
			String v = System.getProperty("mcopt.qos." + role);
			if ("none".equals(v)) LEVELS.remove(role);
			int cls = parse(v);
			if (cls != 0) LEVELS.put(role, cls);
		}
	}

	private Qos() {
	}

	private static int parse(String name) {
		if (name == null) return 0;
		return switch (name.toLowerCase(Locale.ROOT)) {
			case "background" -> 0x09;
			case "utility" -> 0x11;
			case "default" -> 0x15;
			case "initiated" -> 0x19;
			case "interactive" -> 0x21;
			default -> 0;
		};
	}

	public static boolean configured() {
		return !LEVELS.isEmpty();
	}

	public static boolean has(String role) {
		return LEVELS.containsKey(role);
	}

	public static void self(String role) {
		Integer cls = LEVELS.get(role);
		if (cls == null) return;
		try {
			synchronized (Qos.class) {
				if (setSelf == null) {
					Linker linker = Linker.nativeLinker();
					setSelf = linker.downcallHandle(linker.defaultLookup().find("pthread_set_qos_class_self_np").orElseThrow(),
						FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
				}
			}
			int rc = (int) setSelf.invokeExact((int) cls, 0);
			if (rc != 0 || Boolean.getBoolean("mcopt.qos.log")) System.out.println("mcopt: QoS 0x" + Integer.toHexString(cls) + " for " + role + " (" + Thread.currentThread().getName() + "): " + rc);
		} catch (Throwable t) {
			System.err.println("mcopt: QoS for " + role + ": " + t);
		}
	}
}
