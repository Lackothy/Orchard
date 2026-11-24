// Russet: the names Sodium's config API gives mods, declared by Russet so mods that build Sodium pages
// work in Russet's menu without Sodium. Only loaded when Sodium isn't installed (SodiumConfigApi).
package net.caffeinemc.mods.sodium.api.config.structure;

import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.OptionBinding;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public interface StatefulOptionBuilder<V> extends OptionBuilder {
    @Override
    StatefulOptionBuilder<V> setName(Component name);

    @Override
    OptionBuilder setEnabled(boolean available);

    @Override
    OptionBuilder setEnabledProvider(Function<ConfigState, Boolean> provider, Identifier... dependencies);

    StatefulOptionBuilder<V> setStorageHandler(StorageEventHandler storage);

    @Override
    StatefulOptionBuilder<V> setTooltip(Component tooltip);

    StatefulOptionBuilder<V> setTooltip(Function<V, Component> tooltip);

    StatefulOptionBuilder<V> setImpact(OptionImpact impact);

    StatefulOptionBuilder<V> setFlags(OptionFlag... flags);

    StatefulOptionBuilder<V> setFlags(Identifier... flags);

    StatefulOptionBuilder<V> setDefaultValue(V value);

    StatefulOptionBuilder<V> setDefaultProvider(Function<ConfigState, V> provider, Identifier... dependencies);

    StatefulOptionBuilder<V> setControlHiddenWhenDisabled(boolean hidden);

    StatefulOptionBuilder<V> setBinding(Consumer<V> save, Supplier<V> load);

    StatefulOptionBuilder<V> setBinding(OptionBinding<V> binding);

    StatefulOptionBuilder<V> setApplyHook(Consumer<ConfigState> hook);
}
