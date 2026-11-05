// From Reese's Sodium Options 2.2.5 by FlashyReese (MIT, see licenses/reeses-sodium-options.txt), modified for Russet.
package com.lackothy.russet.menu.state;

import com.lackothy.russet.menu.layout.LayoutBounds;
import org.jetbrains.annotations.Nullable;

public final class OptionLayoutState {
    private @Nullable LayoutBounds bounds;
    private @Nullable LayoutBounds parentBounds;

    public @Nullable LayoutBounds bounds() {
        return this.bounds;
    }

    public void setBounds(@Nullable LayoutBounds bounds) {
        this.bounds = bounds;
    }

    public @Nullable LayoutBounds parentBounds() {
        return this.parentBounds;
    }

    public void setParentBounds(@Nullable LayoutBounds parentBounds) {
        this.parentBounds = parentBounds;
    }
}
