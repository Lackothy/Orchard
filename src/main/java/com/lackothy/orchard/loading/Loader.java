package com.lackothy.orchard.loading;

import com.lackothy.orchard.ui.OrchardScene;

import static com.lackothy.orchard.loading.Ease.clamp;
import static com.lackothy.orchard.loading.Ease.lerp;

public enum Loader {

    COMET("Comet") {
        @Override
        Sun draw(Pixels px, Frame f, double p, double tl, boolean withSun) {
            double tip = thinBar(px, f, p);
            sparks(px, f, tip, tl);
            Sun sun = new Sun(tip, f.barY());
            sun.angle = tl * 7;
            sun.scale = 0.75;
            return sun;
        }
    },

    COMET_BOUNCE("Comet bounce") {
        @Override
        Sun draw(Pixels px, Frame f, double p, double tl, boolean withSun) {
            int s = f.s();
            int y = f.barY();
            double tip = thinBar(px, f, p);
            sparks(px, f, tip, tl);
            double per = 0.5;
            int n = (int) Math.floor(tl / per);
            double ph = (tl / per) % 1;
            Frame.Memory memory = f.memory();
            if (n != memory.hop) {
                memory.hop = n;
                memory.hopFrom = tip;
            }
            if (n > 0 && ph < 0.4) {
                Mulberry q = new Mulberry(n * 31 + 7);
                double age = ph / 0.4;
                px.alpha = 1 - age;
                for (int i = 0; i < 6; i++) {
                    double vx = -(0.3 + q.next()) * 14 * s;
                    double vy = -(0.5 + q.next()) * 16 * s;
                    px.rect((int) Math.round(memory.hopFrom + vx * age), (int) Math.round(y + vy * age + 22 * s * age * age),
                        s, s, f.sky().spark);
                }
                px.alpha = 1;
            }
            double scale = 0.85;
            double half = 7 * s * scale;
            double lift = Math.sin(Math.PI * ph) * f.height() * 0.1;
            double squash = clamp(1 - Math.min(ph, 1 - ph) / 0.1);
            Sun sun = new Sun(tip, y - half * (1 - 0.25 * squash) - lift);
            sun.sx = 1 + 0.25 * squash;
            sun.sy = 1 - 0.25 * squash;
            sun.angle = (n + Ease.inOut(ph)) * Math.PI * 2;
            sun.scale = scale;
            sun.ground = y;
            return sun;
        }
    },

    SUNRISE("Sunrise") {
        @Override
        void behindHills(Pixels px, Frame f, double p, boolean withSun) {
            if (withSun) {
                rising(f, p).draw(px, f);
            }
        }

        @Override
        Sun draw(Pixels px, Frame f, double p, double tl, boolean withSun) {
            px.rect(0, 0, f.width(), f.height(), Sprite.withAlpha(0, (1 - p) * 0.55));
            Sun sun = rising(f, p);
            sun.selfDrawn = true;
            return sun;
        }

        private Sun rising(Frame f, double p) {
            return new Sun(f.width() / 2.0, lerp(f.height() * 0.92, f.height() * 0.34, Ease.out(p)));
        }
    },

    TREETOPS("Treetops") {
        @Override
        double growth() {
            return 0;
        }

        @Override
        Sun draw(Pixels px, Frame f, double p, double tl, boolean withSun) {
            int s = f.s();
            int n = 6;
            int ground = Math.round(OrchardScene.field(f.height()));
            int[] xs = new int[n];
            for (int i = 0; i < n; i++) {
                xs[i] = (int) Math.round(lerp(f.width() * 0.16, f.width() * 0.84, i / (double) (n - 1)));
            }
            double along = p * (n - 1);
            int i = Math.min(n - 2, (int) Math.floor(along));
            double fr = Math.min(1, along - i);
            for (int j = 0; j < n; j++) {
                Sprite tree = Sprite.tree(f.sky(), j <= Math.round(along) ? Sprite.Fruit.BLOSSOM : Sprite.Fruit.NONE);
                px.sprite(tree, xs[j], ground + s - tree.height() * s / 2.0, 0, 1, 1, 1, s);
            }
            double top = ground - 12 * s;
            double half = 9 * s;
            double lift = Math.sin(Math.PI * fr) * f.height() * 0.12;
            double squash = clamp(1 - Math.min(fr, 1 - fr) / 0.15);
            Sun sun = new Sun(lerp(xs[i], xs[i + 1], Ease.inOut(fr)), top - half * (1 - 0.2 * squash) - lift);
            sun.sx = 1 + 0.2 * squash;
            sun.sy = 1 - 0.2 * squash;
            sun.ground = top;
            return sun;
        }
    },

    ORBIT("Orbit") {
        @Override
        Sun draw(Pixels px, Frame f, double p, double tl, boolean withSun) {
            double cx = f.width() / 2.0;
            double cy = f.height() * 0.46;
            double r = f.height() * 0.2;
            int size = 2 * f.s();
            for (int i = 0; i < 16; i++) {
                double a = -Math.PI / 2 + i * Math.PI / 8;
                px.rect((int) Math.round(cx + Math.cos(a) * r - size / 2.0), (int) Math.round(cy + Math.sin(a) * r - size / 2.0),
                    size, size, p >= (i + 1) / 16.0 ? f.sky().fill : f.sky().track);
            }
            double a = -Math.PI / 2 + p * Math.PI * 2;
            Sun sun = new Sun(cx + Math.cos(a) * r, cy + Math.sin(a) * r);
            sun.angle = tl * 2;
            sun.scale = 0.8;
            return sun;
        }
    },

    RAYS("Rays and phases") {
        @Override
        Sun draw(Pixels px, Frame f, double p, double tl, boolean withSun) {
            double cx = f.width() / 2.0;
            double cy = Math.round(f.height() * 0.44);
            Sun sun = new Sun(cx, cy);
            if (f.sky() == Sky.NIGHT) {
                sun.phase = p;
                sun.scale = 1.5;
                return sun;
            }
            sun.scale = 1.3;
            sun.selfDrawn = true;
            if (!withSun) {
                return sun;
            }
            int s = f.s();
            px.sprite(f.sky().sprite(false, 1), cx, cy, 0, 1, 1, 1, 1.3 * s);
            double r = 11 * s * 1.3;
            for (int i = 0; i < 8; i++) {
                double on = clamp((p - i / 8.0) * 8);
                if (on <= 0) {
                    continue;
                }
                double a = -Math.PI / 2 + i * Math.PI / 4 + tl * 0.3;
                int size = Math.max(1, (int) Math.round(2 * s * 1.3 * Ease.back(on)));
                px.rect((int) Math.round(cx + Math.cos(a) * r - size / 2.0), (int) Math.round(cy + Math.sin(a) * r - size / 2.0),
                    size, size, f.sky().ray);
            }
            return sun;
        }
    };

    public final String label;

    Loader(String label) {
        this.label = label;
    }

    Sun picture(Pixels px, Frame f, double p, double tl, boolean withSun) {
        px.clear();
        OrchardScene.backdrop(px, f.width(), f.height(), growth(), () -> behindHills(px, f, p, withSun));
        px.rect(0, 0, f.width(), f.height(), 0x2E000000);
        Sun sun = draw(px, f, p, tl, withSun);
        if (withSun && !sun.selfDrawn) {
            sun.shadow(px, f);
            sun.draw(px, f);
        }
        return sun;
    }

    abstract Sun draw(Pixels px, Frame f, double p, double tl, boolean withSun);

    void behindHills(Pixels px, Frame f, double p, boolean withSun) {
    }

    double growth() {
        return 1;
    }

    private static double thinBar(Pixels px, Frame f, double p) {
        int x = f.barX();
        int y = f.barY();
        int w = f.barWidth();
        px.rect(x, y, w, f.s(), f.sky().track);
        double tip = x + w * p;
        px.rect(x, y, (int) Math.round(tip - x), f.s(), f.sky().fill);
        return tip;
    }

    private static void sparks(Pixels px, Frame f, double tip, double tl) {
        int s = f.s();
        Mulberry r = new Mulberry((int) Math.floor(tl * 12) + 1);
        for (int i = 1; i <= 14; i++) {
            double x = tip - i * 3 * s;
            if (x < f.barX()) {
                break;
            }
            px.alpha = (1 - i / 15.0) * (0.4 + 0.6 * r.next());
            px.rect((int) Math.round(x), f.barY() + (int) Math.round((r.next() - 0.5) * 6 * s), s, s, f.sky().spark);
        }
        px.alpha = 1;
    }
}
