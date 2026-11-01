package com.lackothy.orchard.menu.option;

import com.lackothy.orchard.video.Pages;
import net.minecraft.resources.Identifier;

import java.util.List;

public record MenuGroup(String configId, String name, String version, Pages.Theme theme, Identifier icon,
                        List<MenuPage> pages) {

    public boolean iconMonochrome() {
        return false;
    }
}
