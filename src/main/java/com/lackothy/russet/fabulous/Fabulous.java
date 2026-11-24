package com.lackothy.russet.fabulous;

import com.lackothy.russet.video.VideoOptions;
import net.minecraft.client.GraphicsPreset;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;

import java.util.List;

public final class Fabulous {

    public static final List<VideoOptions.Toggle> EXTRAS = List.of(VideoOptions.EFFECT_PARTICLES, VideoOptions.WEATHER_PARTICLES,
        VideoOptions.CAVE_DUST, VideoOptions.DYNAMIC_LIGHTS);

    private Fabulous() {
    }

    public static void presetApplied(GraphicsPreset preset) {
        if (preset == GraphicsPreset.CUSTOM) {
            return;
        }
        VideoOptions.ensureLoaded();
        for (VideoOptions.Toggle extra : EXTRAS) {
            extra.set(preset == GraphicsPreset.FABULOUS);
        }
    }

    public static void setByHand(VideoOptions.Toggle extra, boolean on) {
        extra.set(on);
        OptionInstance<GraphicsPreset> preset = Minecraft.getInstance().options.graphicsPreset();
        boolean fabulous = EXTRAS.stream().allMatch(VideoOptions.Toggle::on);
        boolean none = EXTRAS.stream().noneMatch(VideoOptions.Toggle::on);
        GraphicsPreset now = preset.get();
        if ((now == GraphicsPreset.FABULOUS && !fabulous) || ((now == GraphicsPreset.FAST || now == GraphicsPreset.FANCY) && !none)) {
            preset.set(GraphicsPreset.CUSTOM);
        }
    }
}
