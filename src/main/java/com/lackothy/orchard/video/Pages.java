package com.lackothy.orchard.video;

import com.lackothy.orchard.loading.Ending;
import com.lackothy.orchard.loading.Loader;
import com.lackothy.orchard.loading.Rehearsal;
import com.lackothy.orchard.mac.MacPower;
import com.lackothy.orchard.mac.PowerLimit;
import com.lackothy.orchard.video.RendererSettings.Api;
import com.lackothy.orchard.video.VideoOptions.Corner;
import com.lackothy.orchard.video.VideoOptions.FrameRate;
import com.lackothy.orchard.video.VideoOptions.Limit;
import com.mojang.blaze3d.systems.RenderSystem;
import mcopt.metal.Switch;
import mcopt.metal.shade.Shade;
import mcopt.metal.shade.ShadeSettingsScreen;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * What the video settings hold: groups down the side, as Reese's Sodium Options groups each mod's
 * pages under its name, then each group's pages, each page's sections, and their rows. Between
 * them they have every option of Minecraft's own Video Settings: Minecraft's group the display,
 * frame rate and quality, Orchard's the rest (clouds and weather on World, entity distance and
 * shadows on Entities, particles on Particles) beside its own switches. The rest of Minecraft's
 * Options menu stays Minecraft's.
 */
public final class Pages {

    /** A group's colours: {@code theme} for ticks and the open page's mark, the others for its text. */
    public record Theme(int theme, int lighter, int darker) {
    }

    /**
     * A group of pages under one heading, with a 16-pixel texture of Minecraft's as its icon (a
     * texture, not an item: items can't be drawn before a world has loaded). A group of one page
     * is its heading alone.
     */
    public record Group(String name, String version, Identifier icon, Theme theme, List<Page> pages) {
    }

    public record Page(String name, List<Section> sections) {
    }

    public record Section(String title, List<Row> rows) {
    }

    private static final Theme MINECRAFT = new Theme(0xFF8ED16B, 0xFFD3F1C2, 0xFF97B886);
    private static final Theme ORCHARD = new Theme(0xFFF0B944, 0xFFFBE6B3, 0xFFC9A766);
    private static final Theme METAL = new Theme(0xFF8EB6E6, 0xFFD5E5F8, 0xFF8FA6C2);

    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

    private Pages() {
    }

    public static List<Group> groups() {
        Options o = Minecraft.getInstance().options;
        List<Group> groups = new ArrayList<>();
        groups.add(new Group("Minecraft", SharedConstants.getCurrentVersion().name(), texture("block/grass_block_side"),
            MINECRAFT, List.of(video(o), quality(o))));
        groups.add(new Group("Orchard", version("orchard"), texture("item/apple"), ORCHARD,
            List.of(animations(), particles(o), world(o), entities(o), overlay(), battery(), loading())));
        if (RendererSettings.present()) {
            groups.add(new Group("Metal renderer", Switch.VERSION, texture("item/iron_ingot"), METAL,
                List.of(metal())));
        }
        return groups;
    }

    private static Identifier texture(String name) {
        return Identifier.withDefaultNamespace("textures/" + name + ".png");
    }

    /** Every page, in order. */
    public static List<Page> all() {
        return groups().stream().flatMap(group -> group.pages().stream()).toList();
    }

    /** A mod's version, without the build after a "+". */
    private static String version(String mod) {
        String version = FabricLoader.getInstance().getModContainer(mod)
            .map(container -> container.getMetadata().getVersion().getFriendlyString()).orElse("");
        int plus = version.indexOf('+');
        return plus < 0 ? version : version.substring(0, plus);
    }

    private static Page video(Options o) {
        List<Row> display = new ArrayList<>(List.of(
            vanilla(o.renderDistance(), "How far away the world is drawn. Each step costs more than the last. Past 32 it needs more memory (the launcher's Settings), and a server only sends as far as it allows."),
            vanilla(o.simulationDistance(), "How far away mobs move and crops grow. Lower is easier on the processor."),
            vanilla(o.guiScale(), "How big menus and the HUD are drawn."),
            vanilla(o.fullscreen(), null),
            vanilla(o.exclusiveFullscreen(), null)));
        if (MAC) {
            display.add(vanilla(o.macFullscreenMenuVisibility(), null));
        }
        display.add(vanilla(o.gamma(), "How bright dark places look. It changes nothing else."));
        return new Page("Video", List.of(
            new Section("Display", display),
            new Section("Frame rate", List.of(
                vsync(o),
                vanilla(o.framerateLimit(), "The most frames a second Minecraft draws. A lower limit runs cooler and quieter."),
                vanilla(o.inactivityFpsLimit(), null))),
            new Section("Interface", List.of(
                vanilla(o.bobView(), "Sways the view as you walk."),
                vanilla(o.attackIndicator(), null),
                vanilla(o.showAutosaveIndicator(), null),
                vanilla(o.menuBackgroundBlurriness(), "How much the world blurs behind menus."),
                vanilla(o.vignette(), "Darkens the corners of the screen."))),
            graphicsApi(o)));
    }

    /**
     * Metal always waits for the screen (MetalSurface offers no other present mode), so while it
     * draws the row says so instead of a switch that would do nothing. The option itself is kept for
     * OpenGL and Vulkan.
     */
    private static Row vsync(Options o) {
        if (!Switch.ON) {
            return vanilla(o.enableVsync(), "Waits for the screen before showing each frame, so the picture never tears.");
        }
        return new Row.Info(Control.caption(o.enableVsync()), Component.literal("Always on with Metal: each frame waits for "
            + "the screen, so the picture never tears and moves evenly. To turn it off, switch the Graphics API to OpenGL "
            + "or Vulkan."), CommonComponents.OPTION_ON);
    }

    /**
     * With the renderer on, Minecraft's own choice only orders the APIs behind Metal, so it could
     * say "Prefer OpenGL" while Metal draws: Orchard's row picks between Metal and those instead.
     */
    private static Section graphicsApi(Options o) {
        if (!Switch.ON) {
            List<Row> rows = new ArrayList<>();
            if (MAC) {
                rows.add(new Row.Note(Component.literal("Metal is off. " + Switch.WHY_OFF)));
            }
            rows.add(vanilla(o.preferredGraphicsBackend(), null));
            return new Section("Graphics API", rows);
        }
        return new Section("Graphics API", List.of(
            new Row.Pick<>(Component.literal("Graphics API"), restart("What draws the game. Metal is Apple's own and "
                + "the fastest here. OpenGL and Vulkan are Minecraft's, for a mod that needs one of them."),
                RendererSettings.METAL_BACKEND, Arrays.asList(Api.values()), api -> api.label,
                () -> RendererSettings.api(o), api -> RendererSettings.choose(o, api), Api.METAL, values -> true)));
    }

    private static Page quality(Options o) {
        return new Page("Quality", List.of(
            new Section("Preset", List.of(
                vanilla(o.graphicsPreset(), null))),
            new Section("Blocks", List.of(
                vanilla(o.ambientOcclusion(), "Soft shadows where blocks meet."),
                vanilla(o.biomeBlendRadius(), "How far grass, leaves and water colours blend across biome edges. "
                    + "Wider costs more each time a chunk is built."),
                vanilla(o.cutoutLeaves(), null),
                vanilla(o.improvedTransparency(), null),
                new Row.Switch("Faster Improved Transparency", "With Improved Transparency on, Minecraft draws every "
                    + "see-through face three times, even the ones turned away from you that it then hides. This leaves "
                    + "those out, so big builds of glass or water cost far less. It looks the same.",
                    VideoOptions.FASTER_TRANSPARENCY),
                vanilla(o.chunkSectionFadeInTime(), "How long new chunks take to fade in."),
                vanilla(o.prioritizeChunkUpdates(), null))),
            new Section("Textures", List.of(
                vanilla(o.mipmapLevels(), "Smooths textures in the distance."),
                vanilla(o.textureFiltering(), null),
                vanilla(o.maxAnisotropyBit(), "Keeps textures sharp at steep angles. Needs Anisotropic filtering.")))));
    }

    private static Page animations() {
        return new Page("Animations", List.of(
            new Section("Animated textures", List.of(
                new Row.Switch("All animations", "Moving textures: water, lava, fire, portals and the rest. "
                    + "Turning them off saves a little work every tick.", VideoOptions.ANIMATIONS),
                new Row.Switch("Water", "Still and flowing water.", VideoOptions.ANIMATE_WATER, VideoOptions.ANIMATIONS),
                new Row.Switch("Lava", "Still and flowing lava.", VideoOptions.ANIMATE_LAVA, VideoOptions.ANIMATIONS),
                new Row.Switch("Fire", "Fire, soul fire and campfires.", VideoOptions.ANIMATE_FIRE, VideoOptions.ANIMATIONS),
                new Row.Switch("Portals", "Nether portals.", VideoOptions.ANIMATE_PORTAL, VideoOptions.ANIMATIONS),
                new Row.Switch("Sculk", "Sculk, its sensors and shriekers.", VideoOptions.ANIMATE_SCULK, VideoOptions.ANIMATIONS),
                new Row.Switch("Everything else", "Prismarine, magma, sea lanterns, kelp, command blocks "
                    + "and every other moving texture.", VideoOptions.ANIMATE_OTHER, VideoOptions.ANIMATIONS)))));
    }

    private static Page particles(Options o) {
        List<Row> each = new ArrayList<>();
        BuiltInRegistries.PARTICLE_TYPE.keySet().stream()
            .sorted(Comparator.comparing(Pages::particleName))
            .forEach(id -> each.add(new Row.Switch(Component.literal(particleName(id)),
                Component.literal("Particles of type " + id + "."), id,
                () -> VideoOptions.particleShown(id), shown -> VideoOptions.showParticle(id, shown), true,
                values -> values.on(VideoOptions.PARTICLES))));
        return new Page("Particles", List.of(
            new Section("Particles", List.of(
                new Row.Switch("Particles", "Every particle. Off, none are made at all.", VideoOptions.PARTICLES),
                vanilla(o.particles(), null),
                new Row.Switch("Breaking blocks", "The pieces a block bursts into when it breaks.", VideoOptions.BLOCK_BREAKING,
                    VideoOptions.PARTICLES),
                new Row.Switch("Mining blocks", "The bits that fly off a block while it is being mined.",
                    VideoOptions.BLOCK_CRACKING, VideoOptions.PARTICLES))),
            new Section("Each particle", each)));
    }

    private static Page world(Options o) {
        return new Page("World", List.of(
            new Section("Sky", List.of(
                new Row.Switch("Sky", "The sky itself. Off, it is a flat colour.", VideoOptions.SKY),
                new Row.Switch("Sun and moon", "The sun and the moon.", VideoOptions.SUN_AND_MOON, VideoOptions.SKY),
                new Row.Switch("Stars", "The stars at night.", VideoOptions.STARS, VideoOptions.SKY),
                new Row.Switch("Sunrise and sunset", "The glow on the horizon at dawn and dusk.", VideoOptions.SUNRISE, VideoOptions.SKY),
                vanilla(o.cloudStatus(), null),
                vanilla(o.cloudRange(), "How far away clouds are drawn."))),
            new Section("Weather", List.of(
                new Row.Switch("Rain and snow", "Falling rain and snow. The weather itself carries on.", VideoOptions.WEATHER),
                vanilla(o.weatherRadius(), "How far around you rain and snow are drawn."),
                vanilla(o.hideLightningFlash(), null))),
            new Section("Fog", List.of(
                new Row.Switch("Distance fog", "The haze that hides the edge of the world. Fog under water, in lava "
                    + "and from blindness stays.", VideoOptions.FOG))),
            new Section("Farms", List.of(
                new Row.Switch("Wait out farm changes", "Minecraft redraws a whole 16-block section for every block "
                    + "change in it. In places that keep changing, like farms, changes from the server wait up to 0.4 s, "
                    + "and a section whose blocks are back as they were by then (a cactus that grew and broke) isn't "
                    + "redrawn. Your own changes always show at once.", VideoOptions.FARM_CHANGES)))));
    }

    private static Page entities(Options o) {
        return new Page("Entities", List.of(
            new Section("Entities", List.of(
                vanilla(o.entityDistanceScaling(), "How far away mobs and items are drawn, as a share of the usual."),
                vanilla(o.entityShadows(), "The round shadow under mobs and items."),
                new Row.Switch("Item frames", "Item frames and what is in them.", VideoOptions.ITEM_FRAMES),
                new Row.Switch("Armor stands", "Armor stands and what they wear.", VideoOptions.ARMOR_STANDS),
                new Row.Switch("Paintings", "Paintings on walls.", VideoOptions.PAINTINGS))),
            new Section("Blocks", List.of(
                new Row.Switch("Beacon beams", "The beam a working beacon sends up.", VideoOptions.BEACON_BEAMS),
                new Row.Switch("Enchanting table books", "The book that floats over an enchanting table.",
                    VideoOptions.ENCHANTING_BOOKS),
                baked("Faster chests", "Closed chests are drawn along with the blocks around them, not one at a time. Big "
                    + "storage rooms cost far less to draw. A chest changes back while its lid moves or it is being "
                    + "broken, and its lighting can look slightly different as it does.", VideoOptions.BAKED_CHESTS),
                baked("Faster shulker boxes", "Closed shulker boxes are drawn along with the blocks around them, not one at "
                    + "a time. A box changes back while its lid moves or it is being broken, and its lighting can look "
                    + "slightly different as it does.", VideoOptions.BAKED_SHULKER_BOXES))),
            new Section("Items on the ground", List.of(
                new Row.Switch("Stacks as one item", "Draws one item for each stack lying on the ground, where Minecraft "
                    + "draws up to five for a big stack. Piles in farms cost far less to draw.", VideoOptions.ONE_ITEM_PER_STACK),
                Row.Pick.of(Component.literal("Most items drawn"),
                    Component.literal("Only the nearest so many items lying on the ground in view are drawn. The rest are "
                        + "still there and can be picked up. It counts where a farm fills the view with drops."),
                    VideoOptions.DROPPED_ITEMS, Arrays.asList(VideoOptions.DroppedItems.values()), d -> d.label))),
            new Section("Name tags", List.of(
                new Row.Switch("Players", "Names over other players.", VideoOptions.PLAYER_NAMES),
                new Row.Switch("Everything else", "Names over mobs and things given a name tag.", VideoOptions.OTHER_NAMES)))));
    }

    /** A switch of BakedBlockEntities, which has every section compiled again when it changes. */
    private static Row baked(String name, String description, VideoOptions.Toggle toggle) {
        return new Row.Switch(Component.literal(name), Component.literal(description), toggle, toggle::on,
            on -> BakedBlockEntities.set(toggle, on), toggle.initial(), values -> true);
    }

    private static Page overlay() {
        return new Page("Overlay", List.of(
            new Section("Frame rate", List.of(
                Row.Pick.of(Component.literal("Show frame rate"),
                    Component.literal("Frames a second in a corner of the screen. FPS and lows adds the slowest 1% "
                        + "of the last few seconds and the time each frame takes."),
                    VideoOptions.FRAME_RATE, Arrays.asList(FrameRate.values()), f -> f.label),
                new Row.Switch("Coordinates", "Where you are, in blocks.", VideoOptions.COORDINATES),
                Row.Pick.of(Component.literal("Corner"), Component.literal("Which corner the frame rate sits in."),
                    VideoOptions.CORNER, Arrays.asList(Corner.values()), c -> c.label, Pages::overlayShown),
                new Row.Switch(Component.literal("Background"),
                    Component.literal("A dark plate behind the text, so it reads over snow and sky."),
                    VideoOptions.OVERLAY_BACKGROUND, Pages::overlayShown))),
            new Section("Notifications", List.of(
                new Row.Switch("Advancements", "The pop-up when you make an advancement.", VideoOptions.ADVANCEMENT_TOASTS),
                new Row.Switch("Recipes", "The pop-up when you unlock a recipe.", VideoOptions.RECIPE_TOASTS),
                new Row.Switch("System messages", "Pop-ups about the game itself, such as a world failing to save.",
                    VideoOptions.SYSTEM_TOASTS),
                new Row.Switch("Tutorial hints", "The hints for new players, such as how to open your inventory.",
                    VideoOptions.TUTORIAL_TOASTS)))));
    }

    private static Page battery() {
        List<Limit> limits = Arrays.asList(Limit.values());
        return new Page("Battery", List.of(
            new Section("Frame rate limit", List.of(
                Row.Pick.of(Component.literal("On battery"),
                    Component.literal("The most frames a second while the Mac runs on its battery. Fewer frames run "
                        + "cooler and last longer. It never raises the limit set on the Video page."),
                    VideoOptions.BATTERY_LIMIT, limits, l -> l.label),
                Row.Pick.of(Component.literal("In Low Power Mode"),
                    Component.literal("The most frames a second while Low Power Mode is on, in System Settings > Battery "
                        + "or Control Centre."),
                    VideoOptions.LOW_POWER_LIMIT, limits, l -> l.label),
                Row.Pick.of(Component.literal("When the Mac is hot"),
                    Component.literal("The most frames a second while the Mac is hot enough to slow itself down. "
                        + "Drawing less lets it cool, and the frame rate holds steadier than when it throttles."),
                    VideoOptions.HOT_LIMIT, limits, l -> l.label))),
            new Section("Now", List.of(
                new Row.Live(Component.literal("This Mac"), Component.literal("What the Mac says about its power and "
                    + "heat, every two seconds."), () -> Component.literal(MacPower.describe(MacPower.state()))),
                new Row.Live(Component.literal("Limit now"), Component.literal("The lowest of the limits above that "
                    + "applies now, if any."), () -> {
                        int cap = PowerLimit.now();
                        return Component.literal(cap == 0 ? "None" : cap + " FPS");
                    })))));
    }

    private static Page loading() {
        return new Page("Loading", List.of(
            new Section("Loading screen", List.of(
                Row.Pick.of(Component.literal("Loading bar"),
                    Component.literal("What the sun or moon does while Minecraft loads. Comet spins at the tip of the bar, "
                        + "Comet bounce hops along it, Sunrise comes up over the hills, Treetops jumps from tree to tree, "
                        + "Orbit circles a ring, and Rays lights its rays one by one (at night the moon fills instead)."),
                    VideoOptions.LOADER, Arrays.asList(Loader.values()), l -> l.label),
                Row.Pick.of(Component.literal("When it has loaded"),
                    Component.literal("What plays from the loading screen into the menu. Sunrise takes the sun up into the "
                        + "orchard's sky as the trees grow in, Leaf gust blows the screen away, Burst flashes, Curtain parts "
                        + "down the middle, and Iris opens a circle from the sun. With the Mac's Reduce Motion on, it fades."),
                    VideoOptions.ENDING, Arrays.asList(Ending.choices()), e -> e.label),
                new Row.Action(Component.literal("Preview"),
                    Component.literal("Plays the loading screen now, over this menu, as it shows when Minecraft starts."),
                    Component.literal("Play"), () -> Rehearsal.play(Minecraft.getInstance()))))));
    }

    private static boolean overlayShown(Values values) {
        return values.get(VideoOptions.FRAME_RATE) != FrameRate.OFF || values.on(VideoOptions.COORDINATES);
    }

    /** The shaders in the renderer: these two take effect at the next start, the rest is on their own screen once they're on. */
    private static Section shaders() {
        List<Row> rows = new ArrayList<>(List.of(
            new Row.Switch(Component.literal("Shaders"), restart("Real shadows, god rays, volumetric clouds, reflective "
                + "water and richer light, drawn in Metal, with no shader pack. They run hotter and lower the frame rate."),
                RendererSettings.SHADERS, () -> RendererSettings.flag(RendererSettings.SHADERS, false),
                on -> RendererSettings.set(RendererSettings.SHADERS, on ? "true" : null), false, values -> true),
            new Row.Pick<>(Component.literal("Shader quality"), restart("Performance draws the world at a lower resolution "
                + "with simpler effects to hold the frame rate. Ultra draws it at full resolution with everything."),
                RendererSettings.SHADER_PRESET, RendererSettings.SHADER_PRESETS, p -> p.substring(0, 1).toUpperCase(Locale.ROOT) + p.substring(1),
                () -> RendererSettings.shaderPreset(RendererSettings.get(RendererSettings.SHADER_PRESET)),
                p -> RendererSettings.set(RendererSettings.SHADER_PRESET, RendererSettings.DEFAULT_SHADER_PRESET.equals(p) ? null : p),
                RendererSettings.DEFAULT_SHADER_PRESET, Pages::shadersOn)));
        if (Shade.ENABLED) {
            rows.add(new Row.Action(Component.literal("More shader settings"),
                Component.literal("The look and each effect. Most changes show at once."), Component.literal("Open"),
                () -> Minecraft.getInstance().gui.setScreen(new ShadeSettingsScreen(Minecraft.getInstance().gui.screen()))));
        }
        return new Section("Shaders", rows);
    }

    private static boolean shadersOn(Values values) {
        return values.get(RendererSettings.SHADERS, () -> RendererSettings.flag(RendererSettings.SHADERS, false));
    }

    private static boolean farTerrain(Values values) {
        return values.get(RendererSettings.FAR_TERRAIN, () -> RendererSettings.flag(RendererSettings.FAR_TERRAIN, false));
    }

    private static Page metal() {
        List<Integer> reaches = List.of(128, 256, 512, 1024, 2048);
        return new Page("Metal", List.of(
            new Section("Renderer", List.of(
                new Row.Info(Component.literal("Drawing with"), Component.literal("The graphics API in use."),
                    Component.literal(RenderSystem.getDevice().getDeviceInfo().backendName())),
                new Row.Info(Component.literal("Graphics"), Component.literal("The chip doing the drawing."),
                    Component.literal(RenderSystem.getDevice().getDeviceInfo().name())))),
            shaders(),
            new Section("Resolution", List.of(
                Row.Pick.of(Component.literal("Render scale"),
                    Component.literal("How much of the screen's resolution the world is drawn at. Below 100%, the "
                        + "graphics chip draws fewer pixels and Apple's MetalFX scales the world up to fill the screen; "
                        + "the HUD and menus stay sharp. Lower runs faster and cooler, and looks softer. Only with Metal. "
                        + "With Shaders on, they use their own: Resolution in the pause menu."),
                    VideoOptions.RENDER_SCALE, Arrays.asList(VideoOptions.Scale.values()), s -> s.label,
                    values -> Switch.ON && !Shade.ENABLED))),
            new Section("Terrain", List.of(
                new Row.Switch("Skip hidden see-through blocks", "Water and glass wholly behind solid blocks are left out "
                    + "before they are drawn, as solid blocks already are. It looks the same. Not with Improved Transparency.",
                    VideoOptions.HIDDEN_SEE_THROUGH))),
            new Section("Far terrain", List.of(
                new Row.Note(Component.literal("These take effect the next time Minecraft starts.")),
                new Row.Switch(Component.literal("Far terrain"), restart("Draws a simpler world past your render "
                    + "distance, out to the horizon. It costs a lot on a small graphics chip."), RendererSettings.FAR_TERRAIN,
                    () -> RendererSettings.flag(RendererSettings.FAR_TERRAIN, false),
                    on -> {
                        RendererSettings.set(RendererSettings.FAR_TERRAIN, on ? "true" : null);
                        // A reach alone turns far terrain on, so it goes when far terrain does.
                        if (!on) {
                            RendererSettings.set(RendererSettings.FAR_TERRAIN_REACH, null);
                        }
                    }, false, values -> true),
                new Row.Pick<>(Component.literal("Far terrain reach"), restart("How far far terrain reaches, in chunks."),
                    RendererSettings.FAR_TERRAIN_REACH, reaches, r -> r + " chunks",
                    () -> reach(reaches),
                    r -> RendererSettings.set(RendererSettings.FAR_TERRAIN_REACH,
                        r == RendererSettings.DEFAULT_REACH || !RendererSettings.flag(RendererSettings.FAR_TERRAIN, false)
                            ? null : String.valueOf(r)),
                    RendererSettings.DEFAULT_REACH, Pages::farTerrain),
                new Row.Switch(Component.literal("Far trees"), restart("Trees in the far terrain."),
                    RendererSettings.FAR_TERRAIN_TREES, () -> RendererSettings.flag(RendererSettings.FAR_TERRAIN_TREES, true),
                    on -> RendererSettings.set(RendererSettings.FAR_TERRAIN_TREES, on ? null : "false"),
                    true, Pages::farTerrain),
                new Row.Pick<>(Component.literal("Light mode"), restart("Far terrain with fewer, larger pieces, for "
                    + "graphics chips with fewer than 10 cores. Automatic picks it on those."),
                    RendererSettings.FAR_TERRAIN_SMALL, List.of("auto", "true", "false"), v -> switch (v) {
                        case "true" -> "On";
                        case "false" -> "Off";
                        default -> "Automatic";
                    },
                    () -> {
                        String v = RendererSettings.get(RendererSettings.FAR_TERRAIN_SMALL);
                        return v == null ? "auto" : v;
                    },
                    v -> RendererSettings.set(RendererSettings.FAR_TERRAIN_SMALL, v.equals("auto") ? null : v),
                    "auto", Pages::farTerrain)))));
    }

    private static int reach(List<Integer> reaches) {
        String value = RendererSettings.get(RendererSettings.FAR_TERRAIN_REACH);
        try {
            int reach = value == null ? RendererSettings.DEFAULT_REACH : Integer.parseInt(value);
            return reaches.contains(reach) ? reach : RendererSettings.DEFAULT_REACH;
        } catch (NumberFormatException e) {
            return RendererSettings.DEFAULT_REACH;
        }
    }

    private static Component restart(String description) {
        return Component.literal(description + "\n\n").append(Row.RESTART);
    }

    private static Row vanilla(OptionInstance<?> option, String description) {
        return new Row.Vanilla(option, description == null ? null : Component.literal(description));
    }

    /** "minecraft:block_marker" as "Block marker", with the mod's id after anything not Minecraft's. */
    static String particleName(Identifier id) {
        String words = id.getPath().replace('_', ' ').replace('/', ' ');
        String name = words.isEmpty() ? words : Character.toUpperCase(words.charAt(0)) + words.substring(1);
        return id.getNamespace().equals(Identifier.DEFAULT_NAMESPACE) ? name : name + " (" + id.getNamespace() + ")";
    }
}
