package com.lackothy.russet.rain;

import com.lackothy.russet.rain.config.ParticleData;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RainParticlesTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    @DisplayName("Particle Rain's particles load, and each sprite of Russet's is a file or one made when the atlas is stitched")
    void particles() throws Exception {
        Map<String, ParticleData> particles = new LinkedHashMap<>();
        try (Reader reader = new InputStreamReader(resource("assets/russet/particles.json"), StandardCharsets.UTF_8)) {
            ParticleLoader.deserializeParticles(reader, particles);
        }
        assertEquals(16, particles.size(), particles.keySet().toString());
        Set<String> made = new HashSet<>();
        for (int i = 0; i < 16; i++) {
            made.add("rain_" + i);
            made.add("snow_" + i);
        }
        for (int i = 0; i < 8; i++) {
            made.add("ripple_" + i);
        }
        for (int i = 0; i < 4; i++) {
            made.add("heavy_rain_" + i);
            made.add("heavy_snow_" + i);
            made.add("splash_" + i);
        }
        for (ParticleData data : particles.values()) {
            for (String sprite : data.spriteLocations) {
                assertTrue(sprite.startsWith("russet:") || sprite.startsWith("minecraft:"), sprite);
                String name = sprite.substring(sprite.indexOf(':') + 1);
                if (sprite.startsWith("russet:") && !made.contains(name)) {
                    try (InputStream in = resource("assets/russet/textures/particle/" + name + ".png")) {
                        assertNotNull(in);
                    }
                }
            }
        }
    }

    private static InputStream resource(String path) {
        InputStream in = RainParticlesTest.class.getClassLoader().getResourceAsStream(path);
        assertNotNull(in, path);
        return in;
    }
}
