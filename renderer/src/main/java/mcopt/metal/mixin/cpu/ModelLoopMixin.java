package mcopt.metal.mixin.cpu;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import mcopt.metal.cpu.Cpu;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ModelPart.class)
abstract class ModelLoopMixin {
	@Shadow @Final private List<ModelPart.Cube> cubes;

	@Inject(method = "compile", at = @At("HEAD"), cancellable = true)
	private void mcopt$indexed(PoseStack.Pose pose, VertexConsumer builder, int lightCoords, int overlayCoords, int color, CallbackInfo ci) {
		if (!Cpu.modelActive) return;
		List<ModelPart.Cube> cubes = this.cubes;
		for (int i = 0, n = cubes.size(); i < n; i++) cubes.get(i).compile(pose, builder, lightCoords, overlayCoords, color);
		ci.cancel();
	}
}
