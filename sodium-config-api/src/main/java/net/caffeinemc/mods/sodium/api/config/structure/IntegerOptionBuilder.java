// Russet: the names Sodium's config API gives mods, declared by Russet so mods that build Sodium pages
// work in Russet's menu without Sodium. Only loaded when Sodium isn't installed (SodiumConfigApi).
package net.caffeinemc.mods.sodium.api.config.structure;

import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.ControlValueFormatter;
import net.caffeinemc.mods.sodium.api.config.option.OptionBinding;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.option.Range;
import net.caffeinemc.mods.sodium.api.config.option.SteppedValidator;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public interface IntegerOptionBuilder extends StatefulOptionBuilder<Integer> {
    @Override
    IntegerOptionBuilder setName(Component name);

    @Override
    IntegerOptionBuilder setEnabled(boolean available);

    @Override
    IntegerOptionBuilder setEnabledProvider(Function<ConfigState, Boolean> provider, Identifier... dependencies);

    @Override
    IntegerOptionBuilder setStorageHandler(StorageEventHandler storage);

    @Override
    IntegerOptionBuilder setTooltip(Component tooltip);

    @Override
    IntegerOptionBuilder setTooltip(Function<Integer, Component> tooltip);

    @Override
    IntegerOptionBuilder setImpact(OptionImpact impact);

    @Override
    IntegerOptionBuilder setFlags(OptionFlag... flags);

    @Override
    IntegerOptionBuilder setFlags(Identifier... flags);

    @Override
    IntegerOptionBuilder setDefaultValue(Integer value);

    @Override
    IntegerOptionBuilder setDefaultProvider(Function<ConfigState, Integer> provider, Identifier... dependencies);

    @Override
    IntegerOptionBuilder setControlHiddenWhenDisabled(boolean hidden);

    @Override
    IntegerOptionBuilder setBinding(Consumer<Integer> save, Supplier<Integer> load);

    @Override
    IntegerOptionBuilder setBinding(OptionBinding<Integer> binding);

    @Override
    IntegerOptionBuilder setApplyHook(Consumer<ConfigState> hook);

    IntegerOptionBuilder setRange(int min, int max, int step);

    IntegerOptionBuilder setRange(Range range);

    IntegerOptionBuilder setRangeProvider(Function<ConfigState, ? extends SteppedValidator> provider, Identifier... dependencies);

    IntegerOptionBuilder setValidator(SteppedValidator validator);

    IntegerOptionBuilder setValidatorProvider(Function<ConfigState, ? extends SteppedValidator> provider, Identifier... dependencies);

    IntegerOptionBuilder setValueFormatter(ControlValueFormatter formatter);
}
