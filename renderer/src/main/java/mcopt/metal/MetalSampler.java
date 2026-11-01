package mcopt.metal;

import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import java.util.OptionalDouble;

record MetalSampler(long handle, AddressMode getAddressModeU, AddressMode getAddressModeV, FilterMode getMinFilter, FilterMode getMagFilter,
	int getMaxAnisotropy, OptionalDouble getMaxLod) implements GpuSampler {

	static MetalSampler create(long ctx, AddressMode u, AddressMode v, FilterMode min, FilterMode mag, int anisotropy, OptionalDouble maxLod) {
		double lod = maxLod.orElse(1000.0);
		int mipFilter = lod > 0.25 ? 2 : 0;
		long handle = Native.samplerNew(ctx, MetalConst.addressMode(u), MetalConst.addressMode(v), MetalConst.filter(min), MetalConst.filter(mag),
			mipFilter, Math.max(1, anisotropy), (float) Math.max(0.25, lod));
		return new MetalSampler(handle, u, v, min, mag, anisotropy, maxLod);
	}

	@Override
	public boolean isClosed() {
		return false;
	}

	@Override
	public void close() {
	}
}
