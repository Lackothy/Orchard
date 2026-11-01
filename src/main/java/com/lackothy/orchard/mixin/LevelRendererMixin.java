package com.lackothy.orchard.mixin;

import com.lackothy.orchard.video.SeeThroughFaces;
import com.lackothy.orchard.video.VideoOptions;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Faster Improved Transparency (SeeThroughFaces): in 26.3's extractSectionDrawGroups each visible
 * section's layer becomes one IndexedDraw (its third List.add); a grouped see-through layer is
 * added instead as the runs that can face the camera. respectTranslucentOrder is false exactly
 * when Improved Transparency draws the frame.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {

    @Shadow
    @Final
    private LevelRenderState levelRenderState;

    @WrapOperation(method = "extractSectionDrawGroups", at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z", ordinal = 2))
    private boolean orchard$split(List<DynamicGpuData.IndexedDraw> draws, Object element, Operation<Boolean> original,
        @Local(argsOnly = true) boolean respectTranslucentOrder, @Local ChunkSectionLayer layer, @Local SectionMesh sectionMesh,
        @Local BlockPos renderOffset) {
        if (!respectTranslucentOrder && layer == ChunkSectionLayer.TRANSLUCENT && VideoOptions.FASTER_TRANSPARENCY.on()
            && element instanceof DynamicGpuData.IndexedDraw draw && sectionMesh instanceof SeeThroughFaces.Holder holder) {
            SeeThroughFaces.Groups groups = holder.orchard$groups();
            if (groups != null && SeeThroughFaces.split(groups, renderOffset, this.levelRenderState.cameraRenderState.pos, draw, draws,
                (list, part) -> original.call(list, part))) {
                return true;
            }
        }
        return original.call(draws, element);
    }

    @Inject(method = "extractSectionDrawGroups", at = @At("RETURN"))
    private void orchard$counted(boolean respectTranslucentOrder, List<?> sectionInfos, Map<?, ?> drawGroups,
        CallbackInfoReturnable<Integer> cir) {
        SeeThroughFaces.frame();
    }
}
