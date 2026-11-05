package com.lackothy.russet.video;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ControlTest {

    private static String value(String caption, String message) {
        return Control.valueOnly(Component.literal(caption), Component.literal(message)).getString();
    }

    @Test
    @DisplayName("a row's value leaves out the name Minecraft puts before it")
    void valueWithoutName() {
        assertEquals("5 Chunks", value("Render Distance", "Render Distance: 5 Chunks"));
        assertEquals("2 second(s)", value("Chat Delay", "Chat Delay: 2 second(s)"));
    }

    @Test
    @DisplayName("and the shorter name some of them use, as Chunk Fade Time does")
    void valueWithoutShortName() {
        assertEquals("None", value("Chunk Fade Time", "Chunk Fade: None"));
        assertEquals("0.75 second(s)", value("Chunk Fade Time", "Chunk Fade: 0.75 second(s)"));
    }

    @Test
    @DisplayName("a value without its name stays as it is")
    void plainValue() {
        assertEquals("5x5 (Normal)", value("Biome Blend", "5x5 (Normal)"));
        assertEquals("Fast: smooth", value("Preset", "Fast: smooth"));
    }
}
