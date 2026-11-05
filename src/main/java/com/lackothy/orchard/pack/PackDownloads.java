package com.lackothy.orchard.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lackothy.Orchard;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * How the Orchard Pack gets its packs. At start, a thread of its own downloads from Modrinth
 * whichever of the packs the game folder's {@code orchard-pack} lacks: the newest release for this
 * Minecraft (else its newest version of any type), its SHA-1 checked, and {@code order.txt}
 * written. If the game has looked for the packs by then, it reloads them, so they are on from the
 * first start. It leaves the player's own copies of the packs as they are. A pack is never updated
 * once there; delete its file to fetch the newest.
 */
public final class PackDownloads {

    /** Modrinth's project ids, lowest first: where two packs have the same file, the later one wins. */
    static final List<String> PROJECTS = List.of(
        "CZrLuVQo", // Low Shield, Oculie
        "1jZGHJ05", // Low Fire, Oculie
        "O7z3QKAG", // Icons, WeNAN Studios
        "UGEVQ6t9", // Fancy Crops, Bee
        "6udpuGCH", // Even Better Enchants, Mythitorium
        "hFoOkEdk", // Fancy Beds, Agente_511
        "dspVZXKP"  // Fast Better Grass
    );

    private static final String API = "https://api.modrinth.com/v2/";

    /** A pack in order.txt: its file in the folder and its title. */
    record Entry(String file, String title) {
    }

    /** The file of a pack's version to download. */
    record Download(String url, String file, String sha1) {
    }

    private PackDownloads() {
    }

    /** Starts fetching the packs {@code folder} lacks, if it lacks any. */
    public static void start(Path folder) {
        Map<String, Entry> have = have(folder);
        if (have.keySet().containsAll(PROJECTS)) {
            return;
        }
        Thread thread = new Thread(() -> fetchMissing(folder, have), "Orchard Pack downloads");
        thread.setDaemon(true);
        thread.start();
    }

    private static void fetchMissing(Path folder, Map<String, Entry> have) {
        Modrinth modrinth = new Modrinth();
        Map<String, Entry> now = new LinkedHashMap<>(have);
        List<String> missing = PROJECTS.stream().filter(project -> !have.containsKey(project)).toList();
        try {
            Files.createDirectories(folder);
            Map<String, String> titles = titles(JsonParser.parseString(modrinth.get("projects?ids=" + query(missing))));
            String minecraft = SharedConstants.getCurrentVersion().name();
            for (String project : missing) {
                JsonElement versions = JsonParser.parseString(modrinth.get("project/" + project + "/version?loaders="
                    + query(List.of("minecraft")) + "&game_versions=" + query(List.of(minecraft))));
                Optional<Download> download = newest(versions);
                if (download.isEmpty()) {
                    Orchard.LOG.warn("The Orchard Pack's {} has no version for Minecraft {}", project, minecraft);
                    continue;
                }
                modrinth.download(download.get(), folder);
                now.put(project, new Entry(download.get().file(), titles.getOrDefault(project, project)));
            }
        } catch (IOException | RuntimeException e) {
            Orchard.LOG.warn("Couldn't download the Orchard Pack; Minecraft tries again when it next starts", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (now.size() == have.size()) {
            return;
        }
        try {
            Path order = folder.resolve("order.txt");
            Path part = folder.resolve("order.txt.part");
            Files.write(part, lines(now), StandardCharsets.UTF_8);
            Files.move(part, order, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Orchard.LOG.warn("Couldn't write the Orchard Pack's order.txt", e);
            return;
        }
        Orchard.LOG.info("Downloaded {} of the Orchard Pack's packs", now.size() - have.size());
        if (OrchardPack.looked) {
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.execute(minecraft::reloadResourcePacks);
        }
    }

    /** order.txt's packs whose files are in the folder. */
    static Map<String, Entry> have(Path folder) {
        List<String> lines;
        try {
            lines = Files.readAllLines(folder.resolve("order.txt"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return Map.of();
        }
        Map<String, Entry> have = new LinkedHashMap<>();
        entries(lines).forEach((project, entry) -> {
            if (Files.isRegularFile(folder.resolve(entry.file()))) {
                have.put(project, entry);
            }
        });
        return have;
    }

    /** order.txt read: project ID, file name and title, split by tabs. */
    static Map<String, Entry> entries(List<String> lines) {
        Map<String, Entry> entries = new LinkedHashMap<>();
        for (String line : lines) {
            String[] cells = line.split("\t");
            if (cells.length >= 3 && plainName(cells[1])) {
                entries.put(cells[0], new Entry(cells[1], cells[2]));
            }
        }
        return entries;
    }

    /** order.txt's lines, in PROJECTS' order, lowest first. */
    static List<String> lines(Map<String, Entry> entries) {
        return PROJECTS.stream().filter(entries::containsKey)
            .map(project -> project + "\t" + entries.get(project).file() + "\t" + entries.get(project).title())
            .toList();
    }

    /** From a project's versions, newest first: the newest release's file, else the newest version's. */
    static Optional<Download> newest(JsonElement versions) {
        if (!versions.isJsonArray() || versions.getAsJsonArray().isEmpty()) {
            return Optional.empty();
        }
        JsonArray all = versions.getAsJsonArray();
        JsonObject version = all.get(0).getAsJsonObject();
        for (JsonElement each : all) {
            if ("release".equals(string(each.getAsJsonObject(), "version_type"))) {
                version = each.getAsJsonObject();
                break;
            }
        }
        JsonArray files = version.getAsJsonArray("files");
        if (files == null || files.isEmpty()) {
            return Optional.empty();
        }
        JsonObject file = files.get(0).getAsJsonObject();
        for (JsonElement each : files) {
            if (each.getAsJsonObject().has("primary") && each.getAsJsonObject().get("primary").getAsBoolean()) {
                file = each.getAsJsonObject();
                break;
            }
        }
        String url = string(file, "url");
        String name = string(file, "filename");
        JsonObject hashes = file.getAsJsonObject("hashes");
        String sha1 = hashes == null ? null : string(hashes, "sha1");
        if (url == null || !url.startsWith("https://") || name == null || !plainName(name) || sha1 == null) {
            return Optional.empty();
        }
        return Optional.of(new Download(url, name, sha1));
    }

    /** Each project's title, by ID, without the tabs and line breaks order.txt can't hold. */
    static Map<String, String> titles(JsonElement projects) {
        Map<String, String> titles = new LinkedHashMap<>();
        if (projects.isJsonArray()) {
            for (JsonElement each : projects.getAsJsonArray()) {
                String id = string(each.getAsJsonObject(), "id");
                String title = string(each.getAsJsonObject(), "title");
                if (id != null && title != null) {
                    titles.put(id, title.replaceAll("[\\t\\r\\n]", " "));
                }
            }
        }
        return titles;
    }

    /** A file name that stays in the pack's folder and fits in order.txt. */
    static boolean plainName(String name) {
        return !name.isEmpty() && !name.startsWith(".") && !name.matches(".*[/\\\\\\t\\r\\n].*");
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static String query(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return URLEncoder.encode(array.toString(), StandardCharsets.UTF_8);
    }

    /** Modrinth's API, with the User-Agent naming Orchard Metal that Modrinth asks every client for. */
    private static final class Modrinth {

        private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        private final String userAgent = "lackothy/Orchard-metal/" + FabricLoader.getInstance().getModContainer(Orchard.METAL_ID)
            .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("unknown");

        String get(String path) throws IOException, InterruptedException {
            HttpResponse<String> response = http.send(request(URI.create(API + path)), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("Modrinth answered " + response.statusCode() + " to " + path);
            }
            return response.body();
        }

        /** Downloads beside the file, then moves it into place once its SHA-1 is the one Modrinth gives. */
        void download(Download download, Path folder) throws IOException, InterruptedException {
            Path file = folder.resolve(download.file());
            Path part = folder.resolve(download.file() + ".part");
            Files.deleteIfExists(part);
            HttpResponse<Path> response = http.send(request(URI.create(download.url())), HttpResponse.BodyHandlers.ofFile(part));
            if (response.statusCode() != 200 || !download.sha1().equalsIgnoreCase(sha1(part))) {
                Files.deleteIfExists(part);
                throw new IOException(response.statusCode() != 200
                    ? "Modrinth answered " + response.statusCode() + " for " + download.file()
                    : download.file() + " isn't the file Modrinth lists");
            }
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }

        private HttpRequest request(URI uri) {
            return HttpRequest.newBuilder(uri).header("User-Agent", userAgent).timeout(Duration.ofSeconds(60)).GET().build();
        }

        private static String sha1(Path file) throws IOException {
            try (InputStream in = Files.newInputStream(file)) {
                MessageDigest digest = MessageDigest.getInstance("SHA-1");
                byte[] buffer = new byte[1 << 16];
                for (int read; (read = in.read(buffer)) > 0; ) {
                    digest.update(buffer, 0, read);
                }
                return HexFormat.of().formatHex(digest.digest());
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
