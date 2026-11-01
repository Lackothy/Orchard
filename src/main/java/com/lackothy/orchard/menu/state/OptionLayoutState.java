// From Reese's Sodium Options 2.2.5 by FlashyReese (MIT, see licenses/reeses-sodium-options.txt), modified for Orchard.
package com.lackothy.orchard.menu.state;

import com.lackothy.orchard.menu.layout.LayoutBounds;
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
