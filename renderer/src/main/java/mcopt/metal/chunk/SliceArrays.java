package mcopt.metal.chunk;

import java.util.Arrays;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class SliceArrays {
	private SliceArrays() { }

	public static final BlockState[] AIR = new BlockState[4096];

	static {
		Arrays.fill(AIR, Blocks.AIR.defaultBlockState());
	}
}
