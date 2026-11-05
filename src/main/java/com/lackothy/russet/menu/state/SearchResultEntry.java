// From Reese's Sodium Options 2.2.5 by FlashyReese (MIT, see licenses/reeses-sodium-options.txt), modified for Russet.
package com.lackothy.russet.menu.state;

import com.lackothy.russet.menu.option.MenuOption;

import net.minecraft.resources.Identifier;

public record SearchResultEntry(String tabKey, Identifier optionId, MenuOption option) {
}
