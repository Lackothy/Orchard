package com.lackothy.russet.pack;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackDownloadsTest {

    private static Optional<PackDownloads.Download> newest(String json) {
        return PackDownloads.newest(JsonParser.parseString(json));
    }

    private static String file(String name, boolean primary) {
        return "{\"url\":\"https://cdn.modrinth.com/data/x/" + name + "\",\"filename\":\"" + name + "\",\"primary\":" + primary
            + ",\"hashes\":{\"sha1\":\"" + name.length() + "\"}}";
    }

    @Test
    @DisplayName("the newest release is taken over a newer beta, and its primary file over the others")
    void newestReleasesPrimaryFile() {
        Optional<PackDownloads.Download> download = newest("[{\"version_type\":\"beta\",\"files\":[" + file("beta.zip", true) + "]},"
            + "{\"version_type\":\"release\",\"files\":[" + file("extra.zip", false) + "," + file("pack.zip", true) + "]},"
            + "{\"version_type\":\"release\",\"files\":[" + file("old.zip", true) + "]}]");
        assertEquals("pack.zip", download.orElseThrow().file());
        assertEquals("https://cdn.modrinth.com/data/x/pack.zip", download.orElseThrow().url());
    }

    @Test
    @DisplayName("with no release for this Minecraft, its newest version of any type")
    void newestOfAnyType() {
        assertEquals("beta.zip", newest("[{\"version_type\":\"beta\",\"files\":[" + file("beta.zip", false) + "]},"
            + "{\"version_type\":\"alpha\",\"files\":[" + file("alpha.zip", true) + "]}]").orElseThrow().file());
    }

    @Test
    @DisplayName("nothing when there is no version, or its file could land outside the folder or has no SHA-1")
    void nothingToTake() {
        assertTrue(newest("[]").isEmpty());
        assertTrue(newest("{\"error\":\"not_found\"}").isEmpty());
        assertTrue(newest("[{\"version_type\":\"release\",\"files\":[" + file("../escape.zip", true) + "]}]").isEmpty());
        assertTrue(newest("[{\"version_type\":\"release\",\"files\":[{\"url\":\"https://cdn.modrinth.com/a.zip\","
            + "\"filename\":\"a.zip\",\"hashes\":{}}]}]").isEmpty());
        assertTrue(newest("[{\"version_type\":\"release\",\"files\":[{\"url\":\"http://cdn.modrinth.com/a.zip\","
            + "\"filename\":\"a.zip\",\"hashes\":{\"sha1\":\"00\"}}]}]").isEmpty());
    }

    @Test
    @DisplayName("names that stay in the folder and fit in order.txt")
    void plainNames() {
        assertTrue(PackDownloads.plainName("Fancy Beds 1.4.zip"));
        assertFalse(PackDownloads.plainName(""));
        assertFalse(PackDownloads.plainName(".."));
        assertFalse(PackDownloads.plainName("a/b.zip"));
        assertFalse(PackDownloads.plainName("a\\b.zip"));
        assertFalse(PackDownloads.plainName("a\tb.zip"));
    }

    @Test
    @DisplayName("titles keep to one cell of order.txt")
    void titles() {
        assertEquals(Map.of("hFoOkEdk", "Fancy Beds  2"),
            PackDownloads.titles(JsonParser.parseString("[{\"id\":\"hFoOkEdk\",\"title\":\"Fancy Beds\\t\\n2\"},{\"id\":\"x\"}]")));
    }

    @Test
    @DisplayName("order.txt is written lowest first, and read back")
    void orderRoundTrip() {
        Map<String, PackDownloads.Entry> entries = new LinkedHashMap<>();
        entries.put("dspVZXKP", new PackDownloads.Entry("grass.zip", "Fast Better Grass"));
        entries.put("CZrLuVQo", new PackDownloads.Entry("shield.zip", "Low Shield"));
        List<String> lines = PackDownloads.lines(entries);
        assertEquals(List.of("CZrLuVQo\tshield.zip\tLow Shield", "dspVZXKP\tgrass.zip\tFast Better Grass"), lines);
        assertEquals(entries, PackDownloads.entries(lines));
    }

    @Test
    @DisplayName("a pack whose file is gone is fetched again; with every one there, nothing is")
    void haveOnlyFilesThatAreThere(@TempDir Path folder) throws IOException {
        Files.writeString(folder.resolve("shield.zip"), "pack");
        Files.write(folder.resolve("order.txt"), List.of("CZrLuVQo\tshield.zip\tLow Shield", "1jZGHJ05\tfire.zip\tLow Fire"));
        assertEquals(Map.of("CZrLuVQo", new PackDownloads.Entry("shield.zip", "Low Shield")), PackDownloads.have(folder));
        assertEquals(Map.of(), PackDownloads.have(folder.resolve("none")));
    }
}
