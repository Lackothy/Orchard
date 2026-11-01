package mcopt.metal.mixin.gen;

import mcopt.metal.lod.LodGenQos;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NoiseBasedChunkGenerator.class)
abstract class GenWorldgenQosMixin {
	@Inject(method = "doFill", at = @At("HEAD"))
	private void mcopt$qos(CallbackInfo ci) {
		LodGenQos.once(LodGenQos.WORLDGEN, "chunk generation");
	}
}
