package com.lackothy.orchard.mixin;

import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(TitleScreen.class)
public interface TitleScreenAccess {

    @Accessor("fading")
    void orchard$setFading(boolean fading);

    @Accessor("fadeInStart")
    void orchard$setFadeInStart(long start);
}
