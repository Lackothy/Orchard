package mcopt.metal.lod;

import net.minecraft.world.level.levelgen.densityfunction.DensitySamplerSet;

public final class GenServerNoise {
	private GenServerNoise() {
	}

	public static DensitySamplerSet wrap(DensitySamplerSet set, Object randomState) {
		return LodNativeNoise.wrapServer(set, randomState);
	}
}
