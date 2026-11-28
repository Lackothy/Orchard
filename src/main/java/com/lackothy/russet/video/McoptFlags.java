package com.lackothy.russet.video;

import com.lackothy.Russet;
import net.minecraft.network.chat.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Predicate;

public final class McoptFlags {

    public record Flag(String key, String kind, String fallback, String file, String description) {
    }

    private static final Map<String, String> AREAS = new LinkedHashMap<>();

    static {
        AREAS.put("own", "Near terrain");
        AREAS.put("lod", "Far terrain");
        AREAS.put("shade", "Shaders");
        AREAS.put("metal", "Metal");
        AREAS.put("startup", "Startup");
        AREAS.put("preload", "Startup");
        AREAS.put("", "Other");
    }

    private static List<Flag> all;
    private static Map<String, String> profile;

    private McoptFlags() {
    }

    public static synchronized List<Flag> all() {
        if (all == null) {
            all = read(McoptFlags.class.getResourceAsStream("/mcopt/flags.tsv"));
        }
        return all;
    }

    static List<Flag> read(InputStream in) {
        List<Flag> flags = new ArrayList<>();
        if (in == null) {
            Russet.LOG.warn("The renderer's list of flags (mcopt/flags.tsv) is missing from the jar");
            return flags;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                String[] parts = line.split("\t", -1);
                if (parts.length == 5) {
                    flags.add(new Flag(parts[0], parts[1], parts[2], parts[3], parts[4]));
                }
            }
        } catch (IOException e) {
            Russet.LOG.warn("Could not read the renderer's list of flags", e);
        }
        return flags;
    }

    public static String code(String key) {
        return all().stream().filter(flag -> flag.key().equals(key)).map(Flag::fallback).findFirst().orElse("");
    }

    static synchronized Map<String, String> profile() {
        if (profile == null) {
            String name = System.getProperty("mcopt.profile");
            if (name == null) {
                name = RendererSettings.get("profile");
            }
            name = name == null || name.isBlank() || name.strip().equals("alpha") ? "indie" : name.strip();
            profile = new HashMap<>();
            if (!name.equals("none")) {
                try (InputStream in = McoptFlags.class.getResourceAsStream("/mcopt/profiles/" + name + ".properties")) {
                    if (in != null) {
                        Properties p = new Properties();
                        p.load(in);
                        p.stringPropertyNames().forEach(k -> profile.put(k, p.getProperty(k).strip()));
                    }
                } catch (IOException e) {
                    Russet.LOG.warn("Could not read the renderer's profile {}", name, e);
                }
            }
        }
        return profile;
    }

    public static String initial(String key, String code) {
        String fromProfile = profile().get(key);
        return fromProfile != null ? fromProfile : code;
    }

    public static String value(String key, String code) {
        String line = RendererSettings.get(key);
        return line != null ? line : initial(key, code);
    }

    public static void set(String key, String value, String code) {
        String v = value == null ? "" : value.strip();
        RendererSettings.set(key, v.isEmpty() || v.equals(initial(key, code)) ? null : v);
    }

    static Row toggle(String name, String description, String key, Predicate<String> on, String yes, String no, Predicate<Values> when) {
        String code = code(key);
        return new Row.Switch(Component.literal(name), restart(description), key, () -> on.test(value(key, code)),
            set -> set(key, set ? yes : no, code), on.test(initial(key, code)), when);
    }

    static Row toggle(String name, String description, String key, Predicate<Values> when) {
        return toggle(name, description, key, Boolean::parseBoolean, "true", "false", when);
    }

    static Pages.Page extras(Set<String> elsewhere) {
        Map<String, List<Row>> areas = new LinkedHashMap<>();
        AREAS.values().forEach(area -> areas.putIfAbsent(area, new ArrayList<>()));
        for (Flag flag : all()) {
            if (!elsewhere.contains(flag.key())) {
                areas.get(area(flag.key())).add(row(flag));
            }
        }
        List<Pages.Section> sections = new ArrayList<>();
        sections.add(new Pages.Section("Extras", List.of(new Row.Note(Component.literal("Every other flag. Most are for testing.")))));
        areas.forEach((area, rows) -> {
            if (!rows.isEmpty()) {
                sections.add(new Pages.Section(area, rows));
            }
        });
        return new Pages.Page("Extras", sections);
    }

    static Row row(Flag flag) {
        String key = flag.key();
        String code = flag.fallback();
        Component name = Component.literal(key.substring("mcopt.".length()));
        Component description = restart((flag.description().isEmpty() ? "The code has no note for it." : flag.description())
            + "\n\n" + key + ", read in " + flag.file().substring(flag.file().lastIndexOf('/') + 1) + ". " + kind(flag.kind())
            + ". Most of these are for testing and measuring; if one breaks something, Reset puts it back.");
        String initial = initial(key, code);
        if (flag.kind().equals("bool") && (initial.equals("true") || initial.equals("false"))) {
            return new Row.Switch(name, description, key, () -> Boolean.parseBoolean(value(key, code)),
                on -> set(key, String.valueOf(on), code), Boolean.parseBoolean(initial), values -> true);
        }
        if (flag.kind().equals("bool")) {
            return new Row.Mod(name, description, key, new Control.Cycle<>(() -> List.of("", "true", "false"),
                () -> value(key, code), v -> set(key, v, code),
                v -> Component.literal(v.isEmpty() ? "Automatic" : v.equals("true") ? "On" : "Off"), initial), values -> true);
        }
        return new Row.Mod(name, description, key, new Control.Entry(() -> value(key, code), v -> set(key, v, code),
            text -> problem(flag.kind(), text), initial), values -> true);
    }

    private static String kind(String kind) {
        return switch (kind) {
            case "bool" -> "On or off";
            case "int" -> "A whole number";
            case "number" -> "A number";
            default -> "Text";
        };
    }

    static String problem(String kind, String text) {
        if (text.isEmpty() || kind.equals("text")) {
            return null;
        }
        try {
            if (kind.equals("int")) {
                Long.parseLong(text);
            } else {
                Double.parseDouble(text);
            }
            return null;
        } catch (NumberFormatException e) {
            return kind.equals("int") ? "Needs a whole number, such as 2." : "Needs a number, such as 1.5.";
        }
    }

    private static Component restart(String description) {
        return Component.literal(description + "\n\n").append(Row.RESTART);
    }

    static String area(String key) {
        return AREAS.getOrDefault(key.split("\\.")[1], AREAS.get(""));
    }
}
