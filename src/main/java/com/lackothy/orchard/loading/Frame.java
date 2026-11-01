package com.lackothy.orchard.loading;

record Frame(int width, int height, int s, Sky sky, double t, Memory memory) {

    static final class Memory {
        int hop = -1;
        double hopFrom;
    }

    int barX() {
        return (int) Math.round(width * 0.27);
    }

    int barY() {
        return (int) Math.round(height * 0.66);
    }

    int barWidth() {
        return (int) Math.round(width * 0.46);
    }
}
