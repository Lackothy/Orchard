package mcopt.metal.mixin;

import mcopt.metal.ResidentAnim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.client.renderer.texture.SpriteContents$AnimatedTexture")
public class AnimFramesResidentMixin {
	@Inject(method = "createAnimationState", at = @At("HEAD"))
	private void mcopt$residentHead(CallbackInfoReturnable<?> cir) {
		ResidentAnim.creating = ResidentAnim.ON;
	}

	@Inject(method = "createAnimationState", at = @At("RETURN"))
	private void mcopt$residentReturn(CallbackInfoReturnable<?> cir) {
		ResidentAnim.creating = false;
	}
}
