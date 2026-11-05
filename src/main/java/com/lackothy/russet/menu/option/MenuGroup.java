package com.lackothy.russet.menu.option;

import com.lackothy.russet.video.Pages;
import net.minecraft.resources.Identifier;

import java.util.List;

public record MenuGroup(String configId, String name, String version, Pages.Theme theme, Identifier icon,
                        List<MenuPage> pages) {

    public boolean iconMonochrome() {
        return false;
    }
}
