// From Reese's Sodium Options 2.2.5 by FlashyReese (MIT, see licenses/reeses-sodium-options.txt), modified for Russet.
package com.lackothy.russet.menu.search;

import com.lackothy.russet.menu.option.MenuGroup;
import com.lackothy.russet.menu.option.MenuOption;
import com.lackothy.russet.menu.option.MenuPage;

import com.lackothy.russet.menu.config.MenuConfig;
import com.lackothy.russet.menu.option.OptionExtended;
import com.lackothy.russet.menu.layout.LayoutBounds;
import com.lackothy.russet.menu.state.OptionLayoutState;
import com.lackothy.russet.menu.state.OptionStateStore;
import com.lackothy.russet.menu.state.OptionUiState;
import com.lackothy.russet.menu.state.SearchResultEntry;
import com.lackothy.russet.menu.state.SearchResultOrder;
import com.lackothy.russet.menu.search.index.SearchIndex;
import com.lackothy.russet.menu.search.index.SearchResult;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

final class OptionSearch {
    private final List<SearchableOption> options;
    private final SearchIndex<SearchableOption> searchIndex;

    OptionSearch(List<MenuGroup> modOptionsList) {
        List<SearchableOption> options = new ArrayList<>();

        for (MenuGroup modOptions : modOptionsList) {
            for (MenuPage page : modOptions.pages()) {
                String tabKey = modOptions.configId() + ":" + page.name().getString();
                for (var group : page.groups()) {
                    for (var option : group.options()) {
                        if (!(option instanceof OptionExtended optionExtended)) {
                            continue;
                        }

                        options.add(new SearchableOption(
                                optionExtended.rso$getId(),
                                tabKey,
                                option,
                                String.format("%s %s", option.getName().getString(), option.getTooltip().getString())));
                    }
                }
            }
        }

        this.options = List.copyOf(options);
        this.searchIndex = SearchIndex.builder(SearchableOption::searchableText)
                .addAll(this.options)
                .foldDiacritics(true)
                .maxResults(MenuConfig.config().getSearchResultLimit())
                .minScore(0.3)
                .rerankWithEditDistance(true)
                .rerankLimit(50)
                .rerankWeight(0.1)
                .build();
    }

    List<SearchResultEntry> query(String query) {
        return this.searchIndex.newSession(query)
                .results()
                .stream()
                .map(SearchResult::item)
                .map(SearchableOption::toSearchResult)
                .toList();
    }

    List<NavigationTarget> navigationTargets(OptionStateStore optionStateStore, SearchResultOrder order) {
        List<SearchResultEntry> orderedResults = this.orderResults(optionStateStore.searchResults(), order);

        List<NavigationTarget> targets = new ArrayList<>(orderedResults.size());
        for (SearchResultEntry result : orderedResults) {
            NavigationTarget target = this.createNavigationTarget(result, optionStateStore);
            if (target != null) {
                targets.add(target);
            }
        }

        return targets;
    }

    private List<SearchResultEntry> orderResults(List<SearchResultEntry> results, SearchResultOrder order) {
        if (results.isEmpty()) {
            return List.of();
        }

        if (order == SearchResultOrder.RANKED) {
            return results;
        }

        Set<MenuOption> resultOptions = Collections.newSetFromMap(new IdentityHashMap<>());
        results.forEach(result -> resultOptions.add(result.option()));

        List<SearchResultEntry> ordered = new ArrayList<>(results.size());
        for (SearchableOption option : this.options) {
            if (resultOptions.contains(option.option())) {
                ordered.add(option.toSearchResult());
            }
        }

        return ordered;
    }

    private @Nullable NavigationTarget createNavigationTarget(SearchResultEntry result, OptionStateStore optionStateStore) {
        OptionUiState optionUiState = optionStateStore.optionUiState(result.optionId());
        OptionLayoutState optionLayoutState = optionStateStore.optionLayoutState(result.optionId());
        LayoutBounds bounds = optionLayoutState.bounds();
        LayoutBounds parentBounds = optionLayoutState.parentBounds();

        if (!optionUiState.isHighlighted() || parentBounds == null || bounds == null) {
            return null;
        }

        return new NavigationTarget(result.tabKey(), optionUiState, bounds, parentBounds);
    }

    private record SearchableOption(Identifier id, String tabKey, MenuOption option, String searchableText) {
        SearchResultEntry toSearchResult() {
            return new SearchResultEntry(this.tabKey, this.id, this.option);
        }
    }

    record NavigationTarget(String tabKey, OptionUiState optionUiState, LayoutBounds bounds, LayoutBounds parentBounds) {
        int scrollOffset(int viewportHeight) {
            int contentHeight = this.parentBounds.height();
            if (contentHeight <= 0 || contentHeight <= viewportHeight) {
                return 0;
            }

            int maxOffset = contentHeight - viewportHeight;
            int input = this.bounds.y() - this.parentBounds.y();
            int inputOffset = input + this.bounds.height() == contentHeight ? contentHeight : input;

            return (int) ((long) inputOffset * maxOffset / contentHeight);
        }
    }
}
