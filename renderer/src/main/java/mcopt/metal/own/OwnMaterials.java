package mcopt.metal.own;

import net.minecraft.world.level.block.state.BlockState;

public final class OwnMaterials {
	public static final boolean ON = false;
	private static final ThreadLocal<byte[]> GRID = ThreadLocal.withInitial(() -> new byte[4096]);
	private static final ThreadLocal<boolean[]> COMPILING = ThreadLocal.withInitial(() -> new boolean[1]);

	private OwnMaterials() {
	}

	public static void block(int x, int y, int z, BlockState state) {
		GRID.get()[(y & 15) << 8 | (z & 15) << 4 | x & 15] = 0;
	}

	public static boolean compiling() {
		return COMPILING.get()[0];
	}

	public static void compiling(boolean on) {
		COMPILING.get()[0] = on;
	}

	static byte[] grid() {
		return GRID.get();
	}
}
