package mcopt.metal.mixin.own;

import mcopt.metal.own.OwnSolid;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelExtractor.class)
abstract class OwnSolidExtractorMixin {
	@Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"))
	private void mcopt$solidDirty(int sectionX, int sectionY, int sectionZ, boolean playerChanged, CallbackInfo ci) {
		OwnSolid.dirty(sectionX, sectionY, sectionZ);
	}

	@Inject(method = "setLevel", at = @At("RETURN"))
	private void mcopt$ownLevelGone(net.minecraft.client.multiplayer.ClientLevel level, CallbackInfo ci) {
		if (level != null) return;
		mcopt.metal.own.OwnTerrain own = mcopt.metal.own.OwnTerrain.get();
		if (own != null) own.disposeAll();
	}
}
