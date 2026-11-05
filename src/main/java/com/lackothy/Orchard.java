package com.lackothy;

import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Orchard {

    public static final String MOD_ID = "orchard";
    public static final String METAL_ID = "orchard_metal";
    public static final Logger LOG = LoggerFactory.getLogger("Orchard");

    private Orchard() {
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
