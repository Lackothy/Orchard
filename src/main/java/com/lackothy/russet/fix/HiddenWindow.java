package com.lackothy.russet.fix;

import com.lackothy.Russet;
import com.lackothy.russet.video.VideoOptions;
import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLVideo;

public final class HiddenWindow {

    static final int LIMIT = 10;
    private static final long HIDDEN = SDLVideo.SDL_WINDOW_OCCLUDED | SDLVideo.SDL_WINDOW_HIDDEN
        | SDLVideo.SDL_WINDOW_MINIMIZED;

    private static boolean wasHidden;

    private HiddenWindow() {
    }

    public static int limit(int limit) {
        if (!VideoOptions.FIX_HIDDEN_WINDOW.on()) {
            return limit;
        }
        boolean hidden = hidden(SDLVideo.SDL_GetWindowFlags(Minecraft.getInstance().getWindow().handle()));
        if (hidden != wasHidden) {
            wasHidden = hidden;
            if (hidden) {
                Russet.LOG.info("The window is hidden: drawing at most {} frames a second", LIMIT);
            } else {
                Russet.LOG.info("The window is showing again");
            }
        }
        return limit(limit, hidden);
    }

    static boolean hidden(long flags) {
        return (flags & HIDDEN) != 0;
    }

    static int limit(int limit, boolean hidden) {
        return hidden ? Math.min(limit, LIMIT) : limit;
    }
}
