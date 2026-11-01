package com.lackothy.orchard.mixin;

import com.lackothy.orchard.video.SeeThroughFaces;
import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Faster Improved Transparency (SeeThroughFaces): a section's see-through faces are grouped as
 * they are uploaded, whenever Improved Transparency is on (its passes don't need Minecraft's back
 * to front order, so the switch can take effect at once). A resort (no vertices, only a new order)
 * or an upload for sorted drawing leaves the section in Minecraft's order, without groups.
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
abstract class RenderSectionMixin {

    @Inject(method = "addSectionBuffersToUberBuffer", at = @At("HEAD"))
    private void orchard$group(ChunkSectionLayer layer, CompiledSectionMesh key, @Nullable ByteBuffer vertexBuffer,
        @Nullable ByteBuffer indexBuffer, CallbackInfoReturnable<Boolean> cir) {
        if (layer != ChunkSectionLayer.TRANSLUCENT) {
            return;
        }
        SeeThroughFaces.Holder holder = (SeeThroughFaces.Holder) key;
        SectionMesh.SectionDraw draw = key.getSectionDraw(layer);
        if (vertexBuffer == null || indexBuffer == null || draw == null || !draw.hasCustomIndexBuffer()
            || !Minecraft.getInstance().gameRenderer.useImprovedTransparency()) {
            holder.orchard$groups(null);
            return;
        }
        holder.orchard$groups(SeeThroughFaces.group(vertexBuffer, indexBuffer, draw.indexType()));
    }
}
