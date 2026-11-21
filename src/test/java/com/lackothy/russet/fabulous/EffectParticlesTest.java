package com.lackothy.russet.fabulous;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class EffectParticlesTest {

    @Test
    @DisplayName("every effect has its big and small sprite")
    void sprites() throws Exception {
        for (String effect : EffectParticles.EFFECTS) {
            for (int i = 1; i <= 2; i++) {
                try (InputStream in = resource("assets/russet/textures/particle/effect/" + effect + "_" + i + ".png")) {
                    assertNotNull(in);
                }
            }
        }
    }

    @Test
    @DisplayName("a particle's colour comes back as the effect's RGB")
    void rgb() {
        assertEquals(0xCD5CAB, EffectParticles.rgb(0xCD / 255F, 0x5C / 255F, 0xAB / 255F));
        assertEquals(0xFFFFFF, EffectParticles.rgb(1, 1, 1));
        assertEquals(0, EffectParticles.rgb(0, 0, 0));
    }

    private static InputStream resource(String path) {
        InputStream in = EffectParticlesTest.class.getClassLoader().getResourceAsStream(path);
        assertNotNull(in, path);
        return in;
    }
}
