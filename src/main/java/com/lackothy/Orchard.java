package com.lackothy;

import com.lackothy.orchard.mac.MacPower;
import com.lackothy.orchard.video.VideoOptions;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The game side of Orchard: the launcher's orchard behind Minecraft's own menus, which
 * {@code ScreenMixin} draws, and Orchard's video settings in place of Minecraft's
 * ({@code VideoOptionsScreen}), with the switches behind them, and what fits the game to the
 * Mac: the frame rate limits on battery and when hot ({@code MacPower}), and the Mac's names for
 * its keys.
 */
public final class Orchard implements ClientModInitializer {

    public static final String MOD_ID = "orchard";
    public static final Logger LOG = LoggerFactory.getLogger("Orchard");

    @Override
    public void onInitializeClient() {
        VideoOptions.load();
        MacPower.start();
        LOG.info("Orchard loaded");
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
