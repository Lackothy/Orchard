// From Reese's Sodium Options 2.2.5 by FlashyReese (MIT, see licenses/reeses-sodium-options.txt), modified for Orchard.
package com.lackothy.orchard.menu.frame.option;

import com.lackothy.orchard.menu.option.MenuOption;

import com.lackothy.orchard.menu.layout.LayoutBounds;
import com.lackothy.orchard.menu.state.OptionStateStore;
import com.lackothy.orchard.menu.theme.GuiTheme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fallback row for option types this addon doesn't recognize (e.g. a custom option type added by
 * another mod that patches Sodium's config structure). Renders just the option's name with no
 * interactive control, rather than throwing and taking down the whole video options screen -- one
 * unrecognized option type shouldn't be able to crash every other mod's settings page.
 */
final class UnsupportedOptionRow extends AbstractOptionRow {
    private static final Logger LOGGER = LoggerFactory.getLogger("Reese's Sodium Options");
    private static final Set<Class<?>> WARNED_TYPES = Collections.newSetFromMap(new ConcurrentHashMap<>());

    UnsupportedOptionRow(LayoutBounds dim, GuiTheme theme, OptionStateStore optionStateStore, MenuOption option) {
        super(dim, theme, optionStateStore, option);

        if (WARNED_TYPES.add(option.getClass())) {
            LOGGER.warn("No option row registered for option type {}; rendering it as unsupported instead of crashing the options screen", option.getClass().getName());
        }
    }

    @Override
    protected int controlContentWidth() {
        return 0;
    }

    @Override
    protected void renderControl(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float delta) {
    }

    @Override
    protected boolean activateControl() {
        return false;
    }
}
