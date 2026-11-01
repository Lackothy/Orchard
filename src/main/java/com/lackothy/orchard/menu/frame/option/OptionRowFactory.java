// From Reese's Sodium Options 2.2.5 by FlashyReese (MIT, see licenses/reeses-sodium-options.txt), modified for Orchard.
package com.lackothy.orchard.menu.frame.option;

import com.lackothy.orchard.menu.option.MenuButton;
import com.lackothy.orchard.menu.option.MenuFact;
import com.lackothy.orchard.menu.option.MenuChoice;
import com.lackothy.orchard.menu.option.MenuOption;
import com.lackothy.orchard.menu.option.MenuSlider;
import com.lackothy.orchard.menu.option.MenuToggle;

import com.lackothy.orchard.menu.layout.LayoutBounds;
import com.lackothy.orchard.menu.option.OptionExtended;
import com.lackothy.orchard.menu.state.OptionStateStore;
import com.lackothy.orchard.menu.theme.GuiTheme;
import net.minecraft.client.gui.screens.Screen;

final class OptionRowFactory {
    private final Screen screen;
    private final GuiTheme theme;
    private final OptionStateStore optionStateStore;

    OptionRowFactory(Screen screen, GuiTheme theme, OptionStateStore optionStateStore) {
        this.screen = screen;
        this.theme = theme;
        this.optionStateStore = optionStateStore;
    }

    OptionRow create(MenuOption option, LayoutBounds dim) {
        OptionRow element = this.createOptionRow(option, dim);

        this.registerOptionBounds(element, dim);

        return element;
    }

    void registerParentBounds(PageLayout layout, LayoutBounds parentDim) {
        for (PageLayout.Row row : layout.rows()) {
            if (row instanceof PageLayout.OptionRow optionRow && optionRow.option() instanceof OptionExtended optionExtended) {
                this.optionStateStore.optionLayoutState(optionExtended.rso$getId())
                        .setParentBounds(parentDim);
            }
        }
    }

    void registerOptionBounds(OptionRow element, LayoutBounds dim) {
        if (element.getOption() instanceof OptionExtended optionExtended) {
            this.optionStateStore.optionLayoutState(optionExtended.rso$getId())
                    .setBounds(dim);
        }
    }

    private OptionRow createOptionRow(MenuOption option, LayoutBounds dim) {
        return switch (option) {
            case MenuToggle toggle -> new BooleanOptionRow(dim, this.theme, this.optionStateStore, toggle);
            case MenuSlider<?> slider -> new IntegerSliderOptionRow<>(dim, this.theme, this.optionStateStore, slider);
            case MenuChoice<?> choice -> new EnumOptionRow<>(dim, this.theme, this.optionStateStore, choice);
            case MenuButton button -> new ExternalButtonOptionRow(this.screen, dim, this.theme, this.optionStateStore, button);
            case MenuFact fact -> new FactOptionRow(dim, this.theme, this.optionStateStore, fact);
            default -> new UnsupportedOptionRow(dim, this.theme, this.optionStateStore, option);
        };
    }
}
