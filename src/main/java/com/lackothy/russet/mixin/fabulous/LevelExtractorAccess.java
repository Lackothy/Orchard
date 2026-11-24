package com.lackothy.russet.mixin.fabulous;

import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LevelExtractor.class)
public interface LevelExtractorAccess {

    @Invoker("setSectionDirty")
    void russet$setSectionDirty(int x, int y, int z, boolean playerChanged);
}
