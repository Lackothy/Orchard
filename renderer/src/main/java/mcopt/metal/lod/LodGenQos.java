package mcopt.metal.lod;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.invoke.MethodHandle;
import java.util.Locale;

import static java.lang.foreign.ValueLayout.JAVA_INT;

public final class LodGenQos {
	private LodGenQos() {
	}

	static final int LOD = parse(System.getProperty("mcopt.lod.qos"));
	public static final int WORLDGEN = parse(System.getProperty("mcopt.gen.worldgenQos"));
	private static MethodHandle setSelf;
	private static final ThreadLocal<Boolean> DONE = ThreadLocal.withInitial(() -> Boolean.FALSE);

	static int parse(String name) {
		if (name == null) return 0;
		return switch (name.toLowerCase(Locale.ROOT)) {
			case "background" -> 0x09;
			case "utility" -> 0x11;
			case "default" -> 0x15;
			case "initiated", "user-initiated" -> 0x19;
			case "interactive", "user-interactive" -> 0x21;
			default -> 0;
		};
	}

	public static void once(int cls, String role) {
		if (cls == 0 || DONE.get()) return;
		DONE.set(Boolean.TRUE);
		try {
			synchronized (LodGenQos.class) {
				if (setSelf == null) {
					Linker linker = Linker.nativeLinker();
					setSelf = linker.downcallHandle(linker.defaultLookup().find("pthread_set_qos_class_self_np").orElseThrow(),
						FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
				}
			}
			int rc = (int) setSelf.invokeExact(cls, 0);
			System.out.println("mcopt-lod: QoS 0x" + Integer.toHexString(cls) + " for " + role + " (" + Thread.currentThread().getName() + "): " + rc);
		} catch (Throwable t) {
			System.out.println("mcopt-lod: QoS for " + role + ": " + t);
		}
	}
}
