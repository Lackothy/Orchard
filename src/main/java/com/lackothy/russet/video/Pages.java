package com.lackothy.russet.video;

import com.lackothy.Russet;
import com.lackothy.russet.fabulous.CaveDust;
import com.lackothy.russet.fabulous.DynamicLights;
import com.lackothy.russet.fabulous.Fabulous;
import com.lackothy.russet.fix.Fixes;
import com.lackothy.russet.hud.AttackIndicator;
import com.lackothy.russet.rain.ParticleRain;
import com.lackothy.russet.rain.config.ConfigManager;
import com.lackothy.russet.loading.Ending;
import com.lackothy.russet.loading.Loader;
import com.lackothy.russet.loading.Rehearsal;
import com.lackothy.russet.pack.RussetPack;
import com.lackothy.russet.video.RendererSettings.Api;
import com.mojang.blaze3d.systems.RenderSystem;
import mcopt.metal.Switch;
import mcopt.metal.shade.Shade;
import mcopt.metal.shade.ShadeSettingsScreen;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.AttackIndicatorStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;

public final class Pages {

    public record Theme(int theme, int lighter, int darker) {
    }

    public record Group(String name, String version, Identifier icon, Theme theme, List<Page> pages) {
    }

    public record Page(String name, List<Section> sections, @Nullable Function<Screen, @Nullable Screen> opens) {
        public Page(String name, List<Section> sections) {
            this(name, sections, null);
        }
    }

    public record Section(String title, List<Row> rows) {
    }

    private static final Theme MINECRAFT = new Theme(0xFF8ED16B, 0xFFD3F1C2, 0xFF97B886);
    private static final Theme RUSSET = new Theme(0xFFF0B944, 0xFFFBE6B3, 0xFFC9A766);
    private static final Theme METAL = new Theme(0xFF8EB6E6, 0xFFD5E5F8, 0xFF8FA6C2);

    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

    private Pages() {
    }

    public static List<Group> groups() {
        Options o = Minecraft.getInstance().options;
        List<Group> groups = new ArrayList<>();
        groups.add(new Group("Minecraft", SharedConstants.getCurrentVersion().name(), texture("block/grass_block_side"),
            MINECRAFT, List.of(video(o), quality(o))));
        groups.add(new Group("Russet", version(Russet.MOD_ID), texture("item/apple"), RUSSET,
            List.of(loading(), packs(), fixes())));
        if (RendererSettings.present()) {
            groups.add(new Group("Metal renderer", Switch.VERSION, texture("item/iron_ingot"), METAL,
                List.of(metal())));
        }
        groups.addAll(GraphicsMods.groups());
        return groups;
    }

    private static Identifier texture(String name) {
        return Identifier.withDefaultNamespace("textures/" + name + ".png");
    }

    public static List<Page> all() {
        return groups().stream().flatMap(group -> group.pages().stream()).toList();
    }

    static String version(String mod) {
        String version = FabricLoader.getInstance().getModContainer(mod)
            .map(container -> container.getMetadata().getVersion().getFriendlyString()).orElse("");
        int plus = version.indexOf('+');
        return plus < 0 ? version : version.substring(0, plus);
    }

    private static Page video(Options o) {
        List<Row> display = new ArrayList<>(List.of(
            vanilla(o.graphicsPreset(), "Sets most of the Quality page at once. Fast and Fancy are Minecraft's. "
                + "Fabulous is Russet's: Fancy, plus effect particles drawn for Russet, Particle Rain's "
                + "weather, cave dust and dynamic lights (Quality > Fabulous). "
                + "Unlike Minecraft's Fabulous it leaves Improved Transparency off, as it costs the most frames; "
                + "it's in Quality > Blocks. Changing any of these by hand makes it Custom."),
            vanilla(o.renderDistance(), "How far away the world is drawn. Each step costs more than the last, and a "
                + "server only sends as far as it allows."),
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
                vanilla(o.attackIndicator(), "Where your attack's cooldown shows, by the crosshair or the hotbar. "
                    + "It also shows breaking, drawing a bow, eating and more (Attack indicator, below)."),
                vanilla(o.showAutosaveIndicator(), null),
                vanilla(o.menuBackgroundBlurriness(), "How much the world blurs behind menus."),
                vanilla(o.vignette(), "Darkens the corners of the screen."))),
            attackIndicator(o),
            graphicsApi(o)));
    }

    private static Row vsync(Options o) {
        if (!Switch.ON) {
            return vanilla(o.enableVsync(), "Waits for the screen before showing each frame, so the picture never tears.");
        }
        return new Row.Info(Control.caption(o.enableVsync()), Component.literal("Always on with Metal: each frame waits for "
            + "the screen, so the picture never tears and moves evenly. To turn it off, switch the Graphics API to OpenGL "
            + "or Vulkan."), CommonComponents.OPTION_ON);
    }

    private static Section graphicsApi(Options o) {
        if (!Switch.ON) {
            List<Row> rows = new ArrayList<>();
            if (MAC) {
                rows.add(new Row.Note(Component.literal(Switch.BACKEND ? Switch.WHY_BACKEND_ONLY : "Metal is off. " + Switch.WHY_OFF)));
            }
            rows.add(vanilla(o.preferredGraphicsBackend(), null));
            return new Section("Graphics API", rows);
        }
        return new Section("Graphics API", List.of(
            new Row.Pick<>(Component.literal("Graphics API"), restart("What draws the game. Metal is Apple's own, and "
                + "the one Russet's renderer is built for. OpenGL and Vulkan are Minecraft's, for a mod that needs one of them."),
                RendererSettings.METAL_BACKEND, Arrays.asList(Api.values()), api -> api.label,
                () -> RendererSettings.api(o), api -> RendererSettings.choose(o, api), Api.METAL, values -> true)));
    }

    private static Page quality(Options o) {
        return new Page("Quality", List.of(
            new Section("Blocks", List.of(
                vanilla(o.ambientOcclusion(), "Soft shadows where blocks meet."),
                vanilla(o.biomeBlendRadius(), "How far grass, leaves and water colours blend across biome edges. "
                    + "Wider costs more each time a chunk is built."),
                vanilla(o.cutoutLeaves(), null),
                vanilla(o.improvedTransparency(), null),
                vanilla(o.chunkSectionFadeInTime(), "How long new chunks take to fade in."),
                vanilla(o.prioritizeChunkUpdates(), null))),
            new Section("Textures", List.of(
                vanilla(o.mipmapLevels(), "Smooths textures in the distance."),
                vanilla(o.textureFiltering(), null),
                vanilla(o.maxAnisotropyBit(), "Keeps textures sharp at steep angles. Needs Anisotropic filtering."))),
            new Section("Sky and weather", List.of(
                vanilla(o.cloudStatus(), null),
                vanilla(o.cloudRange(), "How far away clouds are drawn."),
                vanilla(o.weatherRadius(), "How far around you rain and snow are drawn."),
                vanilla(o.hideLightningFlash(), null))),
            new Section("Entities and particles", List.of(
                vanilla(o.entityDistanceScaling(), "How far away mobs and items are drawn, as a share of the usual."),
                vanilla(o.entityShadows(), "The round shadow under mobs and items."),
                vanilla(o.particles(), null))),
            fabulous(),
            dynamicLights()));
    }

    private static Section attackIndicator(Options o) {
        if (AttackIndicator.STOOD_DOWN) {
            return new Section("Attack indicator", List.of(
                new Row.Note(Component.literal("Enhanced Attack Indicator is installed, so it draws the attack indicator."))));
        }
        OptionInstance<AttackIndicatorStatus> shown = o.attackIndicator();
        Predicate<Values> when = values -> values.get(shown, shown::get) != AttackIndicatorStatus.OFF;
        return new Section("Attack indicator", List.of(
            new Row.Note(Component.literal("It shows more than your attack, as Enhanced Attack Indicator by Minenash (MIT) does.")),
            Row.Pick.of(Component.literal("Attack cooldown"),
                Component.literal("When more than one thing is going on, which the indicator shows: your attack's cooldown "
                    + "first, in the middle (after sleeping, breaking, drawing, eating and how full a box is, before item "
                    + "cooldowns), or last."),
                VideoOptions.ATTACK_PRIORITY, Arrays.asList(AttackIndicator.Priority.values()), p -> p.label, when),
            row("No cooldown for pickaxes and shovels", "Leaves their attack cooldown out, so the indicator shows "
                + "what they're doing instead.", VideoOptions.ATTACK_HIDE_DIGGERS, when),
            row("No cooldown for axes", "The same for axes.", VideoOptions.ATTACK_HIDE_AXES, when),
            row("Breaking blocks", "How far the block you're breaking is broken.", VideoOptions.ATTACK_BLOCK_BREAKING, when),
            row("Bows, crossbows and tridents", "How far drawn, and the full sign while a crossbow is loaded.",
                VideoOptions.ATTACK_RANGED_DRAW, when),
            row("Item cooldowns", "How long until an ender pearl, chorus fruit or shield can be used again.",
                VideoOptions.ATTACK_ITEM_COOLDOWNS, when),
            row("Eating and drinking", "How far through the food or potion you are.", VideoOptions.ATTACK_EATING, when),
            row("Sleeping", "How long until you're asleep.", VideoOptions.ATTACK_SLEEP, when),
            row("Shulker boxes and bundles", "How full the one in your hand is.", VideoOptions.ATTACK_CONTAINERS, when)));
    }

    private static Row row(String name, String description, VideoOptions.Toggle toggle, Predicate<Values> when) {
        return new Row.Switch(Component.literal(name), Component.literal(description), toggle, when);
    }

    private static Section fabulous() {
        List<Row> rows = new ArrayList<>(List.of(
            new Row.Note(Component.literal("Russet's extras for Fabulous graphics.")),
            fabulous("Effect particles", "A sprite for each effect, such as a heart for Regeneration or a bolt for "
                + "Haste, in place of Minecraft's coloured swirls, on you and on other players and mobs.",
                VideoOptions.EFFECT_PARTICLES)));
        if (ParticleRain.STOOD_DOWN) {
            rows.add(new Row.Note(Component.literal("Weather particles: Particle Rain is installed, so it draws the weather.")));
        } else {
            rows.add(fabulous("Weather particles", "Rain and snow fall as particles that the wind blows, splash, ripple "
                + "on water and run down walls, with dust, haze and tumbleweeds in deserts, fog after rain and in "
                + "wet biomes, and sounds for snow and sandstorms. From Particle Rain by pigcart (MIT).",
                VideoOptions.WEATHER_PARTICLES));
            rows.add(new Row.Action(Component.literal("Weather settings"),
                Component.literal("Particle Rain's own settings: how many particles and how far, sounds, wind, "
                    + "and each particle on its own."), Component.literal("Open"),
                () -> Minecraft.getInstance().gui.setScreen(ConfigManager.screenPlease(Minecraft.getInstance().gui.screen()))));
        }
        if (CaveDust.STOOD_DOWN) {
            rows.add(new Row.Note(Component.literal("Cave dust: Cave Dust is installed, so it draws the dust.")));
        } else {
            rows.add(fabulous("Cave dust", "Specks of dust drift in the air underground, more the deeper you are, as in Cave "
                + "Dust by LizIsTired: below sea level, out of the sky's sight and outside lush caves.", VideoOptions.CAVE_DUST));
        }
        if (DynamicLights.STOOD_DOWN) {
            rows.add(new Row.Note(Component.literal("Dynamic lights: LambDynamicLights is installed, so it lights things.")));
        } else {
            rows.add(fabulous("Dynamic lights", "A torch or other light glows while you or anyone else holds it, and so do "
                + "dropped ones, items in frames, anything on fire, blazes, fireballs, glow squids and creepers about to "
                + "blow, as in LambDynamicLights. The chunks around a light are drawn again as it moves, which costs frames. "
                + "They go out by themselves under full bright or night vision, and wherever they wouldn't show, as "
                + "outdoors in daylight. With Metal's shaders on, theirs light things instead. Its settings are under "
                + "Dynamic lights below.",
                VideoOptions.DYNAMIC_LIGHTS));
        }
        return new Section("Fabulous", rows);
    }

    private static Section dynamicLights() {
        if (DynamicLights.STOOD_DOWN) {
            return new Section("Dynamic lights", List.of(
                new Row.Note(Component.literal("LambDynamicLights is installed, so its own settings apply."))));
        }
        Predicate<Values> when = values -> values.on(VideoOptions.DYNAMIC_LIGHTS);
        return new Section("Dynamic lights", List.of(
            new Row.Note(Component.literal("If dynamic lights lag, lower the sliders.")),
            new Row.Slide(Component.literal("Smoothness"),
                Component.literal("How far a light moves before the blocks around it are lit again. Off moves it a block "
                    + "at a time, in whole light levels; finer steps fade between levels too, and draw the world again "
                    + "more often. Mobs, items and your hand are lit smoothly whatever this is."),
                VideoOptions.LIGHT_SMOOTHNESS, sixteenths -> sixteenths == 16 ? "Off" : "1/" + 16 / sixteenths + " block", when),
            new Row.Slide(Component.literal("Updates"),
                Component.literal("How often the light on blocks may move. Fewer costs fewer frames, and the light "
                    + "trails behind more."),
                VideoOptions.LIGHT_UPDATE_TICKS, ticks -> switch (ticks) {
                    case 20 -> "Once a second";
                    case 10 -> "Twice a second";
                    default -> 20 / ticks + " a second";
                }, when),
            new Row.Slide(Component.literal("Most lights"),
                Component.literal("How many of the lights nearest you light things. Fewer costs fewer frames in crowds, "
                    + "mob farms and fires."),
                VideoOptions.LIGHT_MOST, String::valueOf, when),
            new Row.Slide(Component.literal("Redraws a tick"),
                Component.literal("How many pieces of the world, 16 blocks each way, may be drawn again each tick as lights "
                    + "move. A limit keeps lag spikes down on a slow computer: the lights furthest from you wait their turn, "
                    + "yours goes first. With Chunk Builder (Quality > Blocks) at Player Affected they're drawn in the same "
                    + "frame, smoothest and slowest."),
                VideoOptions.LIGHT_REDRAWS, sections -> sections == 0 ? "No limit" : String.valueOf(sections), when),
            Row.Pick.of(Component.literal("Reach"),
                Component.literal("How far a light reaches. At Short a held torch lights 7 blocks around, half as far as a "
                    + "placed one; at Long as far. Further draws more of the world again as lights move."),
                VideoOptions.LIGHT_REACH, Arrays.asList(DynamicLights.Reach.values()), r -> r.label, when),
            new Row.Slide(Component.literal("Distance"),
                Component.literal("How far from you lights are found."),
                VideoOptions.LIGHT_DISTANCE, blocks -> blocks + " blocks", when),
            row("Your own light", "What you hold, and you on fire, light around you.", VideoOptions.LIGHT_YOURS, when),
            row("Held lights", "A torch or other light in another player's or a mob's hand.", VideoOptions.LIGHT_HELD, when),
            row("Dropped items and item frames", "Lights on the ground, falling or in a frame.", VideoOptions.LIGHT_ITEMS, when),
            row("Burning things", "Players, mobs and arrows on fire.", VideoOptions.LIGHT_BURNING, when),
            row("Glowing mobs", "Blazes, magma cubes, allays and glow squids.", VideoOptions.LIGHT_MOBS, when),
            row("Fireballs and fireworks", "Fireballs, spectral arrows, fireworks and experience orbs.",
                VideoOptions.LIGHT_PROJECTILES, when),
            row("Creepers and TNT", "A creeper about to blow, brighter as it swells, and lit TNT.", VideoOptions.LIGHT_EXPLOSIVES, when),
            row("Out underwater", "Torches, campfires and other flames give no light underwater.",
                VideoOptions.LIGHT_OUT_UNDERWATER, when)));
    }

    private static Row fabulous(String name, String description, VideoOptions.Toggle extra) {
        return new Row.Switch(Component.literal(name), Component.literal(description), extra, extra::on,
            on -> Fabulous.setByHand(extra, on), extra.initial(), values -> true);
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
                        + "sky as the menu fades in, Leaf gust blows the screen away, Burst flashes, Curtain parts "
                        + "down the middle, and Iris opens a circle from the sun. With the Mac's Reduce Motion on, it fades."),
                    VideoOptions.ENDING, Arrays.asList(Ending.choices()), e -> e.label),
                new Row.Action(Component.literal("Preview"),
                    Component.literal("Plays the loading screen now, over this menu, as it shows when Minecraft starts."),
                    Component.literal("Play"), () -> Rehearsal.play(Minecraft.getInstance()))))));
    }

    private static Page packs() {
        return new Page("Packs", List.of(
            new Section("Russet Pack", List.of(
                new Row.Switch(Component.literal("Russet Pack"),
                    Component.literal("Seven resource packs by other creators, shown in your pack list as one pack named "
                        + "Russet: Low Shield and Low Fire by Oculie, Icons by Zartrix, (Bee's) Fancy Crops by bebebea_loste, "
                        + "Even Better Enchants by mythitorium, Fancy Beds by Agente_511 and Fast Better Grass by robotkoer. "
                        + "Russet downloads them from Modrinth when they're missing. Off, it doesn't, and the pack leaves "
                        + "your list."),
                    VideoOptions.PACK, VideoOptions.PACK::on, RussetPack::choose, VideoOptions.PACK.initial(),
                    values -> true)))));
    }

    private static Page fixes() {
        return new Page("Fixes", List.of(
            new Section("Fixes", Fixes.ALL.stream()
                .<Row>map(fix -> new Row.Switch(fix.name(), fix.description(), fix.toggle()))
                .toList())));
    }

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
}
