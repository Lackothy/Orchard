package com.lackothy.russet.fabulous;

import com.lackothy.Russet;
import com.lackothy.russet.video.VideoOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.AtlasIds;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class EffectParticles {

    static final List<String> EFFECTS = List.of("absorption", "bad_omen", "blindness", "breath_of_the_nautilus",
        "conduit_power", "darkness", "dolphins_grace", "fire_resistance", "glowing", "haste", "health_boost",
        "hero_of_the_village", "hunger", "infested", "instant_damage", "instant_health", "invisibility", "jump_boost",
        "levitation", "luck", "mining_fatigue", "nausea", "night_vision", "oozing", "poison", "raid_omen",
        "regeneration", "resistance", "saturation", "slow_falling", "slowness", "speed", "strength", "trial_omen",
        "unluck", "water_breathing", "weakness", "weaving", "wind_charged", "wither");

    private static Map<Integer, String> byColour;
    private static Map<ParticleType<?>, String> byType;

    private static String pending;
    private static float pendingAlpha;

    private EffectParticles() {
    }

    public static void claim(ParticleOptions vanilla) {
        if (!VideoOptions.EFFECT_PARTICLES.on()) {
            return;
        }
        if (byColour == null) {
            index();
        }
        if (vanilla instanceof ColorParticleOption colour && colour.getType() == ParticleTypes.ENTITY_EFFECT) {
            pending = byColour.get(rgb(colour.getRed(), colour.getGreen(), colour.getBlue()));
            pendingAlpha = colour.getAlpha();
        } else {
            pending = byType.get(vanilla.getType());
            pendingAlpha = 1;
        }
    }

    public static void release() {
        pending = null;
    }

    public static Particle create(ClientLevel level, double x, double y, double z) {
        String effect = pending;
        if (effect == null) {
            return null;
        }
        pending = null;
        TextureAtlas atlas = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.PARTICLES);
        return new EffectParticle(level, x, y, z, level.getRandom(), atlas.getSprite(Russet.id("effect/" + effect + "_1")),
            atlas.getSprite(Russet.id("effect/" + effect + "_2")), pendingAlpha);
    }

    private static void index() {
        Map<Integer, String> colours = new HashMap<>();
        BuiltInRegistries.MOB_EFFECT.entrySet().forEach(entry -> {
            String name = entry.getKey().identifier().getPath();
            if (EFFECTS.contains(name)) {
                colours.putIfAbsent(entry.getValue().getColor() & 0xFFFFFF, name);
            }
        });
        byColour = colours;
        byType = Map.of(ParticleTypes.TRIAL_OMEN, "trial_omen", ParticleTypes.RAID_OMEN, "raid_omen",
            ParticleTypes.INFESTED, "infested", ParticleTypes.ITEM_SLIME, "oozing",
            ParticleTypes.ITEM_COBWEB, "weaving", ParticleTypes.SMALL_GUST, "wind_charged");
    }

    static int rgb(float r, float g, float b) {
        return Math.round(r * 255) << 16 | Math.round(g * 255) << 8 | Math.round(b * 255);
    }
}
