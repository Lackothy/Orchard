package com.lackothy.russet.fabulous;

record LightmapUniforms(float skyFactor, float blockFactor, float nightVision, float darkness, float bossDarkening,
                        float brightness, float[] blockTint, float[] skyColour, float[] ambient, float[] nightVisionColour) {

    LightmapUniforms steady() {
        return new LightmapUniforms(skyFactor, blockFactor, 0, 0, bossDarkening, brightness, blockTint, skyColour, ambient,
            nightVisionColour);
    }

    float change(int sky, int from, int to) {
        float[] was = colour(from, sky);
        float[] now = colour(to, sky);
        float most = 0;
        for (int i = 0; i < 3; i++) {
            most = Math.max(most, Math.abs(now[i] - was[i]));
        }
        return most;
    }

    float[] colour(int block, int sky) {
        float blockLevel = block / 15F;
        float blockBrightness = brightness(blockLevel) * blockFactor;
        float skyBrightness = brightness(sky / 15F) * skyFactor;
        float white = 0.9F * (2 * blockLevel - 1) * (2 * blockLevel - 1);
        float[] colour = new float[3];
        for (int i = 0; i < 3; i++) {
            float c = Math.max(ambient[i], nightVisionColour[i] * nightVision);
            c += skyColour[i] * skyBrightness;
            c += (blockTint[i] + (1 - blockTint[i]) * white) * blockBrightness;
            c += (c * (i == 0 ? 0.7F : 0.6F) - c) * bossDarkening;
            colour[i] = clamp(c - darkness);
        }
        float max = Math.max(colour[0], Math.max(colour[1], colour[2]));
        if (max > 0) {
            float inverted = 1 - max;
            float scale = (1 - inverted * inverted * inverted * inverted) / max;
            for (int i = 0; i < 3; i++) {
                colour[i] = clamp(colour[i] + (colour[i] * scale - colour[i]) * brightness);
            }
        }
        return colour;
    }

    private static float brightness(float level) {
        return level / (4 - 3 * level);
    }

    private static float clamp(float value) {
        return Math.clamp(value, 0F, 1F);
    }
}
