package com.lackothy.russet.video;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphicsModsTest {

    @Test
    @DisplayName("each mod always gets the same one of Russet's colours, and the list's mods don't all share one")
    void themes() {
        Set<Pages.Theme> used = new HashSet<>();
        for (String id : GraphicsMods.IDS) {
            assertSame(GraphicsMods.theme(id), GraphicsMods.theme(id));
            assertTrue(List.of(GraphicsMods.THEMES).contains(GraphicsMods.theme(id)));
            used.add(GraphicsMods.theme(id));
        }
        assertTrue(used.size() > 1, used.toString());
    }

    @Test
    @DisplayName("the graphics mods are the ten the player chose, each once")
    void ids() {
        assertEquals(10, new HashSet<>(GraphicsMods.IDS).size());
    }
}
