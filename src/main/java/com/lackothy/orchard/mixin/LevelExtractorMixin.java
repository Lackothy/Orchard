package com.lackothy.orchard.mixin;

import com.lackothy.orchard.video.BakedBlockEntities;
import com.lackothy.orchard.video.FarmChanges;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Wait out farm changes (FarmChanges): every section mark passes through here, and those held
 * back are made at the start of a later frame, before its sections are picked for redrawing.
 * BakedBlockEntities makes its marks for what has lost its cracks there too.
 */
@Mixin(LevelExtractor.class)
abstract class LevelExtractorMixin {

    @Shadow
    private SectionUpdateTracker sectionUpdateTracker;

    @Shadow
    public abstract void setSectionDirty(int x, int y, int z);

    @Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"), cancellable = true)
    private void orchard$waitOut(int x, int y, int z, boolean byPlayer, CallbackInfo ci) {
        if (this.sectionUpdateTracker != null && FarmChanges.hold(x, y, z, byPlayer, this.sectionUpdateTracker)) {
            ci.cancel();
        }
    }

    @Inject(method = "extract", at = @At("HEAD"))
    private void orchard$waited(DeltaTracker delta, Camera camera, float partialTick, CallbackInfo ci) {
        FarmChanges.tick(s -> this.setSectionDirty(s.x(), s.y(), s.z()));
        BakedBlockEntities.frame(Minecraft.getInstance().level);
    }
}
