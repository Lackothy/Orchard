package com.lackothy.orchard.video;

import com.lackothy.orchard.mixin.OptionInstanceAccess;
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

/**
 * What a row's right-hand side does: a box to tick, a value to click through, a slider, or a
 * fact. Minecraft's options get the one that fits their values, so every option works here
 * without knowing it by name. Each says what it starts as ({@code fallback}), or null when that
 * isn't known, for the menu's reset button.
 */
public sealed interface Control {

    /** On or off. */
    record Check(BooleanSupplier get, Consumer<Boolean> set, Boolean fallback) implements Control {
    }

    /** One of a few values, clicked through in order; {@code values} can change, as Minecraft's do while Alt is held. */
    record Cycle<T>(Supplier<List<T>> values, Supplier<T> get, Consumer<T> set, Function<T, Component> label,
                    T fallback) implements Control {
    }

    /** A value along a track of {@code steps} steps: {@code position} finds a value's step, {@code at} a step's value. */
    record Slider<T>(int steps, Supplier<T> get, Consumer<T> set, ToIntFunction<T> position, IntFunction<T> at,
                     Function<T, Component> label, T fallback) implements Control {
    }

    /** Something to read, not set. */
    record Text(Supplier<Component> value) implements Control {
    }

    /** Something to do: {@code label} says what, on the right of the row. */
    record Action(Component label, Runnable run) implements Control {
    }

    /** The control for one of Minecraft's options, from what its values are. */
    @SuppressWarnings("unchecked")
    static <T> Control of(OptionInstance<T> option) {
        OptionInstanceAccess access = (OptionInstanceAccess) (Object) option;
        Function<Object, Component> toString = access.orchard$toString();
        Function<T, Component> label = value -> valueOnly(caption(option), toString.apply(value));
        T fallback = (T) access.orchard$initialValue();
        OptionInstance.ValueSet<T> values = option.values();

        if (values == (Object) OptionInstance.BOOLEAN_VALUES) {
            OptionInstance<Boolean> flag = (OptionInstance<Boolean>) (Object) option;
            return new Check(flag::get, on -> OptionInstance.BOOLEAN_VALUES.valueSetter().set(flag, on), (Boolean) fallback);
        }
        boolean cycles = values instanceof OptionInstance.SliderableOrCyclableValueSet<T> both && both.createCycleButton();
        if (values instanceof OptionInstance.SliderableValueSet<T> slider && !cycles) {
            // Whole numbers go one at a time; anything else in hundredths of the track.
            int steps = slider instanceof OptionInstance.IntRangeBase range
                ? Math.max(1, range.maxInclusive() - range.minInclusive()) : 100;
            return new Slider<>(steps, option::get, option::set,
                value -> (int) Math.round(slider.toSliderValue(value) * steps),
                step -> slider.fromSliderValue((double) step / steps), label, fallback);
        }
        if (values instanceof OptionInstance.CycleableValueSet<T> cycle) {
            // The list Minecraft's own button steps through, Alt's while Alt is held.
            return new Cycle<>(() -> cycle.valueListSupplier().getSelectedList(), option::get,
                value -> cycle.valueSetter().set(option, value), label, fallback);
        }
        return new Text(() -> label.apply(option.get()));
    }

    /**
     * What an option is called. A few of Minecraft's have a caption with no words behind it and
     * say their name in their value instead ("Chat Delay: None"), so it is taken from there.
     */
    static Component caption(OptionInstance<?> option) {
        OptionInstanceAccess access = (OptionInstanceAccess) (Object) option;
        Component caption = access.orchard$caption();
        if (caption.getContents() instanceof TranslatableContents translatable
            && !Language.getInstance().has(translatable.getKey())) {
            String text = access.orchard$toString().apply(option.get()).getString();
            String[] parts = text.split("\\s*[:：]", 2);
            if (parts.length == 2 && !parts[0].isBlank()) {
                return Component.literal(parts[0]);
            }
        }
        return caption;
    }

    /** {@code message} without the "Caption: " Minecraft puts before an option's value. */
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
            // A few shorten their name before the value, as Chunk Fade Time's "Chunk Fade: None" does.
            String[] parts = text.split("\\s*[:：]\\s*", 2);
            rest = parts.length == 2 && !parts[0].isEmpty() && name.startsWith(parts[0]) ? parts[1] : "";
        }
        return rest.isEmpty() ? message : Component.literal(rest).withStyle(message.getStyle());
    }
}
