package mcopt.metal.mixin.own;

import mcopt.metal.own.AnimOnePass;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SpriteContents.class)
abstract class OwnAnimIdentityMixin {
	@Inject(method = "createAnimationState", at = @At("RETURN"))
	private void mcopt$animOwner(CallbackInfoReturnable<SpriteContents.AnimationState> cir) {
		if (AnimOnePass.ON && cir.getReturnValue() != null) AnimOnePass.owned(cir.getReturnValue(), (SpriteContents) (Object) this);
	}
}
