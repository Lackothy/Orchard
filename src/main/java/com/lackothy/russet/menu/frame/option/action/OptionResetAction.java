// From Reese's Sodium Options 2.2.5 by FlashyReese (MIT, see licenses/reeses-sodium-options.txt), modified for Russet.
package com.lackothy.russet.menu.frame.option.action;

import com.lackothy.russet.menu.option.MenuSetting;

import com.lackothy.russet.menu.config.MenuConfig;
import net.minecraft.resources.Identifier;

import java.util.Objects;

public final class OptionResetAction {
    static final Identifier ICON = Identifier.fromNamespaceAndPath("russet", "textures/gui/menu/reset_to_default.png");

    public static boolean isVisible(MenuSetting<?> option) {
        return MenuConfig.config().isResetButtonOverlay()
                && (MenuConfig.config().isAlwaysShowActionButtons() || canReset(option));
    }

    public static boolean isActive(MenuSetting<?> option) {
        return MenuConfig.config().isResetButtonOverlay()
                && canReset(option);
    }

    public static boolean canReset(MenuSetting<?> option) {
        if (!option.isEnabled()) {
            return false;
        }

        Object fallback = option.getDefaultValue();

        return fallback != null
                && !Objects.equals(option.getValidatedValue(), fallback);
    }

    public static void resetToDefault(MenuSetting<?> option) {
        option.resetToDefault();
        OptionUndoAction.normalizeEquivalentChange(option);
    }
}
