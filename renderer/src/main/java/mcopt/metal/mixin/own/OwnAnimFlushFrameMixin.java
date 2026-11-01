package mcopt.metal.mixin.own;

import mcopt.metal.own.AnimOnePass;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class OwnAnimFlushFrameMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void mcopt$animFlushFrame(CallbackInfo ci) {
		AnimOnePass.flush();
	}
}
