package com.lackothy.orchard.mixin;

import com.lackothy.orchard.video.SeeThroughFaces;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Faster Improved Transparency (SeeThroughFaces): a compiled section keeps its see-through faces' groups. */
@Mixin(CompiledSectionMesh.class)
abstract class CompiledSectionMeshMixin implements SeeThroughFaces.Holder {

    /** Written on the worker that uploaded the section, read on the render thread. */
    @Unique
    private volatile SeeThroughFaces.Groups orchard$groups;

    @Override
    public SeeThroughFaces.Groups orchard$groups() {
        return this.orchard$groups;
    }

    @Override
    public void orchard$groups(SeeThroughFaces.Groups groups) {
        this.orchard$groups = groups;
    }
}
