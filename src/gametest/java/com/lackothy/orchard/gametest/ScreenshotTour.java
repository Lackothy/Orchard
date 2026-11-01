package com.lackothy.orchard.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import com.lackothy.orchard.loading.Ending;
import com.lackothy.orchard.loading.Loader;
import com.lackothy.orchard.loading.Rehearsal;
import com.lackothy.orchard.mac.MacPower;
import com.lackothy.orchard.mac.PowerLimit;
import com.lackothy.orchard.video.Pages;
import com.lackothy.orchard.video.RendererSettings.Api;
import com.lackothy.orchard.video.Row;
import com.lackothy.orchard.video.VideoOptions;
import com.lackothy.orchard.menu.VideoOptionsScreen;
import com.lackothy.orchard.menu.frame.option.OptionRow;
import com.lackothy.orchard.menu.layout.LayoutBounds;
import com.lackothy.orchard.menu.option.MenuChanges;
import com.lackothy.orchard.menu.option.MenuSetting;
import com.mojang.blaze3d.platform.Window;
import mcopt.metal.Switch;
import mcopt.metal.shade.Shade;
import mcopt.metal.shade.ShadeSettingsScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.sdl.SDLKeycode;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public final class ScreenshotTour implements FabricClientGameTest {

    private static final Path OUT = Path.of(System.getProperty("orchard.screenshots", "orchard-screenshots"));
    private static final int SETTLE = 10;
    private static final int CONTROL = 0x40;
    private static final int SHIFT = 0x01;

    @Override
    public void runTest(ClientGameTestContext context) {
        size(context, 854, 480, Integer.getInteger("orchard.guiScale", 0));
        if (Boolean.getBoolean("orchard.tour.frames")) {
            ScreenTiming.run(context, OUT);
            context.setScreen(TitleScreen::new);
            return;
        }

        for (String scene : new String[] {"day", "sunset", "night"}) {
            System.setProperty("orchard.scene", scene);
            title(context, "title-" + scene);
        }
        System.clearProperty("orchard.scene");
        for (String[] hour : new String[][] {{"sunrise", "6.4"}, {"midnight", "0"}}) {
            System.setProperty("orchard.sceneHour", hour[1]);
            title(context, "title-auto-" + hour[0]);
        }

        System.setProperty("orchard.sceneHour", "15");
        loadingScreens(context);
        context.setScreen(() -> new SelectWorldScreen(new TitleScreen()));
        context.waitTicks(SETTLE);
        shot(context, "worlds-on-scene");

        openVideo(context, "Video");
        shot(context, "video-from-title");
        menus(context);
        mac(context);

        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getServer().runCommand("time set noon");
            world.getConnection().waitForChunksRender();
            context.getInput().pressKey(InputConstants.KEY_ESCAPE);
            context.waitTicks(SETTLE);
            shot(context, "pause-over-world");
            videoSettings(context);
        }

        for (int[] window : new int[][] {{756, 490}, {640, 480}}) {
            size(context, window[0], window[1], 2);
            title(context, "title-" + (window[0] / 2) + "x" + (window[1] / 2));
        }
        System.clearProperty("orchard.sceneHour");
        context.setScreen(TitleScreen::new);
    }

    private static void loadingScreens(ClientGameTestContext context) {
        context.setScreen(TitleScreen::new);
        context.waitTicks(SETTLE);
        for (Loader loader : Loader.values()) {
            rehearse(context, loader, Ending.SUNRISE, 40, "loading-" + loader.name().toLowerCase(Locale.ROOT));
        }
        System.setProperty("orchard.sceneHour", "23");
        rehearse(context, Loader.RAYS, Ending.SUNRISE, 40, "loading-rays-night");
        rehearse(context, Loader.COMET, Ending.SUNRISE, 94, "loading-end-sunrise-night");
        System.setProperty("orchard.sceneHour", "15");
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
        openVideo(context, "Particles");
        key(context, InputConstants.KEY_F, InputConstants.KEYCODE_F, CONTROL);
        context.getInput().typeChars("fog");
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
                throw new AssertionError("Graphics API should be " + (Switch.ON ? "Orchard's row, on Metal" : "Minecraft's, with why Metal is off")
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
        pending(context);
        tooltip(context);

        size(context, 756, 490, 2);
        openVideo(context, "Video");
        shot(context, "video-video-378x245");
        size(context, 640, 480, 2);
        openVideo(context, "Quality");
        shot(context, "video-quality-320x240");
        size(context, 854, 480, Integer.getInteger("orchard.guiScale", 0));

        context.setScreen(() -> null);
        context.runOnClient(mc -> {
            VideoOptions.FRAME_RATE.set(VideoOptions.FrameRate.DETAILED);
            VideoOptions.COORDINATES.set(true);
        });
        context.waitTicks(SETTLE * 4);
        shot(context, "overlay-in-world");
        context.runOnClient(mc -> {
            VideoOptions.FRAME_RATE.set(VideoOptions.FrameRate.OFF);
            VideoOptions.COORDINATES.set(false);
        });
    }

    private static void pending(ClientGameTestContext context) {
        openVideo(context, "Animations");
        context.runOnClient(mc -> setting(mc.gui.screen(), "All animations").modifyValue(false));
        context.waitTicks(SETTLE);
        shot(context, "video-pending");
        context.runOnClient(mc -> {
            MenuChanges changes = ((VideoOptionsScreen) mc.gui.screen()).changes();
            if (!VideoOptions.ANIMATIONS.on() || !changes.anyOptionChanged()) {
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
                throw new AssertionError("Esc should come back to Orchard's settings, not " + mc.gui.screen());
            }
        });
    }

    private static void openVideo(ClientGameTestContext context, String page) {
        context.runOnClient(mc -> {
            VideoOptionsScreen.startOn(page);
            mc.gui.setScreen(new VideoSettingsScreen(new OptionsScreen(new TitleScreen(), mc.options), mc, mc.options));
            if (!(mc.gui.screen() instanceof VideoOptionsScreen)) {
                throw new AssertionError("Video Settings opened " + mc.gui.screen() + ", not Orchard's");
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
                throw new AssertionError("Video Settings opened " + mc.gui.screen() + ", not Orchard's");
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

    private static void mac(ClientGameTestContext context) {
        context.runOnClient(mc -> {
            String command = InputConstants.getKey("key.keyboard.left.win").getDisplayName().getString();
            String option = InputConstants.getKey("key.keyboard.right.alt").getDisplayName().getString();
            if (MacPower.MAC && (!command.equals("Left Command") || !option.equals("Right Option"))) {
                throw new AssertionError("Mac keys are named " + command + " and " + option);
            }
            List<VideoOptions.Choice<VideoOptions.Limit>> limits =
                List.of(VideoOptions.BATTERY_LIMIT, VideoOptions.LOW_POWER_LIMIT, VideoOptions.HOT_LIMIT);
            List<VideoOptions.Limit> before = limits.stream().map(VideoOptions.Choice::get).toList();
            limits.forEach(limit -> limit.set(VideoOptions.Limit.FPS_30));
            int paced = mc.getFramerateLimitTracker().getFramerateLimit();
            System.out.println("[Orchard tour] " + MacPower.describe(MacPower.state()) + ", cap " + PowerLimit.now() + ", paced to " + paced);
            if (PowerLimit.now() > 0 && paced > 30) {
                throw new AssertionError("Paced to " + paced + " with a cap of 30");
            }
            for (int i = 0; i < limits.size(); i++) {
                limits.get(i).set(before.get(i));
            }
        });
        openVideo(context, "Battery");
        shot(context, "video-battery");
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
