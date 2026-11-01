package mcopt.metal.chunk;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.chunk.DataLayer;

public final class LayeredLightMap extends Long2ObjectOpenHashMap<DataLayer> {
	public Long2ObjectOpenHashMap<DataLayer> base, delta;

	public LayeredLightMap() {
		super(0);
	}

	@Override
	public DataLayer get(long k) {
		DataLayer d = this.delta.get(k);
		if (d != null) return d == LightSnapshots.REMOVED ? null : d;
		return this.base.get(k);
	}

	@Override
	public boolean containsKey(long k) {
		return this.get(k) != null;
	}

	@Override
	public DataLayer put(long k, DataLayer v) {
		throw new UnsupportedOperationException("light snapshot is read-only");
	}

	@Override
	public DataLayer remove(long k) {
		throw new UnsupportedOperationException("light snapshot is read-only");
	}

	@Override
	public int size() {
		throw new UnsupportedOperationException("light snapshot has no size");
	}

	@Override
	public LayeredLightMap clone() {
		throw new UnsupportedOperationException("light snapshot is not copied");
	}
}
