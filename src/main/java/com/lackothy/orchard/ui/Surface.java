package com.lackothy.orchard.ui;

/**
 * What the orchard is drawn on: the loading screen's pixels, which exist before the GUI's shaders
 * have loaded. Coordinates are floats in GUI pixels.
 */
public interface Surface {

    void rect(float x, float y, float width, float height, float radius, int color);

    void gradient(float x, float y, float width, float height, float radius, int top, int bottom);

    /** A soft shadow: the shape blurred by {@code blur} pixels either side of its edge. */
    void shadow(float x, float y, float width, float height, float radius, float blur, int color);
}
