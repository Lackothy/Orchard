// From Reese's Sodium Options 2.2.5 by FlashyReese (MIT, see licenses/reeses-sodium-options.txt), modified for Russet.
package com.lackothy.russet.menu.state;

import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Set;

public interface OptionStateStore {
    OptionUiState optionUiState(Identifier id);

    OptionLayoutState optionLayoutState(Identifier id);

    boolean searchActive();

    List<SearchResultEntry> searchResults();

    Set<Identifier> collapsedOptionGroups();
}
