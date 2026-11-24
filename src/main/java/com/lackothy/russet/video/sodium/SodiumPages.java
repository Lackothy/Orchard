package com.lackothy.russet.video.sodium;

import com.lackothy.Russet;
import com.lackothy.russet.video.GraphicsMods;
import com.lackothy.russet.video.Pages;
import com.lackothy.russet.video.Row;
import com.lackothy.russet.video.Values;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.caffeinemc.mods.sodium.api.config.option.FlagHook;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.structure.OptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.PageBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

public final class SodiumPages {

    static final Values NOW = new Values() {
        @Override
        public <T> T get(Object key, Supplier<T> now) {
            return now.get();
        }
    };

    private static final Map<Identifier, Builders.Stateful<?, ?>> OPTIONS = new HashMap<>();
    private static final Set<Identifier> FLAGS = new LinkedHashSet<>();
    private static @Nullable List<Builders.Mod> mods;

    private SodiumPages() {
    }

    public static List<Pages.Group> groups() {
        List<Pages.Group> groups = new ArrayList<>();
        for (Builders.Mod mod : mods()) {
            List<Pages.Page> pages = new ArrayList<>();
            for (PageBuilder page : mod.pages) {
                if (page instanceof Builders.ExternalPage external) {
                    pages.add(new Pages.Page(external.name.getString(), List.of(), parent -> {
                        external.opens.accept(parent);
                        return null;
                    }));
                } else if (page instanceof Builders.OptionPage options) {
                    List<Pages.Section> sections = new ArrayList<>();
                    for (Builders.Group group : options.groups) {
                        List<Row> rows = new ArrayList<>();
                        for (OptionBuilder option : group.options) {
                            Row row = row(option);
                            if (row != null) {
                                rows.add(row);
                            }
                        }
                        if (!rows.isEmpty()) {
                            sections.add(new Pages.Section(group.name == null ? "" : group.name.getString(), rows));
                        }
                    }
                    if (!sections.isEmpty()) {
                        pages.add(new Pages.Page(options.name.getString(), sections));
                    }
                }
            }
            if (!pages.isEmpty()) {
                groups.add(new Pages.Group(mod.name, mod.version, mod.icon,
                    mod.theme != null ? mod.theme.theme() : GraphicsMods.theme(mod.configId), pages));
            }
        }
        return groups;
    }

    public static Set<String> modIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (Builders.Mod mod : mods()) {
            ids.add(mod.configId);
        }
        return ids;
    }

    private static @Nullable Row row(OptionBuilder option) {
        try {
            if (option instanceof Builders.Stateful<?, ?> stateful) {
                return stateful.row();
            }
            if (option instanceof Builders.Button button) {
                return button.row();
            }
        } catch (RuntimeException e) {
            Russet.LOG.warn("Couldn't show a mod's Sodium option", e);
        }
        return null;
    }

    private static List<Builders.Mod> mods() {
        if (mods == null) {
            Map<String, Builders.Mod> registered = new LinkedHashMap<>();
            List<EntrypointContainer<ConfigEntryPoint>> users = FabricLoader.getInstance()
                .getEntrypointContainers("sodium:config_api_user", ConfigEntryPoint.class);
            for (boolean early : new boolean[] {true, false}) {
                for (EntrypointContainer<ConfigEntryPoint> user : users) {
                    Builders.Config builder = new Builders.Config(user.getProvider(), registered);
                    try {
                        if (early) {
                            user.getEntrypoint().registerConfigEarly(builder);
                        } else {
                            user.getEntrypoint().registerConfigLate(builder);
                        }
                    } catch (RuntimeException | LinkageError e) {
                        Russet.LOG.warn("{}'s Sodium pages failed to register", user.getProvider().getMetadata().getId(), e);
                    }
                }
            }
            mods = List.copyOf(registered.values());
        }
        return mods;
    }

    static <T extends Builders.Stateful<?, ?>> T register(T option) {
        OPTIONS.put(option.id, option);
        return option;
    }

    static ConfigState state(Values values) {
        return new ConfigState() {
            @Override
            public boolean readBooleanOption(Identifier id) {
                return values.get(id, () -> current(id, Boolean.class, false));
            }

            @Override
            public int readIntOption(Identifier id) {
                return values.get(id, () -> current(id, Integer.class, 0));
            }

            @Override
            public <E extends Enum<E>> E readEnumOption(Identifier id, Class<E> enumClass) {
                return enumClass.cast(values.get(id, () -> current(id, enumClass, enumClass.getEnumConstants()[0])));
            }
        };
    }

    private static <T> T current(Identifier id, Class<T> type, T fallback) {
        Builders.Stateful<?, ?> option = OPTIONS.get(id);
        Object value = option == null || option.load == null ? null : option.load.get();
        return type.isInstance(value) ? type.cast(value) : fallback;
    }

    static void changed(Builders.Stateful<?, ?> option) {
        if (option.storage != null) {
            GraphicsMods.saveLater(option.storage, option.storage::afterSave);
        }
        if (option.applyHook != null) {
            GraphicsMods.afterSaving(option.applyHook, () -> option.applyHook.accept(state(NOW)));
        }
        if (!option.flags.isEmpty()) {
            FLAGS.addAll(option.flags);
            GraphicsMods.afterSaving(OptionFlag.class, SodiumPages::runFlags);
        }
    }

    private static void runFlags() {
        Set<Identifier> flags = Set.copyOf(FLAGS);
        FLAGS.clear();
        Minecraft mc = Minecraft.getInstance();
        if ((flags.contains(OptionFlag.REQUIRES_RENDERER_RELOAD.getId()) || flags.contains(OptionFlag.REQUIRES_RENDERER_UPDATE.getId()))
            && mc.level != null) {
            mc.levelExtractor.allChanged();
        }
        if (flags.contains(OptionFlag.REQUIRES_ASSET_RELOAD.getId())) {
            mc.delayTextureReload();
        }
        for (Builders.Mod mod : mods()) {
            for (FlagHook hook : mod.hooks) {
                if (hook.getTriggers().stream().anyMatch(flags::contains)) {
                    hook.accept(flags, state(NOW));
                }
            }
        }
    }
}
