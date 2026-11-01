package com.lackothy.orchard.ui;

public interface Surface {

    void rect(float x, float y, float width, float height, float radius, int color);

    void gradient(float x, float y, float width, float height, float radius, int top, int bottom);

    void shadow(float x, float y, float width, float height, float radius, float blur, int color);
}
