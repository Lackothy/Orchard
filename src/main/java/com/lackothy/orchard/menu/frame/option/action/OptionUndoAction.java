// From Reese's Sodium Options 2.2.5 by FlashyReese (MIT, see licenses/reeses-sodium-options.txt), modified for Orchard.
package com.lackothy.orchard.menu.frame.option.action;

import com.lackothy.orchard.menu.option.MenuSetting;

import com.lackothy.orchard.menu.config.MenuConfig;
import net.minecraft.resources.Identifier;

import java.util.Objects;

public final class OptionUndoAction {
    static final Identifier ICON = Identifier.fromNamespaceAndPath("orchard", "textures/gui/menu/undo_to_unmodified.png");

    public static boolean isVisible(MenuSetting<?> option) {
        return MenuConfig.config().isUndoButtonOverlay()
                && (MenuConfig.config().isAlwaysShowActionButtons() || canUndo(option));
    }

    public static boolean isActive(MenuSetting<?> option) {
        return MenuConfig.config().isUndoButtonOverlay()
                && canUndo(option);
    }

    public static boolean canUndo(MenuSetting<?> option) {
        return option.isEnabled()
                && option.hasChanged()
                && !Objects.equals(option.getValidatedValue(), option.getAppliedValue());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void undoChanges(MenuSetting<?> option) {
        ((MenuSetting) option).modifyValue(option.getAppliedValue());
    }

    public static void normalizeEquivalentChange(MenuSetting<?> option) {
        if (option.hasChanged() && Objects.equals(option.getValidatedValue(), option.getAppliedValue())) {
            undoChanges(option);
        }
    }

}
