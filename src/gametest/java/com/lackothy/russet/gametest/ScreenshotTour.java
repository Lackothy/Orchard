package com.lackothy.russet.gametest;

import com.lackothy.russet.loading.Ending;
import com.lackothy.russet.loading.Loader;
import com.lackothy.russet.loading.Rehearsal;
import com.lackothy.russet.menu.VideoOptionsScreen;
import com.lackothy.russet.menu.frame.option.OptionRow;
import com.lackothy.russet.menu.layout.LayoutBounds;
import com.lackothy.russet.menu.option.MenuChanges;
import com.lackothy.russet.menu.option.MenuSetting;
import com.lackothy.russet.pack.RussetPack;
import com.lackothy.russet.rain.config.ConfigManager;
import com.lackothy.russet.video.Pages;
import com.lackothy.russet.video.RendererSettings.Api;
import com.lackothy.russet.video.Row;
import com.lackothy.russet.video.VideoOptions;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import mcopt.metal.Switch;
import mcopt.metal.shade.Shade;
import mcopt.metal.shade.ShadeSettingsScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.GraphicsPreset;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.sdl.SDLKeycode;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

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
            weather(context, world);
        }
        packSwitch(context);

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
        List<String> pages = context.computeOnClient(mc -> Pages.all().stream().map(Pages.Page::name).toList());
        List<String> keys = context.computeOnClient(mc -> Pages.all().stream()
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
            shot(context, "video-" + page.toLowerCase(Locale.ROOT));
        }
        openVideo(context, MenuChanges.MENU_PAGE);
        shot(context, "video-menu");
        shaderSettings(context);
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
        openVideo(context, "Metal");
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
