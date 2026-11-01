package com.lackothy.orchard.video;

import com.lackothy.Orchard;
import com.lackothy.orchard.mixin.LevelRendererAccess;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.renderpearl.api.GpuFormat;
import mcopt.metal.Switch;
import mcopt.metal.shade.Shade;
import mcopt.metal.Upscaler;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * The Metal page's render scale: the world is drawn into targets smaller than the window, and
 * MetalFX's spatial scaler takes it up to the window's size before the HUD and menus are drawn
 * over it, so they stay sharp. GameRendererMixin swaps the smaller targets in for Minecraft's main
 * one and the hand's depth while the world draws (mcopt reads the size from the main target, so
 * its terrain follows); the glowing outline's target is kept the world's size too.
 */
public final class RenderScale {

    /** The world's targets this frame: its colour and depth, and the hand's depth. */
    public record Targets(RenderTarget main, RenderTarget hand) {
    }

    private static TextureTarget world;
    private static TextureTarget hand;
    private static boolean broken;

    private RenderScale() {
    }

    /** Smaller targets to draw this frame's world into, or null to draw it at the window's size. */
    public static @Nullable Targets begin(RenderTarget full) {
        RenderTarget outline = ((LevelRendererAccess) Minecraft.getInstance().levelRenderer).orchard$entityOutlineTarget();
        // (the shaders draw the world at a resolution of their own, and scale it up themselves)
        int percent = Switch.ON && !broken && !Shade.ENABLED ? VideoOptions.RENDER_SCALE.get().percent : 100;
        if (percent >= 100 || full.width < 2 || full.height < 2) {
            release();
            fit(outline, full.width, full.height);
            return null;
        }
        int w = Math.max(1, Math.round(full.width * percent / 100f));
        int h = Math.max(1, Math.round(full.height * percent / 100f));
        if (world == null) {
            world = new TextureTarget("Orchard world", w, h, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
            hand = new TextureTarget("Orchard hand depth", w, h, null, GpuFormat.D32_FLOAT);
        }
        fit(world, w, h);
        fit(hand, w, h);
        if (!Upscaler.ready(world.getColorTexture(), full.getColorTexture())) {
            Orchard.LOG.warn("Render scale is off: MetalFX can't take {}x{} up to {}x{} here", w, h, full.width, full.height);
            broken = true;
            release();
            fit(outline, full.width, full.height);
            return null;
        }
        fit(outline, w, h);
        return new Targets(world, hand);
    }

    /** Takes the world drawn into {@code from} (begin's main) up into the window's {@code full} target. */
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
