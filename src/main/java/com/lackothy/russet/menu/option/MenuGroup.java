package com.lackothy.russet.menu.option;

import com.lackothy.russet.video.Pages;
import net.minecraft.resources.Identifier;

import java.util.List;

/** A heading down the side with its pages, as each mod's are under its name in Reese's Sodium Options. */
public record MenuGroup(String configId, String name, String version, Pages.Theme theme, Identifier icon,
                        List<MenuPage> pages) {

    /** Russet's icons are Minecraft's own textures, drawn in their colours. */
    public boolean iconMonochrome() {
        return false;
    }
}
