package com.lackothy.russet.gametest;

import com.lackothy.russet.loading.Ending;
import com.lackothy.russet.loading.Loader;
import com.lackothy.russet.loading.Rehearsal;
import com.lackothy.russet.menu.EntryScreen;
import com.lackothy.russet.menu.VideoOptionsScreen;
import com.lackothy.russet.menu.frame.tab.Tab;
import com.lackothy.russet.menu.frame.tab.TabFrame;
import com.lackothy.russet.menu.frame.option.OptionRow;
import com.lackothy.russet.menu.layout.LayoutBounds;
import com.lackothy.russet.menu.option.MenuChanges;
import com.lackothy.russet.menu.option.MenuEntry;
import com.lackothy.russet.menu.option.MenuSetting;
import com.lackothy.russet.menu.widget.TabHeaderWidget;
import com.lackothy.russet.pack.RussetPack;
import com.lackothy.russet.rain.config.ConfigManager;
import com.lackothy.russet.video.Control;
import com.lackothy.russet.video.GraphicsMods;
import com.lackothy.russet.video.Pages;
import com.lackothy.russet.video.RendererSettings;
import com.lackothy.russet.video.RendererSettings.Api;
import com.lackothy.russet.video.Row;
import com.lackothy.russet.video.Values;
import com.lackothy.russet.video.VideoOptions;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import mcopt.metal.Switch;
import mcopt.metal.lod.Lod;
import mcopt.metal.lod.LodConfig;
import mcopt.metal.shade.Shade;
import mcopt.metal.shade.ShadeSettingsScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.GraphicsPreset;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.sdl.SDLKeycode;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

public final class ScreenshotTour implements FabricClientGameTest {

    private static final Path OUT = Path.of(System.getProperty("russet.screenshots", "russet-screenshots"));
    private static final int SETTLE = 10;
    private static final int CONTROL = 0x40;
    private static final int SHIFT = 0x01;

    @Override
    public void runTest(ClientGameTestContext context) {
        size(context, 854, 480, Integer.getInteger("russet.guiScale", 0));
        if (Boolean.getBoolean("russet.tour.frames")) {
            ScreenTiming.run(context, OUT);
            context.setScreen(TitleScreen::new);
            return;
        }
        if (Boolean.getBoolean("russet.tour.farOnly")) {
            int distance = context.computeOnClient(mc -> mc.options.renderDistance().get());
            context.runOnClient(mc -> mc.options.renderDistance().set(4));
            try (TestSingleplayerContext world = context.worldBuilder()
                .adjustSettings(settings -> settings.setWorldType(settings.getNormalPresetList().getFirst())).create()) {
                world.getConnection().waitForChunksRender();
                farTerrain(context, world);
            }
            context.runOnClient(mc -> mc.options.renderDistance().set(distance));
            context.setScreen(TitleScreen::new);
            return;
        }
        if (Boolean.getBoolean("russet.tour.leaves")) {
            LeafBench.run(context);
            context.setScreen(TitleScreen::new);
            return;
        }
        if (Boolean.getBoolean("russet.tour.far")) {
            FarBench.run(context);
            context.setScreen(TitleScreen::new);
            return;
        }

        System.setProperty("russet.sceneHour", "15");
        loadingScreens(context);
        openVideo(context, "Video");
        shot(context, "video-from-title");
        menus(context);

        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getServer().runCommand("time set noon");
            world.getConnection().waitForChunksRender();
            effectParticles(context, world);
            context.getInput().pressKey(InputConstants.KEY_ESCAPE);
            context.waitTicks(SETTLE);
            shot(context, "pause-over-world");
            videoSettings(context);
            otherMods(context);
            weather(context, world);
        }
        packSwitch(context);
        underground(context);

        for (int[] window : new int[][] {{756, 490}, {640, 480}}) {
            size(context, window[0], window[1], 2);
            title(context, "title-" + (window[0] / 2) + "x" + (window[1] / 2));
        }
        System.clearProperty("russet.sceneHour");
        context.setScreen(TitleScreen::new);
    }

    private static void effectParticles(ClientGameTestContext context, TestSingleplayerContext world) {
        GraphicsPreset before = context.computeOnClient(mc -> mc.options.graphicsPreset().get());
        world.getServer().runCommand("weather clear");
        world.getServer().runCommand("execute as @p at @s run tp @s ~ ~ ~ 0 15");
        String[] effects = {"regeneration", "speed", "poison", "haste", "night_vision"};
        for (int i = 0; i < effects.length; i++) {
            world.getServer().runCommand("execute as @p at @s rotated ~ 0 run summon pig ^" + (i - 2) * 1.2 + " ^ ^4 "
                + "{NoAI:1b,Silent:1b,Tags:[\"russet_" + effects[i] + "\"]}");
            world.getServer().runCommand("effect give @e[tag=russet_" + effects[i] + "] minecraft:" + effects[i] + " 600 0");
        }
        for (GraphicsPreset preset : new GraphicsPreset[] {GraphicsPreset.FANCY, GraphicsPreset.FABULOUS}) {
            context.runOnClient(mc -> mc.options.graphicsPreset().set(preset));
            context.waitTicks(40);
            shot(context, "effects-" + preset.getSerializedName());
        }
        context.runOnClient(mc -> mc.options.graphicsPreset().set(before));
        world.getServer().runCommand("tp @e[type=pig] 0 -500 0");
        context.waitTicks(40);
    }

    private static void weather(ClientGameTestContext context, TestSingleplayerContext world) {
        context.setScreen(() -> null);
        GraphicsPreset before = context.computeOnClient(mc -> mc.options.graphicsPreset().get());
        world.getServer().runCommand("execute as @p at @s rotated ~ 0 run fill ^-4 ^-1 ^3 ^4 ^-1 ^7 water");
        world.getServer().runCommand("execute as @p at @s rotated ~ 0 run fill ^-4 ^ ^9 ^4 ^3 ^9 stone");
        world.getServer().runCommand("weather rain");
        for (GraphicsPreset preset : new GraphicsPreset[] {GraphicsPreset.FANCY, GraphicsPreset.FABULOUS}) {
            context.runOnClient(mc -> mc.options.graphicsPreset().set(preset));
            context.waitTicks(120);
            shot(context, "weather-rain-" + preset.getSerializedName());
        }
        world.getServer().runCommand("weather thunder");
        context.waitTicks(120);
        shot(context, "weather-thunder-fabulous");
        biome(world, "snowy_plains");
        world.getServer().runCommand("weather rain");
        context.waitTicks(120);
        shot(context, "weather-snow-fabulous");
        biome(world, "swamp");
        world.getServer().runCommand("weather clear");
        context.waitTicks(240);
        shot(context, "weather-fog-fabulous");
        world.getServer().runCommand("weather rain");
        biome(world, "desert");
        world.getServer().runCommand("execute as @p at @s run fill ~-24 ~-1 ~-24 ~24 ~-1 ~24 sand");
        context.waitTicks(160);
        shot(context, "weather-desert-fabulous");
        world.getServer().runCommand("weather clear");
        context.runOnClient(mc -> mc.options.graphicsPreset().set(before));
    }

    private static void underground(ClientGameTestContext context) {
        int distance = context.computeOnClient(mc -> mc.options.renderDistance().get());
        context.runOnClient(mc -> mc.options.renderDistance().set(4));
        try (TestSingleplayerContext world = context.worldBuilder()
            .adjustSettings(settings -> settings.setWorldType(settings.getNormalPresetList().getFirst())).create()) {
            GraphicsPreset before = context.computeOnClient(mc -> mc.options.graphicsPreset().get());
            world.getServer().runCommand("execute as @p at @s run fill ~-6 -46 ~-6 ~6 -38 ~6 deepslate hollow");
            world.getServer().runCommand("execute as @p at @s run fillbiome ~-6 -46 ~-6 ~6 -38 ~6 minecraft:plains");
            world.getServer().runCommand("execute as @p at @s run tp @s ~ -45 ~ 0 15");
            world.getServer().runCommand("item replace entity @p weapon.mainhand with torch");
            world.getServer().runCommand("execute as @p at @s run summon item ~ ~ ~4 "
                + "{Item:{id:\"minecraft:glowstone_dust\",count:1},PickupDelay:32767}");
            world.getConnection().waitForChunksRender();
            for (GraphicsPreset preset : new GraphicsPreset[] {GraphicsPreset.FANCY, GraphicsPreset.FABULOUS}) {
                context.runOnClient(mc -> {
                    mc.options.graphicsPreset().set(preset);
                    mc.options.renderDistance().set(4);
                });
                context.waitTicks(60);
                world.getConnection().waitForChunksRender();
                shot(context, "underground-" + preset.getSerializedName());
                int light = context.computeOnClient(mc -> LightCoordsUtil.block(LightCoordsUtil.getLightCoords(mc.level, mc.player.blockPosition())));
                String particles = context.computeOnClient(mc -> mc.particleEngine.countParticles());
                boolean fabulous = preset == GraphicsPreset.FABULOUS;
                if (fabulous ? light < 12 : light != 0) {
                    throw new AssertionError("Block light at the player's feet with " + preset + " was " + light);
                }
                if (fabulous && "0".equals(particles)) {
                    throw new AssertionError("No cave dust with Fabulous");
                }
                boolean fine = context.computeOnClient(mc -> BlockPos.betweenClosedStream(mc.player.blockPosition().offset(-3, 0, -3),
                    mc.player.blockPosition().offset(3, 0, 3)).anyMatch(pos -> (LightCoordsUtil.getLightCoords(mc.level, pos) & 15) != 0));
                if (fabulous && !fine) {
                    throw new AssertionError("Dynamic lights at Smooth lit no block in sixteenths of a level");
                }
                if (fabulous) {
                    context.runOnClient(mc -> VideoOptions.LIGHT_SMOOTHNESS.set(16));
                    context.waitTicks(3);
                    boolean whole = context.computeOnClient(mc -> BlockPos.betweenClosedStream(mc.player.blockPosition().offset(-3, 0, -3),
                        mc.player.blockPosition().offset(3, 0, 3)).allMatch(pos -> (LightCoordsUtil.getLightCoords(mc.level, pos) & 15) == 0));
                    context.runOnClient(mc -> VideoOptions.LIGHT_SMOOTHNESS.set(VideoOptions.LIGHT_SMOOTHNESS.initial()));
                    if (!whole) {
                        throw new AssertionError("Dynamic lights at Smoothness Off lit a block in sixteenths of a level");
                    }
                    double gamma = context.computeOnClient(mc -> mc.options.gamma().get());
                    context.runOnClient(mc -> fullBright(mc, 15.0));
                    context.waitTicks(40);
                    int seeing = context.computeOnClient(mc -> LightCoordsUtil.block(LightCoordsUtil.getLightCoords(mc.level, mc.player.blockPosition())));
                    context.runOnClient(mc -> fullBright(mc, gamma));
                    context.waitTicks(5);
                    int after = context.computeOnClient(mc -> LightCoordsUtil.block(LightCoordsUtil.getLightCoords(mc.level, mc.player.blockPosition())));
                    world.getServer().runCommand("gamemode spectator @p");
                    world.getServer().runCommand("time set noon");
                    world.getServer().runCommand("execute as @p at @s run tp @s ~ 200 ~");
                    context.waitTicks(40);
                    int noon = context.computeOnClient(mc -> LightCoordsUtil.block(LightCoordsUtil.getLightCoords(mc.level, mc.player.blockPosition())));
                    world.getServer().runCommand("execute as @p at @s run tp @s ~ -45 ~");
                    world.getServer().runCommand("gamemode creative @p");
                    context.waitTicks(5);
                    if (seeing != 0 || after < 12 || noon != 0) {
                        throw new AssertionError("Block light at the player's feet should be 0 under full bright, 12+ after it and 0 in "
                            + "the open at noon, not " + seeing + ", " + after + " and " + noon);
                    }
                }
            }

            world.getServer().runCommand("gamemode survival @p");
            world.getServer().runCommand("item replace entity @p weapon.mainhand with air");
            world.getServer().runCommand("execute as @p at @s run tp @s ~ ~ ~ 0 0");
            world.getServer().runCommand("execute as @p at @s run setblock ~ ~1 ~2 deepslate");
            context.waitTicks(5);
            context.getInput().holdKey(options -> options.keyAttack);
            context.waitTicks(60);
            shot(context, "attack-breaking");
            int stage = context.computeOnClient(mc -> mc.gameMode.getDestroyStage());
            context.getInput().releaseKey(options -> options.keyAttack);
            if (stage <= 0) {
                throw new AssertionError("Not breaking the deepslate");
            }
            world.getServer().runCommand("item replace entity @p weapon.mainhand with bow");
            world.getServer().runCommand("give @p arrow 8");
            context.waitTicks(5);
            context.getInput().holdKey(options -> options.keyUse);
            context.waitTicks(8);
            shot(context, "attack-bow");
            context.getInput().releaseKey(options -> options.keyUse);
            farTerrain(context, world);
            renderScaleSky(context, world);
            context.runOnClient(mc -> mc.options.graphicsPreset().set(before));
        }
        context.runOnClient(mc -> mc.options.renderDistance().set(distance));
    }

    private static void fullBright(Minecraft mc, double gamma) {
        try {
            Field value = OptionInstance.class.getDeclaredField("value");
            value.setAccessible(true);
            value.set(mc.options.gamma(), gamma);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void farTerrain(ClientGameTestContext context, TestSingleplayerContext world) {
        if (!LodConfig.LIVE) {
            return;
        }
        String[] keys = {RendererSettings.FAR_TERRAIN, RendererSettings.FAR_TERRAIN_REACH, RendererSettings.FAR_TERRAIN_TREES};
        String[] saved = context.computeOnClient(mc -> Arrays.stream(keys).map(RendererSettings::get).toArray(String[]::new));
        world.getServer().runCommand("gamemode spectator @p");
        world.getServer().runCommand("time set noon");
        world.getServer().runCommand("execute as @p at @s run forceload add ~292 ~-16 ~323 ~15");
        context.waitTicks(100);
        world.getServer().runCommand("execute as @p at @s run fill ~300 100 ~-8 ~315 159 ~7 gold_block");
        world.getServer().runCommand("execute as @p at @s run fill ~292 160 ~-16 ~323 163 ~15 red_wool");
        world.getServer().runCommand("execute as @p at @s run forceload remove ~292 ~-16 ~323 ~15");
        context.waitTicks(60);
        world.getServer().runOnServer(server -> server.saveEverything(true, true, true));
        world.getServer().runCommand("execute as @p at @s run tp @s ~ 200 ~ 0 10");
        context.runOnClient(mc -> {
            RendererSettings.set(RendererSettings.FAR_TERRAIN, "true");
            RendererSettings.set(RendererSettings.FAR_TERRAIN_REACH, "256");
            RendererSettings.applyFarTerrain();
        });
        context.waitTicks(200);
        shot(context, "far-terrain-on");
        plain(context, "far-terrain-plain");
        world.getServer().runCommand("execute as @p at @s run tp @s ~ 150 ~ -90 5");
        context.waitTicks(100);
        for (int i = 0; i < 30 && context.computeOnClient(mc -> Lod.savedPending()) > 0; i++) {
            context.waitTicks(20);
        }
        context.waitTicks(20);
        shot(context, "far-terrain-saved");
        world.getServer().runCommand("execute as @p at @s run tp @s ~ 200 ~ 0 10");
        context.waitTicks(20);
        world.getServer().runCommand("execute as @p at @s run tp @s ~ 110 ~ 0 0");
        context.waitTicks(100);
        shot(context, "far-terrain-horizon");
        plain(context, "far-terrain-horizon-plain");
        world.getServer().runCommand("execute as @p at @s run tp @s ~ 90 ~ 0 35");
        context.waitTicks(60);
        shot(context, "far-terrain-edge");
        world.getServer().runCommand("execute as @p at @s run tp @s ~320 ~ ~");
        context.waitTicks(200);
        shot(context, "far-terrain-moved");
        world.getServer().runCommand("execute as @p at @s run tp @s ~-320 ~ ~");
        context.waitTicks(100);
        quality(context, world);
        float on = context.computeOnClient(mc -> Lod.activeReach());
        context.runOnClient(mc -> {
            RendererSettings.set(RendererSettings.FAR_TERRAIN_REACH, null);
            RendererSettings.applyFarTerrain();
        });
        context.waitTicks(20);
        float moved = context.computeOnClient(mc -> Lod.activeReach());
        context.runOnClient(mc -> {
            RendererSettings.set(RendererSettings.FAR_TERRAIN, null);
            RendererSettings.applyFarTerrain();
        });
        context.waitTicks(20);
        shot(context, "far-terrain-off");
        float off = context.computeOnClient(mc -> Lod.activeReach());
        context.runOnClient(mc -> {
            for (int i = 0; i < keys.length; i++) {
                RendererSettings.set(keys[i], saved[i]);
            }
            RendererSettings.applyFarTerrain();
        });
        if (on != 256 * 16 || moved != RendererSettings.DEFAULT_REACH * 16 || off != 0) {
            throw new AssertionError("Far terrain changed in the game should reach 4096 blocks, then "
                + RendererSettings.DEFAULT_REACH * 16 + ", then stop; it reached " + on + ", " + moved + " and " + off);
        }
    }

    private static void quality(ClientGameTestContext context, TestSingleplayerContext world) {
        double y = context.computeOnClient(mc -> mc.player.getY());
        int distance = context.computeOnClient(mc -> mc.options.renderDistance().get());
        for (int z = -48; z <= 48; z += 16) {
            for (int x = -48; x <= 48; x += 16) {
                world.getServer().runCommand("execute as @p at @s positioned ~" + x + " ~ ~" + z
                    + " positioned over motion_blocking run setblock ~ ~ ~ torch");
            }
        }
        context.waitTicks(40);
        context.runOnClient(mc -> mc.options.renderDistance().set(4));
        size(context, 1920, 1080, Integer.getInteger("russet.guiScale", 0));
        world.getServer().runCommand("execute as @p at @s run tp @s ~ 110 ~ 0 12");
        context.waitTicks(100);
        List<String> presets = context.computeOnClient(mc -> RendererSettings.farQualities());
        String top = presets.get(presets.size() - 1);
        shot(context, "far-terrain-low");
        String quality = context.computeOnClient(mc -> {
            RendererSettings.setFarQuality(top);
            return RendererSettings.farQuality();
        });
        context.waitTicks(5);
        shot(context, "far-terrain-top");
        world.getServer().runCommand("time set midnight");
        world.getServer().runCommand("execute as @p at @s run tp @s ~ ~ ~-150 0 12");
        context.waitTicks(100);
        shot(context, "far-terrain-night-top");
        context.runOnClient(mc -> RendererSettings.setFarQuality("low"));
        context.waitTicks(5);
        shot(context, "far-terrain-night-low");
        world.getServer().runCommand("time set noon");
        world.getServer().runCommand("execute as @p at @s run tp @s ~ ~ ~150");
        context.runOnClient(mc -> mc.options.renderDistance().set(distance));
        size(context, 854, 480, Integer.getInteger("russet.guiScale", 0));
        world.getServer().runCommand("execute as @p at @s run tp @s ~ " + y + " ~ 0 35");
        context.waitTicks(100);
        if (RendererSettings.hasFarDetails() && !quality.equals(top)) {
            throw new AssertionError("Far quality set to " + top + " should read back as it; it read " + quality);
        }
    }

    private static void plain(ClientGameTestContext context, String name) {
        float[] look = context.computeOnClient(mc -> new float[] {LodConfig.GRAIN, LodConfig.FAR_SHADE});
        context.runOnClient(mc -> {
            LodConfig.GRAIN = 0;
            LodConfig.FAR_SHADE = 0;
        });
        context.waitTicks(5);
        shot(context, name);
        context.runOnClient(mc -> {
            LodConfig.GRAIN = look[0];
            LodConfig.FAR_SHADE = look[1];
        });
        context.waitTicks(5);
    }

    private static void renderScaleSky(ClientGameTestContext context, TestSingleplayerContext world) {
        if (!Switch.ON || Shade.ENABLED) {
            return;
        }
        VideoOptions.Scale saved = context.computeOnClient(mc -> VideoOptions.RENDER_SCALE.get());
        world.getServer().runCommand("gamemode spectator @p");
        world.getServer().runCommand("time set noon");
        world.getServer().runCommand("execute as @p at @s run tp @s ~ 200 ~ 0 -10");
        context.runOnClient(mc -> VideoOptions.RENDER_SCALE.set(VideoOptions.Scale.FULL));
        context.waitTicks(SETTLE);
        context.runOnClient(mc -> mc.levelExtractor.onResourceManagerReload(mc.getResourceManager()));
        context.waitTicks(5);
        context.runOnClient(mc -> VideoOptions.RENDER_SCALE.set(VideoOptions.Scale.P50));
        context.waitTicks(SETTLE);
        shot(context, "render-scale-sky");
        context.runOnClient(mc -> mc.levelExtractor.onResourceManagerReload(mc.getResourceManager()));
        context.waitTicks(5);
        context.runOnClient(mc -> VideoOptions.RENDER_SCALE.set(VideoOptions.Scale.FULL));
        context.waitTicks(SETTLE);
        context.runOnClient(mc -> VideoOptions.RENDER_SCALE.set(saved));
    }

    private static void otherMods(ClientGameTestContext context) {
        List<String> mods = context.computeOnClient(mc -> GraphicsMods.groups().stream().map(Pages.Group::name).toList());
        for (String mod : mods) {
            openVideo(context, "Quality");
            double[] at = null;
            for (int tries = 0; tries < 6 && at == null; tries++) {
                at = context.computeOnClient(mc -> heading(mc, mod));
                if (at == null) {
                    context.getInput().setCursorPos(context.computeOnClient(mc -> mc.getWindow().getScreenWidth() * 0.15),
                        context.computeOnClient(mc -> mc.getWindow().getScreenHeight() * 0.5));
                    context.getInput().scroll(-5);
                    context.waitTicks(SETTLE);
                }
            }
            if (at == null) {
                throw new AssertionError(mod + "'s heading isn't in the sidebar");
            }
            context.getInput().setCursorPos(at[0], at[1]);
            context.waitTicks(SETTLE);
            context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
            context.waitTicks(SETTLE * 2);
            context.runOnClient(mc -> {
                if (!(mc.gui.screen() instanceof VideoOptionsScreen screen)) {
                    throw new AssertionError("Clicking " + mod + " left the menu for " + mc.gui.screen());
                }
                Tab<?> tab = screen.rso$getTabFrame().getSelectedTab().orElseThrow();
                if (!tab.getModOptions().name().equals(mod)) {
                    throw new AssertionError("Clicking " + mod + " opened " + tab.getModOptions().name() + "'s " + tab.getTitle().getString());
                }
            });
            context.getInput().setCursorPos(0, 0);
            shot(context, "mods-" + mod.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-"));
            applyModSwitch(context, mod);
        }
        if (mods.isEmpty()) {
            return;
        }
        String first = mods.getFirst();
        openVideo(context, "Quality");
        context.runOnClient(mc -> {
            TabFrame frame = ((VideoOptionsScreen) mc.gui.screen()).rso$getTabFrame();
            frame.setTab(Optional.of(frame.getTabs().stream()
                .filter(tab -> tab.getModOptions().name().equals(first) && tab.getPage().external())
                .findFirst().orElseThrow(() -> new AssertionError(first + " has no page for its own screen"))));
        });
        context.waitTicks(SETTLE * 2);
        if (context.computeOnClient(mc -> mc.gui.screen() instanceof VideoOptionsScreen)) {
            throw new AssertionError(first + "'s All settings didn't open its own screen");
        }
        shot(context, "mods-settings");
        key(context, InputConstants.KEY_ESCAPE, SDLKeycode.SDLK_ESCAPE, 0);
        context.waitTicks(SETTLE * 2);
        if (!context.computeOnClient(mc -> mc.gui.screen() instanceof VideoOptionsScreen)) {
            throw new AssertionError("Esc from " + first + "'s own screen didn't come back to Russet's menu");
        }
        shot(context, "mods-back");
    }

    private static final Values NOW = new Values() {
        @Override
        public <T> T get(Object key, Supplier<T> now) {
            return now.get();
        }
    };

    private static void applyModSwitch(ClientGameTestContext context, String mod) {
        String name = context.computeOnClient(mc -> GraphicsMods.groups().stream()
            .filter(group -> group.name().equals(mod))
            .flatMap(group -> group.pages().stream())
            .flatMap(page -> page.sections().stream())
            .flatMap(section -> section.rows().stream())
            .filter(row -> row instanceof Row.Mod && row.control() instanceof Control.Check && row.enabled(NOW))
            .map(row -> row.name().getString())
            .findFirst().orElse(null));
        if (name == null) {
            return;
        }
        for (int round = 0; round < 2; round++) {
            boolean before = context.computeOnClient(mc -> (Boolean) setting(mc.gui.screen(), name).getAppliedValue());
            context.runOnClient(mc -> {
                VideoOptionsScreen screen = (VideoOptionsScreen) mc.gui.screen();
                setting(screen, name).modifyValue(!before);
                screen.changes().applyAllOptions();
            });
            openVideo(context, "Quality");
            boolean after = context.computeOnClient(mc -> (Boolean) setting(mc.gui.screen(), name).getAppliedValue());
            if (after == before) {
                throw new AssertionError(mod + "'s " + name + " stayed " + before + " after Apply");
            }
        }
    }

    private static double @Nullable [] heading(Minecraft mc, String group) {
        Window window = mc.getWindow();
        double scale = (double) window.getScreenWidth() / window.getGuiScaledWidth();
        List<GuiEventListener> todo = new ArrayList<>(mc.gui.screen().children());
        while (!todo.isEmpty()) {
            GuiEventListener next = todo.removeLast();
            if (next instanceof TabHeaderWidget header && header.group().name().equals(group)) {
                int y = header.getY() + header.getHeight() / 2;
                return y > 0 && y < window.getGuiScaledHeight() - 30 ? new double[] {(header.getX() + 20) * scale, y * scale} : null;
            }
            if (next instanceof ContainerEventHandler container) {
                todo.addAll(container.children());
            }
        }
        return null;
    }

    private static void biome(TestSingleplayerContext world, String biome) {
        for (int y = -8; y < 32; y += 8) {
            world.getServer().runCommand("execute as @p at @s run fillbiome ~-20 ~" + y + " ~-20 ~20 ~" + (y + 7) + " ~20 minecraft:" + biome);
        }
    }

    private static void packSwitch(ClientGameTestContext context) {
        if (!context.computeOnClient(mc -> mc.getResourcePackRepository().getSelectedIds().contains(RussetPack.ID))) {
            throw new AssertionError("The Russet Pack should be on before its switch is tried");
        }
        for (boolean on : new boolean[] {false, true}) {
            context.runOnClient(mc -> RussetPack.choose(on));
            context.waitTicks(5);
            context.waitFor(mc -> mc.gui.overlay() == null);
            boolean listed = context.computeOnClient(mc -> mc.getResourcePackRepository().isAvailable(RussetPack.ID)
                && mc.getResourcePackRepository().getSelectedIds().contains(RussetPack.ID));
            if (listed != on) {
                throw new AssertionError("With its switch " + (on ? "on" : "off") + ", the Russet Pack should "
                    + (on ? "be" : "not be") + " in the pack list");
            }
        }
    }

    private static void loadingScreens(ClientGameTestContext context) {
        context.setScreen(TitleScreen::new);
        context.waitTicks(SETTLE);
        for (Loader loader : Loader.values()) {
            rehearse(context, loader, Ending.SUNRISE, 40, "loading-" + loader.name().toLowerCase(Locale.ROOT));
        }
        System.setProperty("russet.sceneHour", "23");
        rehearse(context, Loader.RAYS, Ending.SUNRISE, 40, "loading-rays-night");
        rehearse(context, Loader.COMET, Ending.SUNRISE, 94, "loading-end-sunrise-night");
        System.setProperty("russet.sceneHour", "15");
        Object[][] endings = {{Ending.SUNRISE, 94}, {Ending.LEAF_GUST, 91}, {Ending.BURST, 88}, {Ending.CURTAIN, 87},
            {Ending.IRIS, 89}};
        for (Object[] ending : endings) {
            Ending which = (Ending) ending[0];
            rehearse(context, Loader.COMET, which, (Integer) ending[1], "loading-end-" + which.name().toLowerCase(Locale.ROOT));
        }
        context.runOnClient(mc -> {
            VideoOptions.LOADER.set(Loader.COMET);
            VideoOptions.ENDING.set(Ending.SUNRISE);
        });
    }

    private static void rehearse(ClientGameTestContext context, Loader loader, Ending ending, int ticks, String name) {
        context.runOnClient(mc -> {
            VideoOptions.LOADER.set(loader);
            VideoOptions.ENDING.set(ending);
            Rehearsal.play(mc);
        });
        context.waitTicks(ticks);
        shot(context, name);
        for (int waited = 0; context.computeOnClient(mc -> mc.gui.overlay() != null); waited += 5) {
            if (waited > 300) {
                throw new AssertionError("The loading screen never went away after " + name);
            }
            context.waitTicks(5);
        }
        if (!context.computeOnClient(mc -> mc.gui.screen() instanceof TitleScreen)) {
            throw new AssertionError("The loading screen did not end on the title screen after " + name);
        }
    }

    private static void videoSettings(ClientGameTestContext context) {
        List<String> pages = context.computeOnClient(mc -> {
            List<String> mods = GraphicsMods.groups().stream().map(Pages.Group::name).toList();
            return Pages.groups().stream().filter(group -> !mods.contains(group.name()))
                .flatMap(group -> group.pages().stream()).map(Pages.Page::name).toList();
        });
        List<String> keys = context.computeOnClient(mc -> Pages.all().stream()
            .filter(page -> !page.name().equals("Extras"))
            .flatMap(page -> page.sections().stream())
            .flatMap(section -> section.rows().stream())
            .map(row -> row.name().getString())
            .filter(name -> name.matches("[a-z0-9_]+(\\.[a-z0-9_]+)+"))
            .toList());
        if (!keys.isEmpty()) {
            throw new AssertionError("Rows named with a key that has no words behind it: " + keys);
        }
        for (String page : pages) {
            openVideo(context, page);
            shot(context, "video-" + page.toLowerCase(Locale.ROOT).replace(' ', '-'));
        }
        openVideo(context, MenuChanges.MENU_PAGE);
        shot(context, "video-menu");
        shaderSettings(context);
        flagEntry(context);
        openVideo(context, "Loading");
        key(context, InputConstants.KEY_F, InputConstants.KEYCODE_F, CONTROL);
        context.getInput().typeChars("clouds");
        context.waitTicks(SETTLE);
        shot(context, "video-search");
        key(context, InputConstants.KEY_RETURN, InputConstants.KEYCODE_RETURN, 0);
        context.waitTicks(SETTLE);
        shot(context, "video-search-result");
        key(context, InputConstants.KEY_ESCAPE, SDLKeycode.SDLK_ESCAPE, 0);
        context.waitTicks(SETTLE);
        context.runOnClient(mc -> {
            if (!(mc.gui.screen() instanceof VideoOptionsScreen menu) || menu.rso$getSearchTextField().hasText()) {
                throw new AssertionError("Esc should clear the search and leave the menu open, not " + mc.gui.screen());
            }
        });
        context.runOnClient(mc -> {
            List<Row> rows = Pages.all().stream().filter(page -> page.name().equals("Video"))
                .flatMap(page -> page.sections().stream()).filter(section -> section.title().equals("Graphics API"))
                .flatMap(section -> section.rows().stream()).toList();
            boolean ours = rows.size() == 1 && rows.get(0) instanceof Row.Pick<?> pick && pick.get().get() == Api.METAL;
            boolean theirs = rows.stream().anyMatch(row -> row instanceof Row.Note)
                && rows.stream().anyMatch(row -> row instanceof Row.Vanilla);
            if (Switch.ON ? !ours : !theirs) {
                throw new AssertionError("Graphics API should be " + (Switch.ON ? "Russet's row, on Metal" : "Minecraft's, with why Metal is off")
                    + ", not " + rows);
            }
        });
        key(context, InputConstants.KEY_F, InputConstants.KEYCODE_F, CONTROL);
        context.getInput().typeChars("graphics api");
        key(context, InputConstants.KEY_RETURN, InputConstants.KEYCODE_RETURN, 0);
        context.waitTicks(SETTLE);
        shot(context, "video-graphics-api");
        key(context, InputConstants.KEY_ESCAPE, SDLKeycode.SDLK_ESCAPE, 0);
        context.waitTicks(SETTLE);
        context.getInput().setCursorPos(context.computeOnClient(mc -> mc.getWindow().getScreenWidth() * 0.6),
            context.computeOnClient(mc -> mc.getWindow().getScreenHeight() * 0.5));
        context.getInput().scroll(-100);
        context.waitTicks(SETTLE);
        context.getInput().scroll(3);
        context.waitTicks(SETTLE);
        context.getInput().setCursorPos(0, 0);
        context.waitTicks(SETTLE);
        shot(context, "video-attack-indicator");
        key(context, InputConstants.KEY_F, InputConstants.KEYCODE_F, CONTROL);
        context.getInput().typeChars("heart for regeneration");
        key(context, InputConstants.KEY_RETURN, InputConstants.KEYCODE_RETURN, 0);
        key(context, InputConstants.KEY_ESCAPE, SDLKeycode.SDLK_ESCAPE, 0);
        context.waitTicks(SETTLE);
        context.getInput().setCursorPos(context.computeOnClient(mc -> mc.getWindow().getScreenWidth() * 0.6),
            context.computeOnClient(mc -> mc.getWindow().getScreenHeight() * 0.5));
        context.getInput().scroll(-100);
        context.waitTicks(SETTLE);
        context.getInput().setCursorPos(0, 0);
        context.waitTicks(SETTLE);
        shot(context, "video-dynamic-lights-kinds");
        context.getInput().setCursorPos(context.computeOnClient(mc -> mc.getWindow().getScreenWidth() * 0.6),
            context.computeOnClient(mc -> mc.getWindow().getScreenHeight() * 0.5));
        context.getInput().scroll(18);
        context.waitTicks(SETTLE);
        context.getInput().setCursorPos(0, 0);
        context.waitTicks(SETTLE);
        shot(context, "video-dynamic-lights");
        context.getInput().setCursorPos(context.computeOnClient(mc -> mc.getWindow().getScreenWidth() * 0.6),
            context.computeOnClient(mc -> mc.getWindow().getScreenHeight() * 0.5));
        context.getInput().scroll(21);
        context.waitTicks(SETTLE);
        context.getInput().setCursorPos(0, 0);
        context.waitTicks(SETTLE);
        shot(context, "video-fabulous");
        context.runOnClient(mc -> mc.gui.setScreen(ConfigManager.screenPlease(mc.gui.screen())));
        context.waitTicks(SETTLE);
        shot(context, "rain-settings");
        openVideo(context, "Quality");
        pending(context);
        tooltip(context);

        size(context, 756, 490, 2);
        openVideo(context, "Video");
        shot(context, "video-video-378x245");
        size(context, 640, 480, 2);
        openVideo(context, "Quality");
        shot(context, "video-quality-320x240");
        size(context, 854, 480, Integer.getInteger("russet.guiScale", 0));
    }

    private static void pending(ClientGameTestContext context) {
        openVideo(context, "Loading");
        Loader before = context.computeOnClient(mc -> VideoOptions.LOADER.get());
        Loader other = before == Loader.RAYS ? Loader.COMET : Loader.RAYS;
        context.runOnClient(mc -> setting(mc.gui.screen(), "Loading bar").modifyValue(other));
        context.waitTicks(SETTLE);
        shot(context, "video-pending");
        context.runOnClient(mc -> {
            MenuChanges changes = ((VideoOptionsScreen) mc.gui.screen()).changes();
            if (VideoOptions.LOADER.get() != before || !changes.anyOptionChanged()) {
                throw new AssertionError("A change in the menu was set before Apply, or not held");
            }
            changes.resetAllOptionsFromBindings();
            if (changes.anyOptionChanged()) {
                throw new AssertionError("Undo left a change held");
            }
        });
    }

    private static void key(ClientGameTestContext context, int key, int keycode, int modifiers) {
        context.runOnClient(mc -> mc.gui.screen().keyPressed(new KeyEvent(key, keycode, modifiers)));
    }

    @SuppressWarnings("unchecked")
    private static MenuSetting<Object> setting(Screen screen, String name) {
        return ((VideoOptionsScreen) screen).changes().getModOptions().stream()
            .flatMap(group -> group.pages().stream())
            .flatMap(page -> page.groups().stream())
            .flatMap(section -> section.options().stream())
            .filter(option -> option instanceof MenuSetting<?> && option.getName().getString().equals(name))
            .map(option -> (MenuSetting<Object>) option)
            .findFirst()
            .orElseThrow(() -> new AssertionError("No setting called " + name));
    }

    private static void tooltip(ClientGameTestContext context) {
        openVideo(context, "Quality");
        double[] at = context.computeOnClient(mc -> {
            OptionRow row = ((VideoOptionsScreen) mc.gui.screen()).rso$getTabFrame().findFirstSelectedOptionRow();
            LayoutBounds bounds = row.getDimensions();
            Window window = mc.getWindow();
            double scale = (double) window.getScreenWidth() / window.getGuiScaledWidth();
            return new double[] {(bounds.x() + 20) * scale, bounds.getCenterY() * scale};
        });
        context.getInput().setCursorPos(at[0], at[1]);
        context.waitTicks(SETTLE * 3);
        shot(context, "video-tooltip");
        context.getInput().setCursorPos(0, 0);
    }

    private static void shaderSettings(ClientGameTestContext context) {
        if (!context.computeOnClient(mc -> Shade.ENABLED)) {
            return;
        }
        openVideo(context, "Shaders");
        context.runOnClient(mc -> Pages.all().stream().flatMap(page -> page.sections().stream())
            .flatMap(section -> section.rows().stream())
            .filter(row -> row instanceof Row.Action && row.name().getString().equals("More shader settings"))
            .map(Row.Action.class::cast).findFirst().orElseThrow().run().run());
        context.waitTicks(SETTLE);
        context.runOnClient(mc -> {
            if (!(mc.gui.screen() instanceof ShadeSettingsScreen)) {
                throw new AssertionError("More shader settings should open their screen, not " + mc.gui.screen());
            }
        });
        shot(context, "video-shader-settings");
        key(context, InputConstants.KEY_ESCAPE, SDLKeycode.SDLK_ESCAPE, 0);
        context.waitTicks(SETTLE);
        context.runOnClient(mc -> {
            if (!(mc.gui.screen() instanceof VideoOptionsScreen)) {
                throw new AssertionError("Esc should come back to Russet's settings, not " + mc.gui.screen());
            }
        });
    }

    private static void flagEntry(ClientGameTestContext context) {
        if (!context.computeOnClient(mc -> Switch.ON)) {
            return;
        }
        openVideo(context, "Extras");
        context.runOnClient(mc -> mc.gui.setScreen(new EntryScreen(mc.gui.screen(), (MenuEntry) (Object) setting(mc.gui.screen(), "metal.inFlight"))));
        context.waitTicks(SETTLE);
        context.getInput().typeChars("x");
        context.waitTicks(SETTLE);
        shot(context, "video-entry-problem");
        key(context, InputConstants.KEY_RETURN, InputConstants.KEYCODE_RETURN, 0);
        context.runOnClient(mc -> {
            if (!(mc.gui.screen() instanceof EntryScreen)) {
                throw new AssertionError("Enter on a value that isn't a whole number should stay on the entry, not open " + mc.gui.screen());
            }
        });
        key(context, InputConstants.KEY_ESCAPE, SDLKeycode.SDLK_ESCAPE, 0);
        context.waitTicks(SETTLE);
        context.runOnClient(mc -> {
            if (!(mc.gui.screen() instanceof VideoOptionsScreen menu) || setting(menu, "metal.inFlight").hasChanged()) {
                throw new AssertionError("Esc should go back to the menu with nothing held, not to " + mc.gui.screen());
            }
            mc.gui.setScreen(new EntryScreen(menu, (MenuEntry) (Object) setting(menu, "metal.inFlight")));
        });
        context.waitTicks(SETTLE);
        for (int i = 0; i < 4; i++) {
            key(context, InputConstants.KEY_BACKSPACE, InputConstants.KEYCODE_BACKSPACE, 0);
        }
        context.getInput().typeChars("3");
        key(context, InputConstants.KEY_RETURN, InputConstants.KEYCODE_RETURN, 0);
        context.waitTicks(SETTLE);
        shot(context, "video-entry-held");
        context.runOnClient(mc -> {
            if (!(mc.gui.screen() instanceof VideoOptionsScreen menu) || !"3".equals(setting(menu, "metal.inFlight").getValidatedValue())) {
                throw new AssertionError("Enter on 3 should hold it in the menu, not leave " + mc.gui.screen());
            }
            menu.changes().resetAllOptionsFromBindings();
        });
    }

    private static void openVideo(ClientGameTestContext context, String page) {
        context.runOnClient(mc -> {
            VideoOptionsScreen.startOn(page);
            mc.gui.setScreen(new VideoSettingsScreen(new OptionsScreen(new TitleScreen(), mc.options), mc, mc.options));
            if (!(mc.gui.screen() instanceof VideoOptionsScreen)) {
                throw new AssertionError("Video Settings opened " + mc.gui.screen() + ", not Russet's");
            }
        });
        context.waitTicks(SETTLE);
    }

    private static void menus(ClientGameTestContext context) {
        context.runOnClient(mc -> {
            mc.gui.setScreen(new OptionsScreen(new TitleScreen(), mc.options));
            if (mc.gui.screen().getClass() != OptionsScreen.class) {
                throw new AssertionError("Options opened " + mc.gui.screen() + ", not Minecraft's own");
            }
        });
        context.waitTicks(SETTLE);
        shot(context, "options-vanilla");
        context.runOnClient(mc -> {
            Screen options = mc.gui.screen();
            mc.gui.setScreen(new VideoSettingsScreen(options, mc, mc.options));
            if (!(mc.gui.screen() instanceof VideoOptionsScreen)) {
                throw new AssertionError("Video Settings opened " + mc.gui.screen() + ", not Russet's");
            }
        });
        context.waitTicks(SETTLE);
        shot(context, "video-settings-button");
        context.runOnClient(mc -> {
            mc.gui.screen().onClose();
            if (mc.gui.screen().getClass() != OptionsScreen.class) {
                throw new AssertionError("Done went to " + mc.gui.screen() + ", not back to the Options menu");
            }
            mc.gui.setScreen(new VideoSettingsScreen(mc.gui.screen(), mc, mc.options));
        });
        key(context, InputConstants.KEY_P, SDLKeycode.SDLK_P, SHIFT);
        context.runOnClient(mc -> {
            if (mc.gui.screen().getClass() != VideoSettingsScreen.class) {
                throw new AssertionError("Shift+P opened " + mc.gui.screen() + ", not Minecraft's Video Settings");
            }
        });
        context.waitTicks(SETTLE);
        shot(context, "vanilla-video");
    }

    private static void size(ClientGameTestContext context, int width, int height, int guiScale) {
        context.getInput().resizeWindow(width, height);
        context.runOnClient(mc -> {
            mc.options.guiScale().set(guiScale);
            mc.resizeGui();
        });
    }

    private static void title(ClientGameTestContext context, String name) {
        context.setScreen(TitleScreen::new);
        context.waitTicks(SETTLE);
        shot(context, name);
    }

    private static void shot(ClientGameTestContext context, String name) {
        context.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix().withDestinationDir(OUT));
    }
}
