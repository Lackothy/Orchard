package com.lackothy.russet.video.sodium;

import com.lackothy.russet.video.Control;
import com.lackothy.russet.video.Pages;
import com.lackothy.russet.video.Row;
import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.ControlValueFormatter;
import net.caffeinemc.mods.sodium.api.config.option.FlagHook;
import net.caffeinemc.mods.sodium.api.config.option.NameProvider;
import net.caffeinemc.mods.sodium.api.config.option.OptionBinding;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.option.Range;
import net.caffeinemc.mods.sodium.api.config.option.SteppedValidator;
import net.caffeinemc.mods.sodium.api.config.structure.BooleanOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ColorThemeBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.EnumOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ExternalButtonOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ExternalPageBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.IntegerOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ModOptionsBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionPageBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.PageBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.StatefulOptionBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

final class Builders {

    private Builders() {
    }

    static final class Config implements ConfigBuilder {
        private final ModContainer caller;
        private final Map<String, Mod> mods;

        Config(ModContainer caller, Map<String, Mod> mods) {
            this.caller = caller;
            this.mods = mods;
        }

        @Override
        public ModOptionsBuilder registerModOptions(String configId, String name, String version) {
            return mods.computeIfAbsent(configId, id -> new Mod(id, name, version));
        }

        @Override
        public ModOptionsBuilder registerModOptions(String configId) {
            ModContainer mod = FabricLoader.getInstance().getModContainer(configId).orElse(caller);
            return registerModOptions(configId, mod.getMetadata().getName(), mod.getMetadata().getVersion().getFriendlyString());
        }

        @Override
        public ModOptionsBuilder registerOwnModOptions() {
            return registerModOptions(caller.getMetadata().getId());
        }

        @Override
        public ColorThemeBuilder createColorTheme() {
            return new Theme();
        }

        @Override
        public OptionPageBuilder createOptionPage() {
            return new OptionPage();
        }

        @Override
        public ExternalPageBuilder createExternalPage() {
            return new ExternalPage();
        }

        @Override
        public OptionGroupBuilder createOptionGroup() {
            return new Group();
        }

        @Override
        public BooleanOptionBuilder createBooleanOption(Identifier id) {
            return SodiumPages.register(new Bool(id));
        }

        @Override
        public IntegerOptionBuilder createIntegerOption(Identifier id) {
            return SodiumPages.register(new Int(id));
        }

        @Override
        public <E extends Enum<E>> EnumOptionBuilder<E> createEnumOption(Identifier id, Class<E> enumClass) {
            return SodiumPages.register(new Choice<>(id, enumClass));
        }

        @Override
        public ExternalButtonOptionBuilder createExternalButtonOption(Identifier id) {
            return new Button();
        }
    }

    static final class Mod implements ModOptionsBuilder {
        final String configId;
        String name;
        String version;
        @Nullable Theme theme;
        @Nullable Identifier icon;
        final List<PageBuilder> pages = new ArrayList<>();
        final List<FlagHook> hooks = new ArrayList<>();

        Mod(String configId, String name, String version) {
            this.configId = configId;
            this.name = name;
            this.version = shortVersion(version);
        }

        private static String shortVersion(String version) {
            int plus = version.indexOf('+');
            return plus < 0 ? version : version.substring(0, plus);
        }

        @Override
        public ModOptionsBuilder setName(String name) {
            this.name = name;
            return this;
        }

        @Override
        public ModOptionsBuilder setVersion(String version) {
            this.version = version;
            return this;
        }

        @Override
        public ModOptionsBuilder formatVersion(Function<String, String> versionFormatter) {
            this.version = versionFormatter.apply(version);
            return this;
        }

        @Override
        public ModOptionsBuilder setColorTheme(ColorThemeBuilder colorTheme) {
            this.theme = (Theme) colorTheme;
            return this;
        }

        @Override
        public ModOptionsBuilder setIcon(Identifier texture) {
            this.icon = texture;
            return this;
        }

        @Override
        public ModOptionsBuilder setNonTintedIcon(Identifier texture) {
            this.icon = texture;
            return this;
        }

        @Override
        public ModOptionsBuilder addPage(PageBuilder page) {
            pages.add(page);
            return this;
        }

        @Override
        public ModOptionsBuilder registerOptionReplacement(Identifier target, OptionBuilder replacement) {
            return this;
        }

        @Override
        public ModOptionsBuilder registerOptionReplacement(Identifier target, OptionBuilder replacement, int priority) {
            return this;
        }

        @Override
        public ModOptionsBuilder registerOptionOverlay(Identifier target, OptionBuilder overlay) {
            return this;
        }

        @Override
        public ModOptionsBuilder registerOptionOverlay(Identifier target, OptionBuilder overlay, int priority) {
            return this;
        }

        @Override
        public ModOptionsBuilder registerFlagHook(BiConsumer<Collection<Identifier>, ConfigState> hook, Identifier... triggers) {
            List<Identifier> on = List.of(triggers);
            return registerFlagHook(new FlagHook() {
                @Override
                public Collection<Identifier> getTriggers() {
                    return on;
                }

                @Override
                public void accept(Collection<Identifier> flags, ConfigState state) {
                    hook.accept(flags, state);
                }
            });
        }

        @Override
        public ModOptionsBuilder registerFlagHook(FlagHook hook) {
            hooks.add(hook);
            return this;
        }
    }

    static final class Theme implements ColorThemeBuilder {
        private Pages.Theme theme = new Pages.Theme(0xFFFFFFFF, 0xFFFFFFFF, 0xFFAAAAAA);

        @Override
        public ColorThemeBuilder setBaseThemeRGB(int rgb) {
            theme = new Pages.Theme(opaque(rgb), opaque(blend(rgb, 0xFFFFFF, 0.6f)), opaque(blend(rgb, 0x8A8A8A, 0.5f)));
            return this;
        }

        @Override
        public ColorThemeBuilder setFullThemeRGB(int rgb, int highlight, int disabled) {
            theme = new Pages.Theme(opaque(rgb), opaque(highlight), opaque(disabled));
            return this;
        }

        Pages.Theme theme() {
            return theme;
        }

        private static int opaque(int rgb) {
            return 0xFF000000 | rgb;
        }

        private static int blend(int from, int to, float amount) {
            int r = Math.round(((from >> 16) & 0xFF) * (1 - amount) + ((to >> 16) & 0xFF) * amount);
            int g = Math.round(((from >> 8) & 0xFF) * (1 - amount) + ((to >> 8) & 0xFF) * amount);
            int b = Math.round((from & 0xFF) * (1 - amount) + (to & 0xFF) * amount);
            return (r << 16) | (g << 8) | b;
        }
    }

    static final class OptionPage implements OptionPageBuilder {
        Component name = Component.empty();
        final List<Group> groups = new ArrayList<>();

        @Override
        public OptionPageBuilder setName(Component name) {
            this.name = name;
            return this;
        }

        @Override
        public OptionPageBuilder addOptionGroup(OptionGroupBuilder group) {
            groups.add((Group) group);
            return this;
        }

        @Override
        public OptionPageBuilder addOption(OptionBuilder option) {
            if (groups.isEmpty() || groups.getLast().name != null) {
                groups.add(new Group());
            }
            groups.getLast().options.add(option);
            return this;
        }
    }

    static final class ExternalPage implements ExternalPageBuilder {
        Component name = Component.empty();
        Consumer<Screen> opens = screen -> { };

        @Override
        public ExternalPageBuilder setName(Component name) {
            this.name = name;
            return this;
        }

        @Override
        public ExternalPageBuilder setScreenConsumer(Consumer<Screen> currentScreenConsumer) {
            this.opens = currentScreenConsumer;
            return this;
        }
    }

    static final class Group implements OptionGroupBuilder {
        @Nullable Component name;
        final List<OptionBuilder> options = new ArrayList<>();

        @Override
        public OptionGroupBuilder setName(Component name) {
            this.name = name;
            return this;
        }

        @Override
        public OptionGroupBuilder addOption(OptionBuilder option) {
            options.add(option);
            return this;
        }
    }

    static final class Button implements ExternalButtonOptionBuilder {
        Component name = Component.empty();
        @Nullable Component tooltip;
        Consumer<Screen> opens = screen -> { };

        @Override
        public ExternalButtonOptionBuilder setName(Component name) {
            this.name = name;
            return this;
        }

        @Override
        public ExternalButtonOptionBuilder setTooltip(Component tooltip) {
            this.tooltip = tooltip;
            return this;
        }

        @Override
        public ExternalButtonOptionBuilder setEnabled(boolean available) {
            return this;
        }

        @Override
        public ExternalButtonOptionBuilder setEnabledProvider(Function<ConfigState, Boolean> provider, Identifier... dependencies) {
            return this;
        }

        @Override
        public ExternalButtonOptionBuilder setScreenConsumer(Consumer<Screen> currentScreenConsumer) {
            this.opens = currentScreenConsumer;
            return this;
        }

        Row row() {
            return new Row.Action(name, tooltip, Component.literal("Open"), () -> opens.accept(Minecraft.getInstance().gui.screen()));
        }
    }

    abstract static class Stateful<V, B extends StatefulOptionBuilder<V>> implements StatefulOptionBuilder<V> {
        final Identifier id;
        Component name = Component.empty();
        @Nullable Component tooltip;
        @Nullable Function<V, Component> tooltipOf;
        Function<ConfigState, Boolean> enabled = state -> true;
        @Nullable StorageEventHandler storage;
        @Nullable OptionImpact impact;
        final Set<Identifier> flags = new LinkedHashSet<>();
        @Nullable V defaultValue;
        @Nullable Function<ConfigState, V> defaultOf;
        @Nullable Consumer<V> save;
        @Nullable Supplier<V> load;
        @Nullable Consumer<ConfigState> applyHook;

        Stateful(Identifier id) {
            this.id = id;
        }

        abstract B self();

        abstract Control control(Supplier<V> get, Consumer<V> set, @Nullable V fallback);

        @Override
        public B setName(Component name) {
            this.name = name;
            return self();
        }

        @Override
        public B setEnabled(boolean available) {
            this.enabled = state -> available;
            return self();
        }

        @Override
        public B setEnabledProvider(Function<ConfigState, Boolean> provider, Identifier... dependencies) {
            this.enabled = provider;
            return self();
        }

        @Override
        public B setStorageHandler(StorageEventHandler storage) {
            this.storage = storage;
            return self();
        }

        @Override
        public B setTooltip(Component tooltip) {
            this.tooltip = tooltip;
            return self();
        }

        @Override
        public B setTooltip(Function<V, Component> tooltip) {
            this.tooltipOf = tooltip;
            return self();
        }

        @Override
        public B setImpact(OptionImpact impact) {
            this.impact = impact;
            return self();
        }

        @Override
        public B setFlags(OptionFlag... flags) {
            for (OptionFlag flag : flags) {
                this.flags.add(flag.getId());
            }
            return self();
        }

        @Override
        public B setFlags(Identifier... flags) {
            this.flags.addAll(List.of(flags));
            return self();
        }

        @Override
        public B setDefaultValue(V value) {
            this.defaultValue = value;
            return self();
        }

        @Override
        public B setDefaultProvider(Function<ConfigState, V> provider, Identifier... dependencies) {
            this.defaultOf = provider;
            return self();
        }

        @Override
        public B setControlHiddenWhenDisabled(boolean hidden) {
            return self();
        }

        @Override
        public B setBinding(Consumer<V> save, Supplier<V> load) {
            this.save = save;
            this.load = load;
            return self();
        }

        @Override
        public B setBinding(OptionBinding<V> binding) {
            return setBinding(binding::save, binding::load);
        }

        @Override
        public B setApplyHook(Consumer<ConfigState> hook) {
            this.applyHook = hook;
            return self();
        }

        @Nullable Row row() {
            Supplier<V> get = load;
            Consumer<V> put = save;
            if (get == null || put == null) {
                return null;
            }
            V fallback = defaultOf != null ? defaultOf.apply(SodiumPages.state(SodiumPages.NOW)) : defaultValue;
            Consumer<V> set = value -> {
                put.accept(value);
                SodiumPages.changed(this);
            };
            return new Row.Mod(name, description(get), id, control(get, set, fallback),
                values -> enabled.apply(SodiumPages.state(values)));
        }

        private @Nullable Component description(Supplier<V> get) {
            Component text = tooltipOf != null ? tooltipOf.apply(get.get()) : tooltip;
            MutableComponent out = text == null ? null : text.copy();
            if (impact != null) {
                Component line = Component.literal("Performance impact: ").withStyle(ChatFormatting.GRAY).append(impact.getName());
                out = out == null ? line.copy() : out.append("\n\n").append(line);
            }
            if (flags.contains(OptionFlag.REQUIRES_GAME_RESTART.getId())) {
                out = (out == null ? Component.empty() : out.append("\n\n")).append(Row.RESTART);
            }
            return out;
        }
    }

    static final class Bool extends Stateful<Boolean, BooleanOptionBuilder> implements BooleanOptionBuilder {
        Bool(Identifier id) {
            super(id);
        }

        @Override
        BooleanOptionBuilder self() {
            return this;
        }

        @Override
        Control control(Supplier<Boolean> get, Consumer<Boolean> set, @Nullable Boolean fallback) {
            return new Control.Check(get::get, set, fallback);
        }
    }

    static final class Int extends Stateful<Integer, IntegerOptionBuilder> implements IntegerOptionBuilder {
        private SteppedValidator range = new Range(0, 100, 1);
        private @Nullable Function<ConfigState, ? extends SteppedValidator> rangeOf;
        private @Nullable ControlValueFormatter formatter;

        Int(Identifier id) {
            super(id);
        }

        @Override
        IntegerOptionBuilder self() {
            return this;
        }

        @Override
        public IntegerOptionBuilder setRange(int min, int max, int step) {
            return setRange(new Range(min, max, step));
        }

        @Override
        public IntegerOptionBuilder setRange(Range range) {
            return setValidator(range);
        }

        @Override
        public IntegerOptionBuilder setRangeProvider(Function<ConfigState, ? extends SteppedValidator> provider, Identifier... dependencies) {
            return setValidatorProvider(provider, dependencies);
        }

        @Override
        public IntegerOptionBuilder setValidator(SteppedValidator validator) {
            this.range = validator;
            return this;
        }

        @Override
        public IntegerOptionBuilder setValidatorProvider(Function<ConfigState, ? extends SteppedValidator> provider, Identifier... dependencies) {
            this.rangeOf = provider;
            return this;
        }

        @Override
        public IntegerOptionBuilder setValueFormatter(ControlValueFormatter formatter) {
            this.formatter = formatter;
            return this;
        }

        @Override
        Control control(Supplier<Integer> get, Consumer<Integer> set, @Nullable Integer fallback) {
            SteppedValidator on = rangeOf != null ? rangeOf.apply(SodiumPages.state(SodiumPages.NOW)) : range;
            int min = on.min();
            int step = on.step();
            ControlValueFormatter text = formatter;
            return new Control.Slider<>(Math.max(1, (on.max() - min) / step), get, set,
                value -> Math.round((value - min) / (float) step), at -> min + at * step,
                value -> text != null ? text.format(value) : Component.literal(String.valueOf(value)), fallback);
        }
    }

    static final class Choice<E extends Enum<E>> extends Stateful<E, EnumOptionBuilder<E>> implements EnumOptionBuilder<E> {
        private final Class<E> type;
        private @Nullable Set<E> allowed;
        private @Nullable Function<ConfigState, Set<E>> allowedOf;
        private @Nullable Function<E, Component> names;

        Choice(Identifier id, Class<E> type) {
            super(id);
            this.type = type;
        }

        @Override
        EnumOptionBuilder<E> self() {
            return this;
        }

        @Override
        public EnumOptionBuilder<E> setAllowedValues(Set<E> allowedValues) {
            this.allowed = allowedValues;
            return this;
        }

        @Override
        public EnumOptionBuilder<E> setAllowedValuesProvider(Function<ConfigState, Set<E>> provider, Identifier... dependencies) {
            this.allowedOf = provider;
            return this;
        }

        @Override
        public EnumOptionBuilder<E> setElementNameProvider(Function<E, Component> provider) {
            this.names = provider;
            return this;
        }

        @Override
        Control control(Supplier<E> get, Consumer<E> set, @Nullable E fallback) {
            Function<E, Component> label = names != null ? names
                : value -> value instanceof NameProvider named ? named.getName() : Component.literal(value.name());
            return new Control.Cycle<>(() -> {
                Set<E> on = allowedOf != null ? allowedOf.apply(SodiumPages.state(SodiumPages.NOW)) : allowed;
                Set<E> in = on == null ? EnumSet.allOf(type) : on;
                return Arrays.stream(type.getEnumConstants()).filter(in::contains).toList();
            }, get, set, label, fallback);
        }
    }
}
