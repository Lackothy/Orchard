package mcopt.metal.chunk;

import net.minecraft.world.level.chunk.DataLayer;

public interface LightSnapshots {
	DataLayer REMOVED = new DataLayer();

	void mcopt$snapshotInto(Object snapshot);
}
