// From Reese's Sodium Options 2.2.5 by FlashyReese (MIT, see licenses/reeses-sodium-options.txt), modified for Russet.
package com.lackothy.russet.menu.frame.option;

import com.lackothy.russet.menu.option.MenuOption;

import com.lackothy.russet.menu.layout.LayoutBounds;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;

public interface OptionRow extends Renderable, GuiEventListener, NarratableEntry {
    MenuOption getOption();

    LayoutBounds getDimensions();

    void releaseActionButtonLayoutHold();

    boolean handleBackNavigation();

    boolean undoFocusedActionButton();

    void clearActionButtonFocus();
}
