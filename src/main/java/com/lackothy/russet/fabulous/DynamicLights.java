package com.lackothy.russet.fabulous;

import com.lackothy.russet.mixin.fabulous.LevelExtractorAccess;
import com.lackothy.russet.video.VideoOptions;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import mcopt.metal.Switch;
import mcopt.metal.shade.light.DynLightConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.LightmapRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.animal.squid.GlowSquid;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.cubemob.MagmaCube;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.arrow.SpectralArrow;
import net.minecraft.world.entity.projectile.hurtingprojectile.DragonFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DynamicLights {

    public static final boolean STOOD_DOWN = FabricLoader.getInstance().isModLoaded("lambdynlights");

    private static final float SEEN = 2F / 255;
    private static final int UNSEEN_TICKS = 20;
    private static final int WHOLE_BLOCKS = 16;
    private static final float[] NO_LIGHTS = new float[0];
    private static final Built NONE = new Built(NO_LIGHTS, Reach.SHORT.falloff, false);

    private static final Map<Item, Integer> ITEMS = Map.ofEntries(
        Map.entry(Items.LAVA_BUCKET, 15), Map.entry(Items.NETHER_STAR, 12), Map.entry(Items.FIRE_CHARGE, 12),
        Map.entry(Items.BLAZE_ROD, 10), Map.entry(Items.GLOWSTONE_DUST, 10), Map.entry(Items.GLOW_BERRIES, 10),
        Map.entry(Items.BLAZE_POWDER, 8), Map.entry(Items.MAGMA_CREAM, 8), Map.entry(Items.SPECTRAL_ARROW, 8),
        Map.entry(Items.PRISMARINE_CRYSTALS, 8), Map.entry(Items.GLOW_INK_SAC, 6), Map.entry(Items.EXPERIENCE_BOTTLE, 6),
        Map.entry(Items.DRAGON_BREATH, 6));

    private static final Set<Item> NEEDS_AIR = Set.of(Items.TORCH, Items.SOUL_TORCH, Items.COPPER_TORCH, Items.CAMPFIRE,
        Items.SOUL_CAMPFIRE, Items.FIRE_CHARGE, Items.BLAZE_POWDER);

    private static volatile Built built = NONE;
    private static Map<Integer, Light> placed = Map.of();
    private static ClientLevel builtFor;
    private static int ticks;
    private static int unseen;

    private static volatile float[] moving = NO_LIGHTS;
    private static volatile float movingFalloff = Reach.SHORT.falloff;

    public enum Reach {
        SHORT("Short", 2), MEDIUM("Medium", 1.5F), LONG("Long", 1);

        public final String label;
        final float falloff;

        Reach(String label, float falloff) {
            this.label = label;
            this.falloff = falloff;
        }
    }

    public record Built(float[] lights, float falloff, boolean fine) {
    }

    private record Light(float x, float y, float z, int level) {
        boolean movedFrom(Light other, float step) {
            float dx = x - other.x;
            float dy = y - other.y;
            float dz = z - other.z;
            return level != other.level || dx * dx + dy * dy + dz * dz >= step * step;
        }
    }

    private record Found(Entity entity, double distance, int level) {
    }

    private DynamicLights() {
    }

    public static boolean on() {
        return !STOOD_DOWN && VideoOptions.DYNAMIC_LIGHTS.on() && !(Switch.ON && DynLightConfig.ENABLED);
    }

    public static void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level != builtFor) {
            builtFor = level;
            placed = Map.of();
            built = NONE;
            moving = NO_LIGHTS;
        }
        Entity camera = mc.getCameraEntity();
        LightmapUniforms lightmap = uniforms(mc.gameRenderer.gameRenderState().lightmapRenderState);
        unseen = lightmap != null && lightmap.change(0, 0, 15) < SEEN ? unseen + 1 : 0;
        if (level == null || camera == null || !on() || unseen > UNSEEN_TICKS) {
            moving = NO_LIGHTS;
            if (!placed.isEmpty() && level != null) {
                LongSet dirty = new LongOpenHashSet();
                for (Light light : placed.values()) {
                    sections(light, built.falloff(), dirty);
                }
                rebuild(mc, Map.of(), built.falloff(), built.fine(), dirty);
            }
            return;
        }

        double range = VideoOptions.LIGHT_DISTANCE.get();
        List<Found> found = new ArrayList<>();
        for (Entity entity : level.entitiesForRendering()) {
            double distance = entity.distanceToSqr(camera);
            if (distance <= range * range && (entity != mc.player || VideoOptions.LIGHT_YOURS.on())) {
                int light = light(entity);
                if (light > 0) {
                    found.add(new Found(entity, distance, light));
                }
            }
        }
        found.sort(Comparator.comparingDouble(Found::distance));
        float falloff = VideoOptions.LIGHT_REACH.get().falloff;
        int most = VideoOptions.LIGHT_MOST.get();
        LightmapUniforms steady = lightmap == null ? null : lightmap.steady();
        List<Found> nearest = new ArrayList<>();
        for (Found light : found) {
            if (nearest.size() == most) {
                break;
            }
            if (steady == null || shows(level, steady, light, falloff, placed.containsKey(light.entity().getId()) ? SEEN / 2 : SEEN)) {
                nearest.add(light);
            }
        }

        float[] now = new float[nearest.size() * 7];
        int i = 0;
        for (Found light : nearest) {
            Entity entity = light.entity();
            Vec3 at = entity.getBoundingBox().getCenter();
            now[i++] = (float) (at.x - entity.getX() + entity.xo);
            now[i++] = (float) (at.y - entity.getY() + entity.yo);
            now[i++] = (float) (at.z - entity.getZ() + entity.zo);
            now[i++] = (float) at.x;
            now[i++] = (float) at.y;
            now[i++] = (float) at.z;
            now[i++] = light.level();
        }
        moving = now;
        movingFalloff = falloff;

        if (++ticks % VideoOptions.LIGHT_UPDATE_TICKS.get() != 0) {
            return;
        }
        int smoothness = VideoOptions.LIGHT_SMOOTHNESS.get();
        boolean fine = smoothness < WHOLE_BLOCKS;
        float step = smoothness / 16F;
        Built was = built;
        boolean all = was.falloff() != falloff || was.fine() != fine;
        int limit = all ? 0 : VideoOptions.LIGHT_REDRAWS.get();
        Set<Integer> kept = new HashSet<>();
        for (Found light : nearest) {
            kept.add(light.entity().getId());
        }
        LongSet dirty = new LongOpenHashSet();
        for (Map.Entry<Integer, Light> there : placed.entrySet()) {
            if (!kept.contains(there.getKey())) {
                sections(there.getValue(), was.falloff(), dirty);
            }
        }
        Map<Integer, Light> next = new HashMap<>();
        LongSet these = new LongOpenHashSet();
        for (Found light : nearest) {
            Vec3 at = light.entity().getBoundingBox().getCenter();
            Light here = fine ? new Light((float) at.x, (float) at.y, (float) at.z, light.level())
                : new Light(Mth.floor(at.x) + 0.5F, Mth.floor(at.y) + 0.5F, Mth.floor(at.z) + 0.5F, light.level());
            Light there = placed.get(light.entity().getId());
            if (!all && there != null && !here.movedFrom(there, step)) {
                next.put(light.entity().getId(), there);
                continue;
            }
            these.clear();
            if (there != null) {
                sections(there, was.falloff(), these);
            }
            sections(here, falloff, these);
            if (limit > 0 && !dirty.isEmpty() && dirty.size() + (int) these.longStream().filter(s -> !dirty.contains(s)).count() > limit) {
                if (there != null) {
                    next.put(light.entity().getId(), there);
                }
                continue;
            }
            next.put(light.entity().getId(), here);
            dirty.addAll(these);
        }
        if (!dirty.isEmpty()) {
            rebuild(mc, next, falloff, fine, dirty);
        }
    }

    private static LightmapUniforms uniforms(LightmapRenderState state) {
        if (state.blockLightTint == null || state.skyLightColor == null || state.ambientColor == null || state.nightVisionColor == null) {
            return null;
        }
        return new LightmapUniforms(state.skyFactor, state.blockFactor, state.nightVisionEffectIntensity, state.darknessEffectScale,
            state.bossOverlayWorldDarkening, state.brightness, rgb(state.blockLightTint), rgb(state.skyLightColor),
            rgb(state.ambientColor), rgb(state.nightVisionColor));
    }

    private static float[] rgb(Vector3fc colour) {
        return new float[] {colour.x(), colour.y(), colour.z()};
    }

    private static boolean shows(ClientLevel level, LightmapUniforms lightmap, Found light, float falloff, float by) {
        BlockPos at = BlockPos.containing(light.entity().getBoundingBox().getCenter());
        if (shows(level, lightmap, at, light.level(), by)) {
            return true;
        }
        int further = Math.round(light.level() - 2 * falloff);
        if (further > 0) {
            for (Direction direction : Direction.values()) {
                BlockPos near = at.relative(direction, 2);
                if (!level.getBlockState(near).canOcclude() && shows(level, lightmap, near, further, by)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean shows(ClientLevel level, LightmapUniforms lightmap, BlockPos pos, int light, float by) {
        int block = level.getBrightness(LightLayer.BLOCK, pos);
        return light > block && lightmap.change(level.getBrightness(LightLayer.SKY, pos), block, light) >= by;
    }

    public static int raise(int packed, BlockPos pos) {
        Built now = built;
        return now.lights().length == 0 ? packed : raise(packed, now, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    public static int raise(int packed, int x, int y, int z) {
        Built now = built;
        return now.lights().length == 0 ? packed : raise(packed, now, x + 0.5, y + 0.5, z + 0.5);
    }

    public static Built near(int x0, int y0, int z0, int x1, int y1, int z1) {
        Built now = built;
        float[] lights = now.lights();
        float[] out = new float[lights.length];
        int n = 0;
        for (int i = 0; i < lights.length; i += 4) {
            float reach = lights[i + 3] / now.falloff();
            double dx = gap(lights[i], x0, x1);
            double dy = gap(lights[i + 1], y0, y1);
            double dz = gap(lights[i + 2], z0, z1);
            if (dx * dx + dy * dy + dz * dz < reach * reach) {
                System.arraycopy(lights, i, out, n, 4);
                n += 4;
            }
        }
        return n == lights.length ? now : new Built(n == 0 ? NO_LIGHTS : java.util.Arrays.copyOf(out, n), now.falloff(), now.fine());
    }

    private static double gap(float at, int min, int max) {
        return at - Math.max(min + 0.5, Math.min(max + 0.5, at));
    }

    public static int raise(int packed, Built lights, int x, int y, int z) {
        return lights.lights().length == 0 ? packed : raise(packed, lights, x + 0.5, y + 0.5, z + 0.5);
    }

    public static int raise(int packed, Entity entity, float partialTick) {
        float[] now = moving;
        if (now.length == 0) {
            return packed;
        }
        float falloff = movingFalloff;
        Vec3 at = entity.getLightProbePosition(partialTick);
        float best = 0;
        for (int i = 0; i < now.length; i += 7) {
            best = Math.max(best, level(Mth.lerp(partialTick, now[i], now[i + 3]), Mth.lerp(partialTick, now[i + 1], now[i + 4]),
                Mth.lerp(partialTick, now[i + 2], now[i + 5]), now[i + 6], falloff, at.x, at.y, at.z));
        }
        return raised(packed, best, true);
    }

    private static int raise(int packed, Built now, double x, double y, double z) {
        float[] lights = now.lights();
        float best = 0;
        for (int i = 0; i < lights.length; i += 4) {
            best = Math.max(best, level(lights[i], lights[i + 1], lights[i + 2], lights[i + 3], now.falloff(), x, y, z));
        }
        return raised(packed, best, now.fine());
    }

    private static float level(float lx, float ly, float lz, float level, float falloff, double x, double y, double z) {
        double dx = x - lx;
        double dy = y - ly;
        double dz = z - lz;
        float reach = level / falloff;
        double distance = dx * dx + dy * dy + dz * dz;
        return distance < reach * reach ? level - (float) Math.sqrt(distance) * falloff : 0;
    }

    private static int raised(int packed, float level, boolean fine) {
        int light = fine ? Math.min(15 * 16, Math.round(level * 16)) : Math.min(15, Math.round(level)) << 4;
        return light > (packed & 0xFFFF) ? packed & 0xFFFF0000 | light : packed;
    }

    private static void rebuild(Minecraft mc, Map<Integer, Light> next, float falloff, boolean fine, LongSet dirty) {
        placed = next;
        float[] out = new float[next.size() * 4];
        int i = 0;
        for (Light light : next.values()) {
            out[i++] = light.x();
            out[i++] = light.y();
            out[i++] = light.z();
            out[i++] = light.level();
        }
        built = new Built(out, falloff, fine);
        LevelExtractorAccess extractor = (LevelExtractorAccess) mc.levelExtractor;
        for (long section : dirty) {
            extractor.russet$setSectionDirty(SectionPos.x(section), SectionPos.y(section), SectionPos.z(section), true);
        }
    }

    private static void sections(Light light, float falloff, LongSet into) {
        float reach = light.level() / falloff;
        for (int x = SectionPos.blockToSectionCoord(light.x() - reach); x <= SectionPos.blockToSectionCoord(light.x() + reach); x++) {
            for (int y = SectionPos.blockToSectionCoord(light.y() - reach); y <= SectionPos.blockToSectionCoord(light.y() + reach); y++) {
                for (int z = SectionPos.blockToSectionCoord(light.z() - reach); z <= SectionPos.blockToSectionCoord(light.z() + reach); z++) {
                    into.add(SectionPos.asLong(x, y, z));
                }
            }
        }
    }

    static int light(Entity entity) {
        int light = VideoOptions.LIGHT_BURNING.on() && entity.isOnFire() ? 14 : 0;
        boolean mobs = VideoOptions.LIGHT_MOBS.on();
        boolean explosives = VideoOptions.LIGHT_EXPLOSIVES.on();
        boolean projectiles = VideoOptions.LIGHT_PROJECTILES.on();
        switch (entity) {
            case LivingEntity living -> {
                if (VideoOptions.LIGHT_HELD.on()) {
                    boolean wet = living.isUnderWater();
                    light = Math.max(light, Math.max(item(living.getMainHandItem(), wet), item(living.getOffhandItem(), wet)));
                }
                light = Math.max(light, switch (living) {
                    case Blaze _ -> mobs ? 10 : 0;
                    case MagmaCube _, Allay _ -> mobs ? 8 : 0;
                    case GlowSquid squid -> mobs && squid.getDarkTicksRemaining() <= 0 ? 10 : 0;
                    case Creeper creeper -> explosives ? Math.round(creeper.getSwelling(0) * 10) : 0;
                    default -> 0;
                });
            }
            case ItemEntity item when VideoOptions.LIGHT_ITEMS.on() -> light = Math.max(light, item(item.getItem(), item.isUnderWater()));
            case ItemFrame frame when VideoOptions.LIGHT_ITEMS.on() -> light = Math.max(light, item(frame.getItem(), false));
            case FallingBlockEntity block when VideoOptions.LIGHT_ITEMS.on() ->
                light = Math.max(light, block.getBlockState().getLightEmission());
            case PrimedTnt _ when explosives -> light = Math.max(light, 10);
            case LargeFireball _, DragonFireball _ -> light = projectiles ? 14 : light;
            case SmallFireball _ -> light = projectiles ? 10 : light;
            case SpectralArrow _, FireworkRocketEntity _ when projectiles -> light = Math.max(light, 8);
            case ExperienceOrb _ when projectiles -> light = Math.max(light, 6);
            default -> {
            }
        }
        return light;
    }

    static int item(ItemStack stack, boolean wet) {
        if (stack.isEmpty()) {
            return 0;
        }
        Item item = stack.getItem();
        if (wet && VideoOptions.LIGHT_OUT_UNDERWATER.on() && NEEDS_AIR.contains(item)) {
            return 0;
        }
        Integer own = ITEMS.get(item);
        if (own != null) {
            return own;
        }
        return item instanceof BlockItem block ? block.getBlock().defaultBlockState().getLightEmission() : 0;
    }
}
