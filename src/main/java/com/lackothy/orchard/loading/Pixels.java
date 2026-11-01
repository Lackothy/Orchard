package com.lackothy.orchard.loading;

import com.lackothy.orchard.ui.Surface;

import java.util.Arrays;

/**
 * A picture the loading screen paints in memory, one int per GUI pixel (ARGB, not premultiplied),
 * then hands to Minecraft as a single texture. It has to be this way: the screen is up before
 * Minecraft has loaded any shader of Orchard's, so the only drawing there is is a textured quad.
 * Everything lands on whole pixels, as the orchard does.
 */
final class Pixels implements Surface {

    final int width;
    final int height;
    final int[] argb;
    /** How clear whatever is painted next is, 0 to 1. */
    double alpha = 1;

    Pixels(int width, int height) {
        this.width = width;
        this.height = height;
        this.argb = new int[width * height];
    }

    void clear() {
        Arrays.fill(argb, 0);
    }

    void copy(Pixels from) {
        System.arraycopy(from.argb, 0, argb, 0, argb.length);
    }

    void rect(int x, int y, int w, int h, int color) {
        int x0 = Math.max(0, x);
        int y0 = Math.max(0, y);
        int x1 = Math.min(width, x + w);
        int y1 = Math.min(height, y + h);
        for (int py = y0; py < y1; py++) {
            int row = py * width;
            for (int px = x0; px < x1; px++) {
                blend(row + px, color, 1);
            }
        }
    }

    /** Part of another picture, painted over this one {@code dx}, {@code dy} along. */
    void paint(Pixels from, int sx, int sy, int w, int h, int dx, int dy) {
        for (int y = 0; y < h; y++) {
            int fy = sy + y;
            int ty = dy + y;
            if (fy < 0 || fy >= from.height || ty < 0 || ty >= height) {
                continue;
            }
            for (int x = 0; x < w; x++) {
                int fx = sx + x;
                int tx = dx + x;
                if (fx >= 0 && fx < from.width && tx >= 0 && tx < width) {
                    blend(ty * width + tx, from.argb[fy * from.width + fx], 1);
                }
            }
        }
    }

    void paint(Pixels from) {
        paint(from, 0, 0, from.width, from.height, 0, 0);
    }

    /**
     * A round shape built of rows {@code band} pixels high, each as wide as the circle is there,
     * rounded to whole bands, so its edge steps like a block circle. {@code color} 0 clears it.
     */
    void disc(double cx, double cy, double r, int band, int color) {
        for (int y = 0; y < height; y += band) {
            double dy = y + band / 2.0 - cy;
            if (Math.abs(dy) >= r) {
                continue;
            }
            double half = Math.sqrt(r * r - dy * dy);
            int x0 = (int) Math.round((cx - half) / band) * band;
            int x1 = (int) Math.round((cx + half) / band) * band;
            if (x1 <= x0) {
                continue;
            }
            if (color == 0) {
                int from = Math.max(0, Math.min(width, x0));
                int to = Math.max(0, Math.min(width, x1));
                for (int py = Math.max(0, y); py < Math.min(height, y + band) && to > from; py++) {
                    Arrays.fill(argb, py * width + from, py * width + to, 0);
                }
            } else {
                rect(x0, y, x1 - x0, band, color);
            }
        }
    }

    /**
     * A sprite centred on ({@code cx}, {@code cy}), {@code scale} pixels to its pixel and squashed by
     * {@code sx}, {@code sy}. Turned, it is sampled pixel by pixel, so it stays blocky.
     */
    void sprite(Sprite sprite, double cx, double cy, double angle, double sx, double sy, double clear, double scale) {
        double w = sprite.width() * sx * scale;
        double h = sprite.height() * sy * scale;
        double a = ((angle % (Math.PI * 2)) + Math.PI * 2) % (Math.PI * 2);
        if (a < 0.02 || a > Math.PI * 2 - 0.02 || w * h > 40000) {
            int dw = Math.max(1, (int) Math.round(w));
            int dh = Math.max(1, (int) Math.round(h));
            int left = (int) Math.round(cx - w / 2);
            int top = (int) Math.round(cy - h / 2);
            for (int y = 0; y < dh; y++) {
                int ty = top + y;
                if (ty < 0 || ty >= height) {
                    continue;
                }
                int v = Math.min(sprite.height() - 1, (int) ((y + 0.5) * sprite.height() / dh));
                for (int x = 0; x < dw; x++) {
                    int tx = left + x;
                    if (tx >= 0 && tx < width) {
                        int u = Math.min(sprite.width() - 1, (int) ((x + 0.5) * sprite.width() / dw));
                        blend(ty * width + tx, sprite.argb()[v * sprite.width() + u], clear);
                    }
                }
            }
            return;
        }
        int d = (int) Math.ceil(Math.hypot(w, h)) + 2;
        double half = d / 2.0;
        double cos = Math.cos(a);
        double sin = Math.sin(a);
        double kx = 1 / (sx * scale);
        double ky = 1 / (sy * scale);
        int left = (int) Math.round(cx - half);
        int top = (int) Math.round(cy - half);
        for (int py = 0; py < d; py++) {
            int ty = top + py;
            if (ty < 0 || ty >= height) {
                continue;
            }
            double dy = py + 0.5 - half;
            for (int px = 0; px < d; px++) {
                int tx = left + px;
                if (tx < 0 || tx >= width) {
                    continue;
                }
                double dx = px + 0.5 - half;
                double u = (dx * cos + dy * sin) * kx + sprite.width() / 2.0;
                double v = (-dx * sin + dy * cos) * ky + sprite.height() / 2.0;
                if (u >= 0 && v >= 0 && u < sprite.width() && v < sprite.height()) {
                    blend(ty * width + tx, sprite.argb()[(int) v * sprite.width() + (int) u], clear);
                }
            }
        }
    }

    /** Paints one pixel over what is there, scaled by {@link #alpha} and {@code clear}. */
    private void blend(int i, int color, double clear) {
        double sa = (color >>> 24) / 255.0 * alpha * clear;
        if (sa <= 0) {
            return;
        }
        if (sa >= 0.999) {
            argb[i] = color | 0xFF000000;
            return;
        }
        int under = argb[i];
        double da = (under >>> 24) / 255.0;
        double out = sa + da * (1 - sa);
        double keep = da * (1 - sa);
        int r = (int) Math.round((((color >> 16) & 0xFF) * sa + ((under >> 16) & 0xFF) * keep) / out);
        int g = (int) Math.round((((color >> 8) & 0xFF) * sa + ((under >> 8) & 0xFF) * keep) / out);
        int b = (int) Math.round(((color & 0xFF) * sa + (under & 0xFF) * keep) / out);
        argb[i] = (int) Math.round(out * 255) << 24 | r << 16 | g << 8 | b;
    }

    // ── the orchard's shapes ──

    @Override
    public void rect(float x, float y, float w, float h, float radius, int color) {
        if (radius < 1) {
            int x0 = Math.round(x);
            int y0 = Math.round(y);
            rect(x0, y0, Math.round(x + w) - x0, Math.round(y + h) - y0, color);
            return;
        }
        shape(x, y, w, h, radius, 0, color);
    }

    @Override
    public void gradient(float x, float y, float w, float h, float radius, int top, int bottom) {
        int x0 = Math.round(x);
        int x1 = Math.round(x + w);
        for (int py = Math.max(0, Math.round(y)); py < Math.min(height, Math.round(y + h)); py++) {
            double t = Math.max(0, Math.min(1, (py + 0.5 - y) / h));
            rect(x0, py, x1 - x0, 1, mix(top, bottom, t));
        }
    }

    @Override
    public void shadow(float x, float y, float w, float h, float radius, float blur, int color) {
        shape(x, y, w, h, radius, blur, color);
    }

    /** A rounded box, edged as the orchard's shader edges it: one pixel soft, or blurred by {@code blur}. */
    private void shape(float x, float y, float w, float h, float radius, float blur, int color) {
        double hx = w / 2.0;
        double hy = h / 2.0;
        double cx = x + hx;
        double cy = y + hy;
        double r = Math.min(radius, Math.min(hx, hy));
        int x0 = Math.max(0, (int) Math.floor(x - blur));
        int y0 = Math.max(0, (int) Math.floor(y - blur));
        int x1 = Math.min(width, (int) Math.ceil(x + w + blur));
        int y1 = Math.min(height, (int) Math.ceil(y + h + blur));
        for (int py = y0; py < y1; py++) {
            for (int px = x0; px < x1; px++) {
                double qx = Math.abs(px + 0.5 - cx) - hx + r;
                double qy = Math.abs(py + 0.5 - cy) - hy + r;
                double d = Math.min(Math.max(qx, qy), 0) + Math.hypot(Math.max(qx, 0), Math.max(qy, 0)) - r;
                double coverage;
                if (blur > 0) {
                    double t = Math.max(0, Math.min(1, (d + blur) / (2 * blur)));
                    coverage = 1 - t * t * (3 - 2 * t);
                } else {
                    coverage = Math.max(0, Math.min(1, 0.5 - d));
                }
                if (coverage > 0) {
                    blend(py * width + px, color, coverage);
                }
            }
        }
    }

    static int mix(int a, int b, double t) {
        int aa = (int) Math.round(((a >>> 24) * (1 - t)) + (b >>> 24) * t);
        int r = (int) Math.round((((a >> 16) & 0xFF) * (1 - t)) + ((b >> 16) & 0xFF) * t);
        int g = (int) Math.round((((a >> 8) & 0xFF) * (1 - t)) + ((b >> 8) & 0xFF) * t);
        int bl = (int) Math.round(((a & 0xFF) * (1 - t)) + (b & 0xFF) * t);
        return aa << 24 | r << 16 | g << 8 | bl;
    }
}
