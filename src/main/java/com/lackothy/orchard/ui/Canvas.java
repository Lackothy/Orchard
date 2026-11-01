package com.lackothy.orchard.ui;

import com.lackothy.orchard.mixin.GuiGraphicsAccess;
import com.lackothy.orchard.ui.render.RoundedRectState;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.joml.Matrix3x2f;

/**
 * What the orchard draws with for one frame: fills, gradients and soft shadows, layered over
 * vanilla's {@link GuiGraphicsExtractor}. Coordinates are floats in GUI pixels.
 */
public final class Canvas implements Surface {

    private final GuiGraphicsExtractor graphics;

    public Canvas(GuiGraphicsExtractor graphics) {
        this.graphics = graphics;
    }

    @Override
    public void rect(float x, float y, float width, float height, float radius, int color) {
        shape(x, y, width, height, radius, 0, color, color);
    }

    @Override
    public void gradient(float x, float y, float width, float height, float radius, int top, int bottom) {
        shape(x, y, width, height, radius, 0, top, bottom);
    }

    @Override
    public void shadow(float x, float y, float width, float height, float radius, float blur, int color) {
        shape(x, y, width, height, radius, blur, color, color);
    }

    private void shape(float x, float y, float width, float height, float radius, float param, int top, int bottom) {
        if (width <= 0 || height <= 0 || ((top >>> 24) == 0 && (bottom >>> 24) == 0)) {
            return;
        }
        ((GuiGraphicsAccess) graphics).orchard$renderState().addGuiElement(RoundedRectState.of(
            new Matrix3x2f(graphics.pose()), x, y, width, height, radius, param, top, bottom));
    }
}
