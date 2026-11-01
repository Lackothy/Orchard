package com.lackothy.orchard.video;

import net.minecraft.resources.Identifier;

/** Which Animations switch a moving texture answers to, by the texture's name. */
public enum Animations {
    WATER, LAVA, FIRE, PORTAL, SCULK, OTHER;

    /** Added to each animation's state by {@code AnimationStateMixin}. */
    public interface Kind {
        void orchard$setKind(Animations kind);
    }

    public static Animations of(Identifier sprite) {
        String path = sprite.getPath();
        if (path.contains("water")) {
            return WATER;
        }
        if (path.contains("lava")) {
            return LAVA;
        }
        if (path.contains("fire") || path.contains("campfire")) {
            return FIRE;
        }
        if (path.contains("portal")) {
            return PORTAL;
        }
        if (path.contains("sculk")) {
            return SCULK;
        }
        return OTHER;
    }

    public boolean moves() {
        if (!VideoOptions.ANIMATIONS.on()) {
            return false;
        }
        return switch (this) {
            case WATER -> VideoOptions.ANIMATE_WATER.on();
            case LAVA -> VideoOptions.ANIMATE_LAVA.on();
            case FIRE -> VideoOptions.ANIMATE_FIRE.on();
            case PORTAL -> VideoOptions.ANIMATE_PORTAL.on();
            case SCULK -> VideoOptions.ANIMATE_SCULK.on();
            case OTHER -> VideoOptions.ANIMATE_OTHER.on();
        };
    }
}
