// From Reese's Sodium Options 2.2.5 by FlashyReese (MIT, see licenses/reeses-sodium-options.txt), modified for Russet.
package com.lackothy.russet.menu.control;

import net.minecraft.network.chat.Component;

public record ControlGuide(Input input, Component label) {
    public static ControlGuide press(Component label) {
        return new ControlGuide(Input.PRESS, label);
    }

    public static ControlGuide navigationLeftRight(Component label) {
        return new ControlGuide(Input.NAVIGATION_LEFT_RIGHT, label);
    }

    public static ControlGuide previousTab(Component label) {
        return new ControlGuide(Input.PREVIOUS_TAB, label);
    }

    public static ControlGuide nextTab(Component label) {
        return new ControlGuide(Input.NEXT_TAB, label);
    }

    public enum Input {
        PRESS,
        NAVIGATION_LEFT_RIGHT,
        PREVIOUS_TAB,
        NEXT_TAB
    }
}
