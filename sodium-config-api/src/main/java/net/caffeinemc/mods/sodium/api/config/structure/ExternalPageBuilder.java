// Russet: the names Sodium's config API gives mods, declared by Russet so mods that build Sodium pages
// work in Russet's menu without Sodium. Only loaded when Sodium isn't installed (SodiumConfigApi).
package net.caffeinemc.mods.sodium.api.config.structure;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

public interface ExternalPageBuilder extends PageBuilder {
    ExternalPageBuilder setName(Component name);

    ExternalPageBuilder setScreenConsumer(Consumer<Screen> currentScreenConsumer);
}
