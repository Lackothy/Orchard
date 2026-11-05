// From Reese's Sodium Options 2.2.5 by FlashyReese (MIT, see licenses/reeses-sodium-options.txt), modified for Russet.
package com.lackothy.russet.menu.theme;

import com.lackothy.russet.video.Pages;

public final class GuiThemes {
    public static final GuiTheme DEFAULT_BUTTON = new GuiTheme(0xFFFFFFFF, 0xFFFFFFFF, 0xFFAAAAAA, 0xE0000000, 0x90000000, 0x40000000);
    public static final int FLAT_BUTTON_FOCUS_BORDER = 0x8000FFEE;
    public static final int OPTION_FOCUS_BORDER = 0xFFFFFFFF;
    public static final int SELECTED_UNDERLINE = 0xFF94E4D3;

    public static GuiTheme fromTheme(Pages.Theme theme) {
        return new GuiTheme(
                theme.theme(),
                theme.lighter(),
                theme.darker(),
                DEFAULT_BUTTON.bgHighlight,
                DEFAULT_BUTTON.bgDefault,
                DEFAULT_BUTTON.bgInactive
        );
    }
}
