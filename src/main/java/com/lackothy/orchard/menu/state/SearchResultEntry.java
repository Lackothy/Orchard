// From Reese's Sodium Options 2.2.5 by FlashyReese (MIT, see licenses/reeses-sodium-options.txt), modified for Orchard.
package com.lackothy.orchard.menu.state;

import com.lackothy.orchard.menu.option.MenuOption;

import net.minecraft.resources.Identifier;

public record SearchResultEntry(String tabKey, Identifier optionId, MenuOption option) {
}
