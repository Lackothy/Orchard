package com.lackothy.russet.fix;

import com.lackothy.russet.video.VideoOptions;

import java.util.List;

public final class Fixes {

    public record Fix(String name, String description, VideoOptions.Toggle toggle) {
    }

    public static final List<Fix> ALL = List.of(
        new Fix("Slow down when hidden", "Minecraft only slows down while its window is minimized, so a window you "
            + "can't see, such as full screen on another desktop, behind other windows or in a hidden app, drew every "
            + "frame for nothing. With this on it draws 10 frames a second until it shows again, as a minimized one does.",
            VideoOptions.FIX_HIDDEN_WINDOW));

    private Fixes() {
    }
}
