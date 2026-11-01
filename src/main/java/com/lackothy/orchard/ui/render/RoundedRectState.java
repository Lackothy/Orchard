package com.lackothy.orchard.ui.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.joml.Matrix3x2fc;

/**
 * One rounded shape queued for the GUI renderer, drawn with {@link UiPipelines#ROUNDED}.
 *
 * @param param 0 for a plain fill, or a positive blur radius for a soft shadow
 */
public record RoundedRectState(
    Matrix3x2fc pose,
    float x,
    float y,
    float width,
    float height,
    float radius,
    float param,
    int topColor,
    int bottomColor,
    ScreenRectangle bounds
) implements GuiElementRenderState {

    public static RoundedRectState of(Matrix3x2fc pose, float x, float y, float width, float height,
                                      float radius, float param, int topColor, int bottomColor) {
        return new RoundedRectState(pose, x, y, width, height, radius, param, topColor, bottomColor,
            bounds(pose, x, y, width, height, param));
    }

    @Override
    public RenderPipeline pipeline() {
        return UiPipelines.ROUNDED;
    }

    @Override
    public TextureSetup textureSetup() {
        return TextureSetup.noTexture();
    }

    @Override
    public ScreenRectangle scissorArea() {
        return null;
    }

    private static ScreenRectangle bounds(Matrix3x2fc pose, float x, float y, float width, float height, float param) {
        float pad = padding(param);
        return new ScreenRectangle(
            (int) Math.floor(x - pad), (int) Math.floor(y - pad),
            (int) Math.ceil(width + pad * 2) + 1, (int) Math.ceil(height + pad * 2) + 1
        ).transformMaxBounds(pose);
    }

    /** The quad is grown past the shape so the blur, or the antialiased edge, has room. */
    private static float padding(float param) {
        return param > 0 ? param : 1;
    }

    @Override
    public void buildVertices(VertexConsumer consumer) {
        float pad = padding(param);
        float halfWidth = width / 2;
        float halfHeight = height / 2;
        float cx = x + halfWidth;
        float cy = y + halfHeight;
        float ex = halfWidth + pad;
        float ey = halfHeight + pad;
        int size1 = Math.round(halfWidth * 16);
        int size2 = Math.round(halfHeight * 16);
        int shape1 = Math.round(radius * 16);
        int shape2 = Math.round(param * 16);

        vertex(consumer, cx - ex, cy - ey, -ex, -ey, topColor, size1, size2, shape1, shape2);
        vertex(consumer, cx - ex, cy + ey, -ex, ey, bottomColor, size1, size2, shape1, shape2);
        vertex(consumer, cx + ex, cy + ey, ex, ey, bottomColor, size1, size2, shape1, shape2);
        vertex(consumer, cx + ex, cy - ey, ex, -ey, topColor, size1, size2, shape1, shape2);
    }

    private void vertex(VertexConsumer consumer, float px, float py, float lx, float ly, int color,
                        int size1, int size2, int shape1, int shape2) {
        consumer.addVertexWith2DPose(pose, px, py)
            .setColor(color)
            .setUv(lx, ly)
            .setUv1(size1, size2)
            .setUv2(shape1, shape2)
            .setNormal(0, 0, 1);
    }
}
