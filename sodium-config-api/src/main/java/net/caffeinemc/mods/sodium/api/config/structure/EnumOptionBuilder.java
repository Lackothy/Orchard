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

import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public interface EnumOptionBuilder<E extends Enum<E>> extends StatefulOptionBuilder<E> {
    static <E extends Enum<E>> Function<E, Component> nameProviderFrom(Component... names) {
        return value -> names[value.ordinal()];
    }

    @Override
    EnumOptionBuilder<E> setName(Component name);

    @Override
    EnumOptionBuilder<E> setEnabled(boolean available);

    @Override
    EnumOptionBuilder<E> setEnabledProvider(Function<ConfigState, Boolean> provider, Identifier... dependencies);

    @Override
    EnumOptionBuilder<E> setStorageHandler(StorageEventHandler storage);

    @Override
    EnumOptionBuilder<E> setTooltip(Component tooltip);

    @Override
    EnumOptionBuilder<E> setTooltip(Function<E, Component> tooltip);

    @Override
    EnumOptionBuilder<E> setImpact(OptionImpact impact);

    @Override
    EnumOptionBuilder<E> setFlags(OptionFlag... flags);

    @Override
    EnumOptionBuilder<E> setFlags(Identifier... flags);

    @Override
    EnumOptionBuilder<E> setDefaultValue(E value);

    @Override
    EnumOptionBuilder<E> setDefaultProvider(Function<ConfigState, E> provider, Identifier... dependencies);

    @Override
    EnumOptionBuilder<E> setControlHiddenWhenDisabled(boolean hidden);

    @Override
    EnumOptionBuilder<E> setBinding(Consumer<E> save, Supplier<E> load);

    @Override
    EnumOptionBuilder<E> setBinding(OptionBinding<E> binding);

    @Override
    EnumOptionBuilder<E> setApplyHook(Consumer<ConfigState> hook);

    EnumOptionBuilder<E> setAllowedValues(Set<E> allowedValues);

    EnumOptionBuilder<E> setAllowedValuesProvider(Function<ConfigState, Set<E>> provider, Identifier... dependencies);

    EnumOptionBuilder<E> setElementNameProvider(Function<E, Component> provider);
}
