package mcopt.metal.mixin.ownmesh;

import mcopt.metal.own.OwnRegion;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RenderSectionRegion.class)
abstract class OwnMeshRegionStateMixin {
	@Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
	private void mcopt$ownState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
		OwnRegion r = OwnRegion.forRegion(this);
		if (r != null) cir.setReturnValue(r.getBlockState(pos));
	}

	@Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
	private void mcopt$ownFluid(BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
		OwnRegion r = OwnRegion.forRegion(this);
		if (r != null) cir.setReturnValue(r.getFluidState(pos));
	}
}
