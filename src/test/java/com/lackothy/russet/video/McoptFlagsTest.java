package com.lackothy.russet.video;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McoptFlagsTest {

    @Test
    @DisplayName("the build lists the renderer's flags with their kinds and the code's defaults")
    void builtList() {
        Map<String, McoptFlags.Flag> flags = McoptFlags.all().stream().collect(Collectors.toMap(McoptFlags.Flag::key, f -> f));
        assertTrue(flags.size() > 500, "only " + flags.size() + " flags");
        assertEquals("bool", flags.get("mcopt.own.mesh.time").kind());
        assertEquals("false", flags.get("mcopt.own.mesh.time").fallback());
        assertEquals("number", flags.get("mcopt.lod.haze").kind());
        assertEquals("1.0", flags.get("mcopt.lod.haze").fallback());
        assertEquals("int", flags.get("mcopt.metal.inFlight").kind());
        assertEquals("2", flags.get("mcopt.metal.inFlight").fallback());
        assertEquals("true", flags.get("mcopt.preciseLimiter").fallback());
        assertFalse(flags.get("mcopt.lod.haze").description().isEmpty());
    }

    @Test
    @DisplayName("the shaders' own settings, prefixes, classes and the file's name aren't listed as flags")
    void leftOut() {
        List<String> keys = McoptFlags.all().stream().map(McoptFlags.Flag::key).toList();
        assertFalse(keys.contains("mcopt.shade.preset"));
        assertFalse(keys.contains("mcopt.properties"));
        assertTrue(keys.stream().noneMatch(key -> key.endsWith(".")));
        assertTrue(keys.stream().noneMatch(key -> key.equals("mcopt.bench.Bench")));
    }

    @Test
    @DisplayName("a line of the list is read into its five parts, and a broken line is skipped")
    void readsLines() {
        String tsv = "mcopt.a.b\tint\t4\tmcopt/metal/A.java\tHow many.\nbroken line\n";
        List<McoptFlags.Flag> flags = McoptFlags.read(new ByteArrayInputStream(tsv.getBytes(StandardCharsets.UTF_8)));
        assertEquals(List.of(new McoptFlags.Flag("mcopt.a.b", "int", "4", "mcopt/metal/A.java", "How many.")), flags);
    }

    @Test
    @DisplayName("a typed value must fit the flag's kind, and nothing typed is its default")
    void typedValues() {
        assertNull(McoptFlags.problem("int", "12"));
        assertNotNull(McoptFlags.problem("int", "1.5"));
        assertNull(McoptFlags.problem("number", "1.5"));
        assertNotNull(McoptFlags.problem("number", "lots"));
        assertNull(McoptFlags.problem("text", "anything"));
        assertNull(McoptFlags.problem("int", ""));
    }

    @Test
    @DisplayName("flags go under their area on the Extras page, and the rest under Other")
    void areas() {
        assertEquals("Near terrain", McoptFlags.area("mcopt.own.mesh.time"));
        assertEquals("Far terrain", McoptFlags.area("mcopt.lod.haze"));
        assertEquals("Startup", McoptFlags.area("mcopt.preload.threads"));
        assertEquals("Other", McoptFlags.area("mcopt.cpu.fence"));
    }
}
