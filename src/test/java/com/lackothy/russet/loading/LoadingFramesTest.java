package com.lackothy.russet.loading;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Paints the loading screen's frames at full screen on a MacBook (378 by 245 GUI pixels). With
 * {@code RUSSET_FRAMES=<folder>} in the environment it also writes them there as PNGs, to look at.
 */
class LoadingFramesTest {

    private static final int WIDTH = 378;
    private static final int HEIGHT = 245;

    @AfterEach
    void clock() {
        System.clearProperty("russet.sceneHour");
    }

    @Test
    @DisplayName("the sparks and leaves scatter as the preview page scattered them")
    void sameRandomNumbersAsThePreview() {
        // From rng() on the Loading Lab page.
        Mulberry seven = new Mulberry(7);
        assertEquals(0.011704753153026104, seven.next());
        assertEquals(0.06195825757458806, seven.next());
        assertEquals(0.97690763277933, seven.next());
        assertEquals(0.5115870486479253, new Mulberry(11).next());
    }

    @Test
    @DisplayName("every bar covers the whole screen, by day and by night")
    void barsCoverTheScreen() throws IOException {
        for (String hour : new String[] {"12", "18.4", "23"}) {
            System.setProperty("russet.sceneHour", hour);
            for (Loader loader : Loader.values()) {
                for (double p : new double[] {0.1, 0.55, 1}) {
                    Pixels px = new Pixels(WIDTH, HEIGHT);
                    loader.picture(px, frame(1.3), p, 1.3 + p, true);
                    assertEquals(1.0, opaque(px), loader + " at " + p + ", hour " + hour);
                    save(px, "bar-" + name(loader) + "-" + Math.round(p * 100) + "-" + hour.replace('.', '_'));
                }
            }
        }
    }

    @Test
    @DisplayName("each ending starts covering the menu and ends showing it")
    void endingsOpenOntoTheMenu() throws IOException {
        System.setProperty("russet.sceneHour", "12");
        for (Ending ending : Ending.values()) {
            for (double k : new double[] {0, 0.25, 0.5, 0.75, 0.999}) {
                Frame f = frame(4 + k);
                Pixels from = new Pixels(WIDTH, HEIGHT);
                Sun last = Loader.COMET.picture(from, f, 1, 3.5, ending.withSun);
                Pixels out = new Pixels(WIDTH, HEIGHT);
                ending.compose(out, from, last, f, k, new Ending.Target(300, 60, 2.1 / 2));
                if (k == 0) {
                    assertEquals(1.0, opaque(out), ending + "'s first frame");
                }
                if (k > 0.99) {
                    assertTrue(opaque(out) < 0.05, ending + "'s last frame still covers " + opaque(out));
                }
                save(out, "end-" + name(ending) + "-" + Math.round(k * 100));
            }
        }
    }

    private static Frame frame(double t) {
        return new Frame(WIDTH, HEIGHT, Math.max(1, (int) Math.round(HEIGHT / 108.0)), Sky.now(), t, new Frame.Memory());
    }

    /** The share of pixels that are fully opaque. */
    private static double opaque(Pixels px) {
        int count = 0;
        for (int c : px.argb) {
            if (c >>> 24 == 255) {
                count++;
            }
        }
        return count / (double) px.argb.length;
    }

    private static String name(Enum<?> e) {
        return e.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static void save(Pixels px, String name) throws IOException {
        String folder = System.getenv("RUSSET_FRAMES");
        if (folder == null) {
            return;
        }
        BufferedImage image = new BufferedImage(px.width, px.height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, px.width, px.height, px.argb, 0, px.width);
        Path dir = Files.createDirectories(Path.of(folder));
        ImageIO.write(image, "png", dir.resolve(name + ".png").toFile());
    }
}
