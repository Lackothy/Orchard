// From Reese's Sodium Options 2.2.5 by FlashyReese (MIT, see licenses/reeses-sodium-options.txt), modified for Russet.
package com.lackothy.russet.menu.frame.option;

import com.lackothy.russet.menu.option.MenuOption;
import com.lackothy.russet.menu.option.MenuPage;
import com.lackothy.russet.menu.option.MenuSection;

import com.lackothy.russet.menu.option.OptionExtended;
import com.lackothy.russet.menu.state.SearchResultEntry;
import com.lackothy.russet.menu.state.SearchResultOrder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class PageLayout {
    static final int ROW_HEIGHT = 18;

    private static final int GROUP_PADDING = 4;

    private final List<Row> rows;
    private final int contentHeight;

    private PageLayout(List<Row> rows, int contentHeight) {
        this.rows = List.copyOf(rows);
        this.contentHeight = contentHeight;
    }

    static PageLayout create(MenuPage page, boolean searchActive, List<SearchResultEntry> results, SearchResultOrder resultOrder, boolean hideDisabledOptions, boolean collapsible, Set<Identifier> collapsedGroups) {
        if (searchActive) {
            return createSearchLayout(buildSearchEntries(page, results, resultOrder, hideDisabledOptions));
        }

        return createPageLayout(page, hideDisabledOptions, collapsible, collapsedGroups);
    }

    List<Row> rows() {
        return this.rows;
    }

    int contentHeight() {
        return this.contentHeight;
    }

    private static PageLayout createSearchLayout(List<SearchEntry> searchEntries) {
        List<Row> rows = new ArrayList<>();
        int y = 0;
        MenuSection lastGroup = null;

        for (SearchEntry entry : searchEntries) {
            MenuSection group = entry.group();

            if (group != lastGroup) {
                if (lastGroup != null) {
                    y += GROUP_PADDING;
                }

                if (hasLabel(group)) {
                    int labelY = y + GROUP_PADDING;
                    rows.add(new LabelRow(group, group.name(), labelY, null, false));
                    y = labelY + ROW_HEIGHT;
                }

                lastGroup = group;
            }

            rows.add(new OptionRow(group, entry.option(), y));
            y += ROW_HEIGHT;
        }

        return new PageLayout(rows, y);
    }

    private static PageLayout createPageLayout(MenuPage page, boolean hideDisabledOptions, boolean collapsible, Set<Identifier> collapsedGroups) {
        List<Row> rows = new ArrayList<>();
        List<VisibleGroup> groups = visibleGroups(page.groups(), hideDisabledOptions);
        int y = 0;

        for (int i = 0; i < groups.size(); i++) {
            VisibleGroup visibleGroup = groups.get(i);
            MenuSection group = visibleGroup.group();

            Identifier collapseKey = collapsible ? groupCollapseKey(group) : null;
            boolean collapsed = collapseKey != null && collapsedGroups.contains(collapseKey);

            if (hasLabel(group)) {
                int labelY = y + (i == 0 ? 0 : GROUP_PADDING);
                rows.add(new LabelRow(group, group.name(), labelY, collapseKey, collapsed));
                y = labelY + ROW_HEIGHT;
            }

            if (!collapsed) {
                for (MenuOption option : visibleGroup.options()) {
                    rows.add(new OptionRow(group, option, y));
                    y += ROW_HEIGHT;
                }
            }

            if (i < groups.size() - 1) {
                y += GROUP_PADDING;
            }
        }

        return new PageLayout(rows, y);
    }

    private static List<VisibleGroup> visibleGroups(List<MenuSection> groups, boolean hideDisabledOptions) {
        if (!hideDisabledOptions) {
            return groups.stream()
                    .map(group -> new VisibleGroup(group, group.options()))
                    .toList();
        }

        List<VisibleGroup> visibleGroups = new ArrayList<>();
        for (MenuSection group : groups) {
            List<MenuOption> options = group.options()
                    .stream()
                    .filter(MenuOption::isEnabled)
                    .toList();

            if (!options.isEmpty()) {
                visibleGroups.add(new VisibleGroup(group, options));
            }
        }

        return visibleGroups;
    }

    private static @Nullable Identifier groupCollapseKey(MenuSection group) {
        for (MenuOption option : group.options()) {
            if (option instanceof OptionExtended optionExtended) {
                return optionExtended.rso$getId();
            }
        }

        return null;
    }

    private static boolean hasLabel(MenuSection group) {
        return group.name() != null && !group.name().getString().isEmpty();
    }

    private static List<SearchEntry> buildSearchEntries(MenuPage page, List<SearchResultEntry> results, SearchResultOrder resultOrder, boolean hideDisabledOptions) {
        if (results.isEmpty()) {
            return List.of();
        }

        return switch (resultOrder) {
            case PAGE_DISPLAY -> buildSearchEntriesInPageOrder(page, results, hideDisabledOptions);
            case RANKED -> buildSearchEntriesInResultOrder(page, results, hideDisabledOptions);
        };
    }

    private static List<SearchEntry> buildSearchEntriesInPageOrder(MenuPage page, List<SearchResultEntry> results, boolean hideDisabledOptions) {
        Set<MenuOption> resultOptions = Collections.newSetFromMap(new IdentityHashMap<>());
        results.forEach(result -> resultOptions.add(result.option()));

        List<SearchEntry> entries = new ArrayList<>();
        for (MenuSection group : page.groups()) {
            for (MenuOption option : group.options()) {
                if (resultOptions.contains(option) && shouldShowOption(option, hideDisabledOptions)) {
                    entries.add(new SearchEntry(group, option));
                }
            }
        }

        return entries;
    }

    private static List<SearchEntry> buildSearchEntriesInResultOrder(MenuPage page, List<SearchResultEntry> results, boolean hideDisabledOptions) {
        Map<MenuOption, SearchEntry> entriesByOption = new IdentityHashMap<>();
        for (MenuSection group : page.groups()) {
            for (MenuOption option : group.options()) {
                if (shouldShowOption(option, hideDisabledOptions)) {
                    entriesByOption.put(option, new SearchEntry(group, option));
                }
            }
        }

        List<SearchEntry> ordered = new ArrayList<>(results.size());
        for (SearchResultEntry result : results) {
            SearchEntry entry = entriesByOption.get(result.option());
            if (entry != null) {
                ordered.add(entry);
            }
        }

        return ordered;
    }

    private static boolean shouldShowOption(MenuOption option, boolean hideDisabledOptions) {
        return !hideDisabledOptions || option.isEnabled();
    }

    interface Row {
        int y();
    }

    record LabelRow(MenuSection group, Component text, int y, @Nullable Identifier collapseKey, boolean collapsed) implements Row {
        boolean collapsible() {
            return this.collapseKey != null;
        }
    }

    record OptionRow(MenuSection group, MenuOption option, int y) implements Row {
    }

    private record SearchEntry(MenuSection group, MenuOption option) {
    }

    private record VisibleGroup(MenuSection group, List<MenuOption> options) {
    }
}
