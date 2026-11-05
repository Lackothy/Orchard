package com.lackothy.russet.video;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RendererSettingsTest {

    @Test
    @DisplayName("no preset in the file is the default, not a crash on the Metal page")
    void noPresetSaved() {
        assertEquals("balanced", RendererSettings.shaderPreset(null));
    }

    @Test
    @DisplayName("a preset in the file is kept, and one it doesn't know is the default")
    void presetSaved() {
        assertEquals("ultra", RendererSettings.shaderPreset("ultra"));
        assertEquals("balanced", RendererSettings.shaderPreset("fancy"));
    }
}
