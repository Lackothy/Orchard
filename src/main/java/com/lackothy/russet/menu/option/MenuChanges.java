package com.lackothy.russet.menu.option;

import com.lackothy.russet.menu.config.MenuConfig;
import com.lackothy.russet.video.Control;
import com.lackothy.russet.video.Pages;
import com.lackothy.russet.video.Row;
import com.lackothy.russet.video.Values;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * What the video settings show and hold, for as long as they are open: every group of
 * {@link Pages}, made into lines, and the changes made to them until Apply, as Sodium's config
 * manager is to Reese's Sodium Options. Rows that depend on other settings read them through
 * this, so a part greys out while what it is part of is set to off, before Apply.
 */
public final class MenuChanges implements Values {

    /** The page of the menu's own settings, in Russet's group. */
    public static final String MENU_PAGE = "Menu";
    public static final String MENU_GROUP = "russet";

    private final List<MenuGroup> groups = new ArrayList<>();
    private final List<MenuSetting<?>> settings = new ArrayList<>();
    private final Map<Object, MenuSetting<?>> byKey = new HashMap<>();
    private final Set<MenuSetting<?>> ownSettings = new HashSet<>();
    private final Set<Identifier> ids = new HashSet<>();

    public MenuChanges() {
        for (Pages.Group group : Pages.groups()) {
            String configId = configId(group);
            List<MenuPage> pages = new ArrayList<>();
            for (Pages.Page page : group.pages()) {
                List<MenuSection> sections = new ArrayList<>();
                for (Pages.Section section : page.sections()) {
                    List<MenuOption> options = new ArrayList<>();
                    for (Row row : section.rows()) {
                        options.add(this.option(this.id(configId, page.name(), section.title(), row.name().getString()), row));
                    }
                    sections.add(new MenuSection(Component.literal(section.title()), options));
                }
                pages.add(new MenuPage(Component.literal(page.name()), sections));
            }
            if (configId.equals(MENU_GROUP)) {
                pages.add(this.menuPage(configId));
            }
            groups.add(new MenuGroup(configId, group.name(), group.version(), group.theme(), group.icon(), pages));
        }
    }

    /** What a group's tabs are known by. */
    public static String configId(Pages.Group group) {
        return sanitize(group.name());
    }

    public List<MenuGroup> getModOptions() {
        return groups;
    }

    public boolean anyOptionChanged() {
        for (MenuSetting<?> setting : settings) {
            if (setting.hasChanged()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Writes every change, and says whether the menu's own settings were among them. Those to
     * make are picked first: a change can set others, as Minecraft's graphics preset does.
     */
    public boolean applyAllOptions() {
        List<MenuSetting<?>> changed = settings.stream().filter(MenuSetting::hasChanged).toList();
        boolean own = false;
        for (MenuSetting<?> setting : changed) {
            setting.apply();
            own |= ownSettings.contains(setting);
        }
        resetAllOptionsFromBindings();
        Minecraft.getInstance().options.save();
        if (own) {
            MenuConfig.writeToDisk();
        }
        return own;
    }

    /** Takes back every change not yet applied. */
    public void resetAllOptionsFromBindings() {
        settings.forEach(MenuSetting::undo);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Supplier<T> now) {
        MenuSetting<?> setting = byKey.get(key);
        return setting != null ? (T) setting.getValidatedValue() : now.get();
    }

    // ── Russet's rows ──

    private MenuOption option(Identifier id, Row row) {
        Component name = row.name();
        Component tooltip = row.description();
        BooleanSupplier enabled = () -> row.enabled(this);
        MenuOption option = switch (row.control()) {
            case Control.Check check -> new MenuToggle(id, name, tooltip, enabled, check.get()::getAsBoolean, check.set(), check.fallback());
            case Control.Cycle<?> cycle -> choice(id, name, tooltip, enabled, cycle);
            case Control.Slider<?> slider -> slider(id, name, tooltip, enabled, slider);
            case Control.Text text -> new MenuFact(id, name, tooltip, text.value());
            case Control.Action action -> new MenuButton(id, name, tooltip, enabled, action.label(), action.run());
            case null -> new MenuFact(id, name, tooltip, null);
        };
        if (option instanceof MenuSetting<?> setting) {
            settings.add(setting);
            byKey.putIfAbsent(row.key(), setting);
        }
        return option;
    }

    private static <T> MenuChoice<T> choice(Identifier id, Component name, Component tooltip, BooleanSupplier enabled, Control.Cycle<T> cycle) {
        return new MenuChoice<>(id, name, tooltip, enabled, cycle.get(), cycle.set(), cycle.fallback(), cycle.values(), cycle.label());
    }

    private static <T> MenuSlider<T> slider(Identifier id, Component name, Component tooltip, BooleanSupplier enabled, Control.Slider<T> slider) {
        return new MenuSlider<>(id, name, tooltip, enabled, slider.get(), slider.set(), slider.fallback(),
            slider.steps(), slider.position(), slider.at(), slider.label());
    }

    /** A line's id: its group, page, section and name, made unique if two read the same. */
    private Identifier id(String... parts) {
        String path = String.join("/", Arrays.stream(parts).map(MenuChanges::sanitize).toList());
        Identifier id = Identifier.fromNamespaceAndPath("russet", path);
        for (int i = 2; !ids.add(id); i++) {
            id = Identifier.fromNamespaceAndPath("russet", path + "_" + i);
        }
        return id;
    }

    private static String sanitize(String text) {
        String path = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "_").replaceAll("^_+|_+$", "");
        return path.isEmpty() ? "_" : path;
    }

    // ── the menu's own settings, as Reese's Sodium Options lists its own ──

    private MenuPage menuPage(String configId) {
        MenuConfig.ConfigData c = MenuConfig.config();
        String page = "menu";
        List<MenuOption> appearance = List.of(
            own(configId, page, "tab_header_icons", c::isTabHeaderIcons, c::setTabHeaderIcons, true),
            own(configId, page, "tab_header_version_labels", c::isTabHeaderVersionLabels, c::setTabHeaderVersionLabels, true),
            own(configId, page, "tab_header_collapse_mode", MenuConfig.TabHeaderCollapseMode.values(), MenuConfig.TabHeaderCollapseMode::id,
                c::getTabHeaderCollapseMode, c::setTabHeaderCollapseMode, MenuConfig.DEFAULT_TAB_HEADER_COLLAPSE_MODE),
            own(configId, page, "tab_headers", c::isTabHeaders, c::setTabHeaders, true),
            own(configId, page, "collapse_single_page_groups", c::isCollapseSinglePageGroups, c::setCollapseSinglePageGroups, true),
            own(configId, page, "collapsible_groups", c::isCollapsibleGroups, c::setCollapsibleGroups, true),
            own(configId, page, "tooltip_delay", MenuConfig.MIN_TOOLTIP_DELAY_MS, MenuConfig.MAX_TOOLTIP_DELAY_MS, 100,
                "rso.options.value.milliseconds", c::getTooltipDelayMs, c::setTooltipDelayMs, MenuConfig.DEFAULT_TOOLTIP_DELAY_MS),
            own(configId, page, "tooltip_option_ids", c::isTooltipOptionIds, c::setTooltipOptionIds, false),
            own(configId, page, "color_themes", c::isColorThemes, c::setColorThemes, true),
            own(configId, page, "themed_headers_and_labels", c::isThemedHeadersAndLabels, c::setThemedHeadersAndLabels, true),
            own(configId, page, "themed_tooltip_borders", c::isThemedTooltipBorders, c::setThemedTooltipBorders, true),
            own(configId, page, "reduced_motion", c::isReducedMotion, c::setReducedMotion, false));
        List<MenuOption> behavior = List.of(
            own(configId, page, "reverse_cycling_controls", c::isReverseCyclingControls, c::setReverseCyclingControls, true),
            own(configId, page, "shift_scroll_slider_adjustments", c::isShiftScrollSliderAdjustments, c::setShiftScrollSliderAdjustments, true),
            own(configId, page, "search_result_limit", MenuConfig.MIN_SEARCH_RESULT_LIMIT, MenuConfig.MAX_SEARCH_RESULT_LIMIT, 1,
                "rso.options.value.results", c::getSearchResultLimit, c::setSearchResultLimit, MenuConfig.DEFAULT_SEARCH_RESULT_LIMIT),
            own(configId, page, "hide_non_matching_options", c::isHideNonMatchingOptions, c::setHideNonMatchingOptions, true),
            own(configId, page, "hide_non_matching_tabs", c::isHideNonMatchingTabs, c::setHideNonMatchingTabs, true),
            own(configId, page, "disabled_option_visibility", MenuConfig.DisabledOptionVisibility.values(), MenuConfig.DisabledOptionVisibility::id,
                c::getDisabledOptionVisibility, c::setDisabledOptionVisibility, MenuConfig.DEFAULT_DISABLED_OPTION_VISIBILITY),
            own(configId, page, "focus_border_mode", MenuConfig.FocusBorderMode.values(), MenuConfig.FocusBorderMode::id,
                c::getFocusBorderMode, c::setFocusBorderMode, MenuConfig.DEFAULT_FOCUS_BORDER_MODE),
            own(configId, page, "reset_button_overlay", c::isResetButtonOverlay, c::setResetButtonOverlay, true),
            own(configId, page, "undo_button_overlay", c::isUndoButtonOverlay, c::setUndoButtonOverlay, true),
            own(configId, page, "always_show_action_buttons", c::isAlwaysShowActionButtons, c::setAlwaysShowActionButtons, false));
        return new MenuPage(Component.literal(MENU_PAGE), List.of(
            new MenuSection(Component.translatable("rso.options.group.appearance"), appearance),
            new MenuSection(Component.translatable("rso.options.group.behavior"), behavior)));
    }

    private MenuToggle own(String configId, String page, String name, Supplier<Boolean> get, Consumer<Boolean> set, boolean fallback) {
        return remember(new MenuToggle(id(configId, page, name), ownName(name), ownTooltip(name), () -> true, get, set, fallback));
    }

    private <E> MenuChoice<E> own(String configId, String page, String name, E[] values, Function<E, String> valueId,
                                  Supplier<E> get, Consumer<E> set, E fallback) {
        return remember(new MenuChoice<>(id(configId, page, name), ownName(name), ownTooltip(name), () -> true, get, set, fallback,
            () -> List.of(values), value -> Component.translatable("rso.options." + name + ".value." + valueId.apply(value))));
    }

    private MenuSlider<Integer> own(String configId, String page, String name, int min, int max, int step, String format,
                                    Supplier<Integer> get, Consumer<Integer> set, int fallback) {
        IntFunction<Integer> at = position -> min + position * step;
        return remember(new MenuSlider<>(id(configId, page, name), ownName(name), ownTooltip(name), () -> true, get, set, fallback,
            (max - min) / step, value -> Math.round((value - min) / (float) step), at, value -> Component.translatable(format, value)));
    }

    private <O extends MenuSetting<?>> O remember(O setting) {
        settings.add(setting);
        ownSettings.add(setting);
        return setting;
    }

    private static Component ownName(String name) {
        return Component.translatable("rso.options." + name + ".name");
    }

    private static Component ownTooltip(String name) {
        return Component.translatable("rso.options." + name + ".tooltip");
    }
}
