package com.lackothy.russet.rain;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.IdentifierException;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceMetadata;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.data.AtlasIds;

import java.awt.Color;
import java.io.IOException;
import java.net.URI;
import java.util.Optional;
import java.util.stream.Stream;

public class VersionUtil {

    public static void hotbarMessage(Object o) {
        hotbarMessage(Component.literal(String.valueOf(o)));
    }
    public static void hotbarMessage(Component component) {
        Minecraft.getInstance().gui.hud.setOverlayMessage(component, false);
    }

    public static boolean isModLoaded(String modid) {
        return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded(modid);
    }

    @SuppressWarnings("removal")
    public static Identifier getId(String path) {
        return Identifier.fromNamespaceAndPath(ParticleRain.NAMESPACE, path);
    }
    @SuppressWarnings("removal")
    public static Identifier getMcId(String path) {
        return Identifier.withDefaultNamespace(path);
    }
    @SuppressWarnings("removal")
    public static Identifier getId(String namespace, String path) {
        return Identifier.fromNamespaceAndPath(namespace, path);
    }

    public static Identifier parseId(String string) {
        try {
            return Identifier.parse(string);
        } catch (IdentifierException e) {
            return null;
        }
    }
    public static ClipContext getClipContext(Vec3 clipStart, Vec3 clipEnd) {
        return new ClipContext(clipStart, clipEnd, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, CollisionContext.empty());
    }

    public static SpriteContents loadSplashSprite(int i) throws IOException {
        final Identifier location = getMcId("textures/particle/splash_" + i + ".png");
        Resource resource = Minecraft.getInstance().getResourceManager().getResourceOrThrow(location);
        ResourceMetadata resourceMetadata = resource.metadata();
        NativeImage splashImage = TextureUtil.loadTexture(resource);
        TextureUtil.desaturate(splashImage);
        Optional<AnimationMetadataSection> animationMetadata = resourceMetadata.getSection(
                                AnimationMetadataSection.TYPE

        );
        FrameSize frameSize;
        if (animationMetadata.isPresent()) {
            frameSize = animationMetadata.get().calculateFrameSize(splashImage.getWidth(), splashImage.getHeight());
        } else {
            frameSize = new FrameSize(splashImage.getWidth(), splashImage.getHeight());
        }
        return new SpriteContents(
                getId("splash_" + i),
                frameSize,
                splashImage
                        );
    }

    public static Biome.Precipitation getPrecipitationAt(Level level, Holder<Biome> biome, BlockPos blockPos) {
        return biome.value().getPrecipitationAt(blockPos, level.getSeaLevel());
    }

    public static Color getMapColor(ClientLevel level, BlockPos blockPos) {
        return new Color(level.getBlockState(level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, blockPos).below()).getBlock().defaultMapColor().calculateARGBColor(MapColor.Brightness.NORMAL));
    }

    public static void schedule(Runnable task) {
        Minecraft.getInstance().schedule(task);
    }

    public static <T> Stream<TagKey<T>> getTagIds(Registry<T> registry) {
        return registry.listTagIds();
    }

    public static <T> Registry<T> getRegistry(ResourceKey<Registry<T>> key) {
        return Minecraft.getInstance().level.registryAccess().lookupOrThrow(key);
    }

    public static int getCloudHeight(ClientLevel level, BlockPos pos) {
        return level.environmentAttributes().getValue(EnvironmentAttributes.CLOUD_HEIGHT, pos).intValue();
    }

    public static int getMaxY(Level level) {
        return level.getMaxY();
    }

    public static int getMinY(Level level) {
        return level.getMinY();
    }

    public static TextureAtlasSprite getSprite(Identifier id) {
        return Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.PARTICLES).getSprite(id);
    }

    public static SpriteContents newNonAnimatedSpriteContents(String id, FrameSize frameSize, NativeImage sprite) {
        return new SpriteContents(getId(id), frameSize, sprite);
    }

    public static float getFOV() {
        Options options = Minecraft.getInstance().options;
        float fov = options.fov().get();
        boolean firstPerson = options.getCameraType().isFirstPerson();
        float effectScale = options.fovEffectScale().get().floatValue();
        return fov * Minecraft.getInstance().player.getFieldOfViewModifier(firstPerson, effectScale);
    }

    public static void openUri(URI uri) {
        com.mojang.blaze3d.Blaze3D.openUri(uri);
    }

    public static Vec3 camPos(Camera cam) {
        return cam.position();
    }

    public static Identifier getKeyId(ResourceKey key) {
        return key.identifier();
    }

    public static Color getBiomeFogColor(Level level, BlockPos pos) {
        return new Color(0xB9D3FB);
    }

    static void addChatMsg(String message) {
        Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(Component.literal(message));
    }
}
