package com.lackothy;

import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The id that names the mod's resources, the mod's own id, and the log. It starts from {@code OrchardMetal}. */
public final class Orchard {

    public static final String MOD_ID = "orchard";
    /** The mod's id. Its resources are still under {@link #MOD_ID}. */
    public static final String METAL_ID = "orchard_metal";
    public static final Logger LOG = LoggerFactory.getLogger("Orchard");

    private Orchard() {
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
