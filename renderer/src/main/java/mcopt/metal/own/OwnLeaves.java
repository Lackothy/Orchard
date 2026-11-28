package mcopt.metal.own;

import com.lackothy.russet.video.VideoOptions;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

public final class OwnLeaves {
	public static final int AS_IS = 0, OPAQUE = 1, HIDDEN = 2;
	private static final Direction[] DIRECTIONS = Direction.values();

	private OwnLeaves() {
	}

	public static boolean on(boolean cutoutLeaves) {
		return cutoutLeaves && VideoOptions.SMART_LEAVES.on();
	}

	public static boolean leaf(BlockState state) {
		return state.getBlock() instanceof LeavesBlock;
	}

	static int side(BlockAndTintGetter level, BlockPos pos, @Nullable Direction side, BlockPos.MutableBlockPos at) {
		if (side == null || !leaf(level.getBlockState(at.setWithOffset(pos, side)))) return AS_IS;
		int x = at.getX(), y = at.getY(), z = at.getZ();
		Direction back = side.getOpposite();
		for (Direction d : DIRECTIONS) {
			if (d == back) continue;
			BlockState s = level.getBlockState(at.set(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ()));
			if (!leaf(s) && !s.isSolidRender()) return OPAQUE;
		}
		return HIDDEN;
	}

	static BlockQuadOutput output(BlockAndTintGetter level, BlockPos pos, BlockQuadOutput asIs, BlockQuadOutput opaque) {
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		return (x, y, z, quad, instance) -> {
			int f = side(level, pos, cullFace(quad), at);
			if (f == AS_IS) asIs.put(x, y, z, quad, instance);
			else if (f == OPAQUE) opaque.put(x, y, z, quad, instance);
		};
	}

	public static boolean besideLeaf(BlockAndTintGetter level, BlockPos pos, boolean cutoutLeaves) {
		if (!on(cutoutLeaves)) return false;
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		for (Direction d : DIRECTIONS) if (leaf(level.getBlockState(at.setWithOffset(pos, d)))) return true;
		return false;
	}

	private static @Nullable Direction cullFace(BakedQuad quad) {
		Direction d = quad.direction();
		int axis = d.getAxis().ordinal();
		float plane = d.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0;
		for (int v = 0; v < 4; v++) if (Math.abs(quad.position(v).get(axis) - plane) > 1e-4f) return null;
		return d;
	}
}
