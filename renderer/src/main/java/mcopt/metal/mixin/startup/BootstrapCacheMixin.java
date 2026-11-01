package mcopt.metal.mixin.startup;

import mcopt.metal.Startup;
import net.minecraft.server.Bootstrap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Bootstrap.class)
abstract class BootstrapCacheMixin {
	@Inject(method = "bootStrap", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/FireBlock;bootStrap()V", shift = At.Shift.AFTER))
	private static void mcopt$caches(CallbackInfo ci) {
		Startup.finishBlockCaches();
	}
}
