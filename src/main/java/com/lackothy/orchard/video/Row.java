package com.lackothy.orchard.video;

import com.lackothy.orchard.mixin.OptionInstanceAccess;
import com.lackothy.orchard.mixin.TooltipAccess;
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

/** One line of a settings page: a name, and what sets it. */
public sealed interface Row {

    Component RESTART = Component.literal("Takes effect the next time Minecraft starts.").withStyle(ChatFormatting.GRAY);

    Component name();

    /** What the setting does, shown when the row is pointed at; null for nothing to say. */
    Component description();

    /** What sets it, or null for a row that is only text. */
    Control control();

    /** What other rows find this one's setting by, in {@link Values}. */
    default Object key() {
        return this;
    }

    /** False while the setting means nothing, such as a part of something that is off. */
    default boolean enabled(Values values) {
        return true;
    }

    /** One of Minecraft's options. Without a description of Orchard's, Minecraft's own tooltip says what it does. */
    record Vanilla(OptionInstance<?> option, Component own, Control control) implements Row {
        public Vanilla(OptionInstance<?> option, Component own) {
            this(option, own, Control.of(option));
        }

        @Override
        public Component name() {
            return Control.caption(option);
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
            // Anisotropy only means something with anisotropic filtering, as in Minecraft's menu.
            Options options = Minecraft.getInstance().options;
            OptionInstance<TextureFilteringMethod> filtering = options.textureFiltering();
            return option != options.maxAnisotropyBit() || values.get(filtering, filtering::get) == TextureFilteringMethod.ANISOTROPIC;
        }

        private static Component tooltip(OptionInstance<?> option) {
            Tooltip tooltip = ((OptionInstanceAccess) (Object) option).orchard$tooltip().apply(option.get());
            return tooltip == null ? null : ((TooltipAccess) tooltip).orchard$message();
        }
    }

    /**
     * An on/off setting of Orchard's; {@code when} greys it out while something it is part of is
     * off, and {@code fallback} is what it starts as, or null when that isn't known.
     */
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

    /** One of Orchard's own settings with a few choices. */
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

    /** Something to do rather than set, such as playing what a setting looks like. */
    record Action(Component name, Component description, Component label, Runnable run) implements Row {
        @Override
        public Control control() {
            return new Control.Action(label, run);
        }
    }

    /** A fact rather than a setting: its value sits where a control would. */
    record Info(Component name, Component description, Component value) implements Row {
        @Override
        public Control control() {
            return new Control.Text(() -> value);
        }
    }

    /** A fact that changes while the menu is open, read each time it is drawn. */
    record Live(Component name, Component description, Supplier<Component> value) implements Row {
        @Override
        public Control control() {
            return new Control.Text(value);
        }
    }

    /** A line of grey text across the page. */
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
