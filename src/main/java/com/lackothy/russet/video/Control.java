package com.lackothy.russet.video;

import com.lackothy.russet.mixin.OptionInstanceAccess;
import net.minecraft.client.OptionInstance;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

public sealed interface Control {

    record Check(BooleanSupplier get, Consumer<Boolean> set, Boolean fallback) implements Control {
    }

    record Cycle<T>(Supplier<List<T>> values, Supplier<T> get, Consumer<T> set, Function<T, Component> label,
                    T fallback) implements Control {
    }

    record Slider<T>(int steps, Supplier<T> get, Consumer<T> set, ToIntFunction<T> position, IntFunction<T> at,
                     Function<T, Component> label, T fallback) implements Control {
    }

    record Text(Supplier<Component> value) implements Control {
    }

    record Action(Component label, Runnable run) implements Control {
    }

    @SuppressWarnings("unchecked")
    static <T> Control of(OptionInstance<T> option) {
        OptionInstanceAccess access = (OptionInstanceAccess) (Object) option;
        Function<Object, Component> toString = access.russet$toString();
        Function<T, Component> label = value -> valueOnly(caption(option), toString.apply(value));
        T fallback = (T) access.russet$initialValue();
        OptionInstance.ValueSet<T> values = option.values();

        if (values == (Object) OptionInstance.BOOLEAN_VALUES) {
            OptionInstance<Boolean> flag = (OptionInstance<Boolean>) (Object) option;
            return new Check(flag::get, on -> OptionInstance.BOOLEAN_VALUES.valueSetter().set(flag, on), (Boolean) fallback);
        }
        boolean cycles = values instanceof OptionInstance.SliderableOrCyclableValueSet<T> both && both.createCycleButton();
        if (values instanceof OptionInstance.SliderableValueSet<T> slider && !cycles) {
            int steps = slider instanceof OptionInstance.IntRangeBase range
                ? Math.max(1, range.maxInclusive() - range.minInclusive()) : 100;
            return new Slider<>(steps, option::get, option::set,
                value -> (int) Math.round(slider.toSliderValue(value) * steps),
                step -> slider.fromSliderValue((double) step / steps), label, fallback);
        }
        if (values instanceof OptionInstance.CycleableValueSet<T> cycle) {
            return new Cycle<>(() -> cycle.valueListSupplier().getSelectedList(), option::get,
                value -> cycle.valueSetter().set(option, value), label, fallback);
        }
        return new Text(() -> label.apply(option.get()));
    }

    static Component caption(OptionInstance<?> option) {
        OptionInstanceAccess access = (OptionInstanceAccess) (Object) option;
        Component caption = access.russet$caption();
        if (caption.getContents() instanceof TranslatableContents translatable
            && !Language.getInstance().has(translatable.getKey())) {
            String text = access.russet$toString().apply(option.get()).getString();
            String[] parts = text.split("\\s*[:：]", 2);
            if (parts.length == 2 && !parts[0].isBlank()) {
                return Component.literal(parts[0]);
            }
        }
        return caption;
    }

    static Component valueOnly(Component caption, Component message) {
        if (message.getContents() instanceof TranslatableContents translatable
            && translatable.getKey().equals("options.generic_value") && translatable.getArgs().length == 2) {
            Object value = translatable.getArgs()[1];
            return value instanceof Component component ? component : Component.literal(String.valueOf(value));
        }
        String text = message.getString();
        String name = caption.getString();
        String rest;
        if (text.startsWith(name)) {
            rest = text.substring(name.length()).replaceFirst("^\\s*[:：]\\s*", "");
        } else {
            String[] parts = text.split("\\s*[:：]\\s*", 2);
            rest = parts.length == 2 && !parts[0].isEmpty() && name.startsWith(parts[0]) ? parts[1] : "";
        }
        return rest.isEmpty() ? message : Component.literal(rest).withStyle(message.getStyle());
    }
}
