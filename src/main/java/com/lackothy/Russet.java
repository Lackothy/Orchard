package com.lackothy;

import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The mod's id, which also names its resources, and the log. It starts from {@code RussetClient}. */
public final class Russet {

    public static final String MOD_ID = "russet";
    public static final Logger LOG = LoggerFactory.getLogger("Russet");

    private Russet() {
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
