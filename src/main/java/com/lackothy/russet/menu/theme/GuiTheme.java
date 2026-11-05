// From Reese's Sodium Options 2.2.5 by FlashyReese (MIT, see licenses/reeses-sodium-options.txt), modified for Russet.
package com.lackothy.russet.menu.theme;

public final class GuiTheme {
    public final int theme;
    public final int themeLighter;
    public final int themeDarker;
    public final int bgHighlight;
    public final int bgDefault;
    public final int bgInactive;

    public GuiTheme(int theme, int themeLighter, int themeDarker, int bgHighlight, int bgDefault, int bgInactive) {
        this.theme = theme;
        this.themeLighter = themeLighter;
        this.themeDarker = themeDarker;
        this.bgHighlight = bgHighlight;
        this.bgDefault = bgDefault;
        this.bgInactive = bgInactive;
    }
}
