package mcopt.metal.own;

final class OwnArenaSizing {
	static final int LOGICAL_INITIAL_UNITS = 1 << 20;

	private OwnArenaSizing() {
	}

	static int reserveUnits(boolean enabled, int renderDistance, boolean compact) {
		return enabled && compact && renderDistance == 16 ? 4 << 20 : LOGICAL_INITIAL_UNITS;
	}

	static boolean preSizeEnabled(boolean requested, int gpuCores, int maxGpuCores) {
		return requested && gpuCores >= 0 && maxGpuCores > 0 && gpuCores < maxGpuCores;
	}
}
