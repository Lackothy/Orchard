package com.lackothy.russet.video;

import com.lackothy.Russet;
import com.lackothy.russet.mixin.LevelRendererAccess;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.renderpearl.api.GpuFormat;
import mcopt.metal.Switch;
import mcopt.metal.shade.Shade;
import mcopt.metal.Upscaler;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

public final class RenderScale {

    public record Targets(RenderTarget main, RenderTarget hand) {
    }

    private static TextureTarget world;
    private static TextureTarget hand;
    private static boolean broken;

    private RenderScale() {
    }

    public static @Nullable Targets begin(RenderTarget full) {
        RenderTarget outline = ((LevelRendererAccess) Minecraft.getInstance().levelRenderer).russet$entityOutlineTarget();
        int percent = Switch.ON && !broken && !Shade.ENABLED ? VideoOptions.RENDER_SCALE.get().percent : 100;
        if (percent >= 100 || full.width < 2 || full.height < 2) {
            release();
            fit(outline, full.width, full.height);
            return null;
        }
        int w = Math.max(1, Math.round(full.width * percent / 100f));
        int h = Math.max(1, Math.round(full.height * percent / 100f));
        if (world == null) {
            world = new TextureTarget("Russet world", w, h, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
            hand = new TextureTarget("Russet hand depth", w, h, null, GpuFormat.D32_FLOAT);
        }
        fit(world, w, h);
        fit(hand, w, h);
        if (!Upscaler.ready(world.getColorTexture(), full.getColorTexture())) {
            Russet.LOG.warn("Render scale is off: MetalFX can't take {}x{} up to {}x{} here", w, h, full.width, full.height);
            broken = true;
            release();
            fit(outline, full.width, full.height);
            return null;
        }
        fit(outline, w, h);
        return new Targets(world, hand);
    }

    public static void end(RenderTarget from, RenderTarget full) {
        Upscaler.upscale(from.getColorTexture(), full.getColorTexture());
    }

    private static void fit(RenderTarget target, int w, int h) {
        if (target.width != w || target.height != h) {
            target.resize(w, h);
        }
    }

    private static void release() {
        if (world != null) {
            world.destroyBuffers();
            hand.destroyBuffers();
            world = null;
            hand = null;
        }
    }
}
