package com.lackothy.russet.video;

import com.lackothy.russet.mixin.OptionInstanceAccess;
import com.lackothy.russet.mixin.TooltipAccess;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

public sealed interface Row {

    Component RESTART = Component.literal("Takes effect the next time Minecraft starts.").withStyle(ChatFormatting.GRAY);
    Component GRAPHICS = Component.literal("Graphics");

    Component name();

    Component description();

    Control control();

    default Object key() {
        return this;
    }

    default boolean enabled(Values values) {
        return true;
    }

    record Vanilla(OptionInstance<?> option, Component own, Control control) implements Row {
        public Vanilla(OptionInstance<?> option, Component own) {
            this(option, own, Control.of(option));
        }

        @Override
        public Component name() {
            return option == Minecraft.getInstance().options.graphicsPreset() ? GRAPHICS : Control.caption(option);
        }

        @Override
        public Component description() {
            return own != null ? own : tooltip(option);
        }

        @Override
        public Object key() {
            return option;
        }

        @Override
        public boolean enabled(Values values) {
            Options options = Minecraft.getInstance().options;
            OptionInstance<TextureFilteringMethod> filtering = options.textureFiltering();
            return option != options.maxAnisotropyBit() || values.get(filtering, filtering::get) == TextureFilteringMethod.ANISOTROPIC;
        }

        private static Component tooltip(OptionInstance<?> option) {
            Tooltip tooltip = ((OptionInstanceAccess) (Object) option).russet$tooltip().apply(option.get());
            return tooltip == null ? null : ((TooltipAccess) tooltip).russet$message();
        }
    }

    record Switch(Component name, Component description, Object key, BooleanSupplier get, Consumer<Boolean> set,
                  Boolean fallback, Predicate<Values> when) implements Row {
        public Switch(String name, String description, VideoOptions.Toggle toggle) {
            this(Component.literal(name), Component.literal(description), toggle, values -> true);
        }

        public Switch(String name, String description, VideoOptions.Toggle toggle, VideoOptions.Toggle partOf) {
            this(Component.literal(name), Component.literal(description), toggle, values -> values.on(partOf));
        }

        public Switch(Component name, Component description, VideoOptions.Toggle toggle, Predicate<Values> when) {
            this(name, description, toggle, toggle::on, toggle::set, toggle.initial(), when);
        }

        @Override
        public Control control() {
            return new Control.Check(get, set, fallback);
        }

        @Override
        public boolean enabled(Values values) {
            return when.test(values);
        }
    }

    record Pick<E>(Component name, Component description, Object key, List<E> values, Function<E, String> label,
                   Supplier<E> get, Consumer<E> set, E fallback, Predicate<Values> when) implements Row {
        public static <E extends Enum<E>> Pick<E> of(Component name, Component description, VideoOptions.Choice<E> choice,
                                                    List<E> values, Function<E, String> label) {
            return of(name, description, choice, values, label, v -> true);
        }

        public static <E extends Enum<E>> Pick<E> of(Component name, Component description, VideoOptions.Choice<E> choice,
                                                    List<E> values, Function<E, String> label, Predicate<Values> when) {
            return new Pick<>(name, description, choice, values, label, choice::get, choice::set, choice.initial(), when);
        }

        @Override
        public Control control() {
            return new Control.Cycle<>(() -> values, get, set, value -> Component.literal(label.apply(value)), fallback);
        }

        @Override
        public boolean enabled(Values values) {
            return when.test(values);
        }
    }

    record Action(Component name, Component description, Component label, Runnable run) implements Row {
        @Override
        public Control control() {
            return new Control.Action(label, run);
        }
    }

    record Info(Component name, Component description, Component value) implements Row {
        @Override
        public Control control() {
            return new Control.Text(() -> value);
        }
    }

    record Live(Component name, Component description, Supplier<Component> value) implements Row {
        @Override
        public Control control() {
            return new Control.Text(value);
        }
    }

    record Note(Component name) implements Row {
        @Override
        public Component description() {
            return null;
        }

        @Override
        public Control control() {
            return null;
        }
    }
}
