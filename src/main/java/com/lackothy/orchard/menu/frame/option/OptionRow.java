// From Reese's Sodium Options 2.2.5 by FlashyReese (MIT, see licenses/reeses-sodium-options.txt), modified for Orchard.
package com.lackothy.orchard.menu.frame.option;

import com.lackothy.orchard.menu.option.MenuOption;

import com.lackothy.orchard.menu.layout.LayoutBounds;
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
