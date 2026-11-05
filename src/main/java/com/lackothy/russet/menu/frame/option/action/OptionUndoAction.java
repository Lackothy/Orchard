// From Reese's Sodium Options 2.2.5 by FlashyReese (MIT, see licenses/reeses-sodium-options.txt), modified for Russet.
package com.lackothy.russet.menu.frame.option.action;

import com.lackothy.russet.menu.option.MenuSetting;

import com.lackothy.russet.menu.config.MenuConfig;
import net.minecraft.resources.Identifier;

import java.util.Objects;

public final class OptionUndoAction {
    static final Identifier ICON = Identifier.fromNamespaceAndPath("russet", "textures/gui/menu/undo_to_unmodified.png");

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
