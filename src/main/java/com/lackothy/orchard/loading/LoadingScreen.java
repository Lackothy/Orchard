package com.lackothy.orchard.loading;

import com.lackothy.orchard.mixin.TitleScreenAccess;
import com.lackothy.orchard.ui.OrchardScene;
import com.lackothy.orchard.video.VideoOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.lwjgl.system.MemoryUtil;

/**
 * Orchard's loading screen, in place of Mojang's while Minecraft starts and whenever it reloads
 * its resources: the orchard, dimmed, with the sun or moon showing how far loading has got
 * ({@link Loader}), then an {@link Ending} into the menu once it is done.
 *
 * <p>Minecraft's {@code LoadingOverlay} still decides when loading is over and calls what waits
 * for it; this only draws, and says when its ending has finished so the overlay can go. It is
 * painted in memory and drawn as one texture, because nothing of Orchard's that needs a shader
 * exists before the first load is over.
 */
public final class LoadingScreen {

    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("orchard", "dynamic/loading");
    /** The pause on a full bar before the ending. */
    private static final double HOLD = 0.3;
    /** Pixels the picture is painted at, at most; a bigger GUI is painted coarser and scaled up. */
    private static final double MOST_PIXELS = 400_000;

    private final boolean fadeIn;
    private final long start = Util.getMillis();
    private final Loader loader = VideoOptions.LOADER.get();
    private final Ending ending;
    private final Sky sky = Sky.now();
    private final Frame.Memory memory = new Frame.Memory();
    private double shown;
    private double last;
    private double loadedAt = -1;
    private double fullAt = -1;
    private boolean titleFading;

    private Pixels layer;
    private Pixels out;
    private DynamicTexture texture;
    private int[] abgr;

    /** {@code fadeIn}: it fades in over the menu, as for a reload, rather than opening the game. */
    public LoadingScreen(boolean fadeIn) {
        this.fadeIn = fadeIn;
        this.ending = OrchardScene.REDUCE_MOTION ? Ending.FADE : VideoOptions.ENDING.get();
    }

    /**
     * Draws one frame. {@code actual} is how much has loaded, {@code loaded} whether Minecraft has
     * finished; returns true once the ending is over.
     */
    public boolean draw(GuiGraphicsExtractor graphics, Minecraft minecraft, int mouseX, int mouseY, float delta,
                        double actual, boolean loaded) {
        double t = (Util.getMillis() - start) / 1000.0;
        double dt = Math.max(0, Math.min(0.1, t - last));
        last = t;
        if (loaded && loadedAt < 0) {
            loadedAt = t;
        }
        // The bar eases toward what has loaded, and runs to the end once everything has.
        double target = loaded ? 1 : Math.max(shown, actual);
        shown += (target - shown) * (1 - Math.exp(-dt * 5));
        if (loaded) {
            shown = Math.min(1, shown + dt * 0.6);
        }
        if (shown >= 0.999 && fullAt < 0) {
            shown = 1;
            fullAt = t;
        }
        double k = fullAt < 0 ? -1 : (t - fullAt - HOLD) / ending.seconds;
        if (k >= 1 || loadedAt >= 0 && t - loadedAt > 10) {
            return true;
        }
        boolean ends = k >= 0;
        double tl = fullAt < 0 ? t : fullAt;

        int guiWidth = graphics.guiWidth();
        int guiHeight = graphics.guiHeight();
        int scale = Math.max(1, (int) Math.ceil(Math.sqrt(guiWidth * (double) guiHeight / MOST_PIXELS)));
        int width = (guiWidth + scale - 1) / scale;
        int height = (guiHeight + scale - 1) / scale;
        prepare(minecraft, width, height);
        Frame f = new Frame(width, height, Math.max(1, (int) Math.round(height / 108.0)), sky, t, memory);

        if (!ends) {
            loader.picture(out, f, shown, tl, true);
        } else {
            Sun at = loader.picture(layer, f, 1, tl, ending.withSun);
            float[] sun = OrchardScene.sun(guiWidth, guiHeight);
            Ending.Target goal = new Ending.Target(sun[0] / scale, sun[1] / scale, sun[2] / scale / (10.0 * f.s()));
            if (ending == Ending.SUNRISE) {
                fadeTitleIn(minecraft);
            }
            ending.compose(out, layer, at, f, k, goal);
        }
        upload(width, height);

        double clear = fadeIn ? Math.max(0.15, Math.min(1, t / 0.5)) : 1;
        if (ends || fadeIn) {
            Screen screen = minecraft.gui.screen();
            if (screen != null && (ends || clear < 1)) {
                screen.extractRenderStateWithTooltipAndSubtitles(graphics, ends ? 0 : mouseX, ends ? 0 : mouseY, delta);
            } else {
                minecraft.gui.hud.extractDeferredSubtitles();
            }
        }
        graphics.nextStratum();
        graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, 0, 0, 0, 0, guiWidth, guiHeight, width, height, width, height,
            (int) Math.round(clear * 255) << 24 | 0xFFFFFF);
        return false;
    }

    /** Lets go of the texture. */
    public void close(Minecraft minecraft) {
        if (texture != null) {
            minecraft.getTextureManager().release(TEXTURE);
            texture = null;
        }
    }

    /** The title screen's buttons fade up toward the end of the sunrise, the way they can on Minecraft's own first start. */
    private void fadeTitleIn(Minecraft minecraft) {
        if (!titleFading && minecraft.gui.screen() instanceof TitleScreen title) {
            titleFading = true;
            ((TitleScreenAccess) title).orchard$setFading(true);
            // Its buttons come up from one second to two after this.
            ((TitleScreenAccess) title).orchard$setFadeInStart(Util.getMillis() + 80);
        }
    }

    private void prepare(Minecraft minecraft, int width, int height) {
        if (texture != null && out.width == width && out.height == height) {
            return;
        }
        close(minecraft);
        texture = new DynamicTexture(() -> "Orchard loading screen", width, height, true);
        minecraft.getTextureManager().register(TEXTURE, texture);
        layer = new Pixels(width, height);
        out = new Pixels(width, height);
        abgr = new int[width * height];
    }

    private void upload(int width, int height) {
        int[] argb = out.argb;
        for (int i = 0; i < argb.length; i++) {
            int c = argb[i];
            abgr[i] = c & 0xFF00FF00 | (c & 0xFF) << 16 | (c >> 16) & 0xFF;
        }
        MemoryUtil.memIntBuffer(texture.getPixels().getPointer(), width * height).put(0, abgr);
        texture.upload();
    }
}
