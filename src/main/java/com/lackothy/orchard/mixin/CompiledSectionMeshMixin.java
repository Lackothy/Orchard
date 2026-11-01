package com.lackothy.orchard.mixin;

import com.lackothy.orchard.video.SeeThroughFaces;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(CompiledSectionMesh.class)
abstract class CompiledSectionMeshMixin implements SeeThroughFaces.Holder {

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
