package mcopt.metal.mixin.own;

import mcopt.metal.own.AnimOnePass;
import net.minecraft.client.renderer.texture.TextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TextureManager.class)
public class OwnAnimFlushMixin {
	@Inject(method = "tick", at = @At("RETURN"))
	private void mcopt$animFlush(CallbackInfo ci) {
		AnimOnePass.flush();
	}
}
