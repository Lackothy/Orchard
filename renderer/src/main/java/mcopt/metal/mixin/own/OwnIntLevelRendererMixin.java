package mcopt.metal.mixin.own;

import mcopt.metal.own.OwnSeam;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(LevelRenderer.class)
abstract class OwnIntLevelRendererMixin {
	@ModifyVariable(method = "extractSectionDrawGroups", at = @At("STORE"), ordinal = 0)
	private long mcopt$ownFade(long fadeDuration) {
		return OwnSeam.fadeMs(fadeDuration);
	}
}
