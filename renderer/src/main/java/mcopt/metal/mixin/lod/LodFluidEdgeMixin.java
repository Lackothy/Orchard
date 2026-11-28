package mcopt.metal.mixin.lod;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import mcopt.metal.lod.LodConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(FluidRenderer.class)
abstract class LodFluidEdgeMixin {
	@WrapOperation(method = "tesselate", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/block/FluidRenderer;shouldRenderFace("
		+ "Lnet/minecraft/world/level/material/FluidState;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;"
		+ "Lnet/minecraft/world/level/material/FluidState;)Z"), require = 5)
	private boolean mcopt$lodFluidEdge(FluidState fluid, BlockState state, Direction direction, FluidState neighbor, Operation<Boolean> original,
		@Local(argsOnly = true) BlockPos pos) {
		return original.call(fluid, state, direction, neighbor)
			&& !(direction.getAxis().isHorizontal() && LodConfig.ENABLED && mcopt$unloaded(pos, direction));
	}

	@Unique
	private static boolean mcopt$unloaded(BlockPos pos, Direction direction) {
		int x = (pos.getX() + direction.getStepX()) >> 4, z = (pos.getZ() + direction.getStepZ()) >> 4;
		if (x == pos.getX() >> 4 && z == pos.getZ() >> 4) return false;
		ClientLevel level = Minecraft.getInstance().level;
		return level != null && !level.getChunkSource().hasChunk(x, z);
	}
}
