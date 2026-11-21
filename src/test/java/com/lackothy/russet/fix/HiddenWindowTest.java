package com.lackothy.russet.fix;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.lwjgl.sdl.SDLVideo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HiddenWindowTest {

    @Test
    @DisplayName("an occluded, hidden or minimized window is hidden, a focused full screen one isn't")
    void hidden() {
        assertTrue(HiddenWindow.hidden(SDLVideo.SDL_WINDOW_OCCLUDED | SDLVideo.SDL_WINDOW_FULLSCREEN));
        assertTrue(HiddenWindow.hidden(SDLVideo.SDL_WINDOW_HIDDEN));
        assertTrue(HiddenWindow.hidden(SDLVideo.SDL_WINDOW_MINIMIZED));
        assertFalse(HiddenWindow.hidden(SDLVideo.SDL_WINDOW_FULLSCREEN | SDLVideo.SDL_WINDOW_INPUT_FOCUS));
        assertFalse(HiddenWindow.hidden(0));
    }

    @Test
    @DisplayName("hidden, the limit drops to 10 and a lower one stays; showing, it is left alone")
    void limit() {
        assertEquals(10, HiddenWindow.limit(60, true));
        assertEquals(10, HiddenWindow.limit(260, true));
        assertEquals(5, HiddenWindow.limit(5, true));
        assertEquals(60, HiddenWindow.limit(60, false));
    }
}
