package mcopt.metal.mixin.qos;

import mcopt.metal.Qos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.util.Util$2")
abstract class WorkerQosMixin {
	@Inject(method = "onStart", at = @At("HEAD"))
	private void mcopt$qos(CallbackInfo ci) {
		Qos.self("worker");
	}
}
