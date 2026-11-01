package mcopt.metal;

public final class TexelViewsSwitch {
	private TexelViewsSwitch() {
	}

	public static void set(boolean on) {
		TexelViews.active = on;
	}

	public static void pass(boolean on) {
		Native.cpuFlags(mcopt.metal.cpu.Cpu.nativeFlags(on));
	}
}
