package com.lackothy.russet.rain.particle;

import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.*;
import org.joml.Math;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.util.RandomSource;

import com.lackothy.russet.rain.ParticleLoader;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import com.lackothy.russet.rain.WindManager;
import com.lackothy.russet.rain.compat.RainShieldCompat;
import com.lackothy.russet.rain.compat.WindLinkCompat;
import com.lackothy.russet.rain.VersionUtil;
import com.lackothy.russet.rain.config.ParticleData;

import java.util.Set;
import java.util.List;

import static com.lackothy.russet.rain.config.ConfigManager.getConfig;

public class CustomParticle extends WeatherParticle {

    private static final Set<String> dontTintTheseWhenWaterTintDisabled = Set.of(
            "russet:rain_0", "russet:rain_1", "russet:rain_2", "russet:rain_3",
            "russet:rain_4", "russet:rain_5", "russet:rain_6", "russet:rain_7",
            "russet:rain_8", "russet:rain_9", "russet:rain_10", "russet:rain_11",
            "russet:rain_12", "russet:rain_13", "russet:rain_14", "russet:rain_15",
            "russet:heavy_rain_0", "russet:heavy_rain_1","russet:heavy_rain_2", "russet:heavy_rain_3"
    );
    public ParticleData data;
    private float oCollisionAnimProgress = 1;
    private float collisionAnimProgress = 1;
    public float speed = 0;
    float rotationVariation;
    BlockHitResult collision = null;
    float baseTemp;
    float distance;
    int maxEdgeBounces = 3;
    float baseSize;
    int edgeBounces = 0;

    private static float sizeFor(ParticleData data) {
        float lent = WindLinkCompat.spriteSizeFor(data.id);
        return (lent > 0 ? lent : data.size) * WindLinkCompat.sizeScale(data.id);
    }

    private static TextureAtlasSprite pickSprite(ClientLevel level, ParticleData data) {
        List<String> sprites = WindLinkCompat.spritesFor(data.id);
        if (sprites == null) sprites = data.spriteLocations;
        return VersionUtil.getSprite(VersionUtil.parseId(sprites.get(level.getRandom().nextInt(sprites.size()))));
    }

    public CustomParticle(ClientLevel level, double x, double y, double z, ParticleData data) {
        super(level, x, y, z, pickSprite(level, data));

        this.data = data;
        this.gravity = data.gravity;
        this.yd = (data.spawnPos.equals(ParticleData.SpawnPos.SKY)) ? -gravity : data.bounciness;
        WindManager.applySpawnWind(this, data, level);
        this.baseSize = sizeFor(data);
        this.quadSize = baseSize;
        this.alpha = 0;
        this.hasPhysics = false;
        this.setSize(quadSize, quadSize);
        this.lifetime = getConfig().perf.particleDistance * 100;
        this.pos = new BlockPos.MutableBlockPos(x, y, z);
        this.oPos = new BlockPos.MutableBlockPos(x, y, z);
        this.baseTemp = level.getBiome(this.pos).value().getBaseTemperature();
        this.lifetime = data.lifetime;
        this.rotationVariation = data.rotationAmount * ((random.nextFloat() - 0.5F) * 2.0F);
        if (data.distanceScaling != 0) {
            this.quadSize = getDistanceSize();
        } else {
            this.quadSize = baseSize;
        }
        if (getConfig().compat.waterTint || !dontTintTheseWhenWaterTintDisabled.contains(this.sprite.contents().name().toString())) {
            data.tintType.applyTint(this, level, this.pos, data);
        }
        if (data.rotationType == ParticleData.RotationType.HORIZONTAL) this.roll = Mth.HALF_PI * level.getRandom().nextInt(4);
        this.oRoll = this.roll;
    }

    public void tick() {
        oQuadSize = quadSize;
        super.tick();
        distance = (float) VersionUtil.camPos(Minecraft.getInstance().gameRenderer.mainCamera()).distanceTo(new Vec3(x, y, z));
        if (distance > data.spawnPos.renderDistance() + 0.5) {
            remove();
        }
        if (!pos.equals(oPos)) {
            onPositionUpdate();
            oPos.set(pos);
        }
        speed = (float) new Vec3(xd, yd, zd).length();
        tickFading();
        if (data.distanceScaling != 0 && !doCollisionAnim) quadSize = getDistanceSize();
        if (data.rotationAmount != 0) {
            oRoll = roll;
            roll += rotationVariation * speed;
        }
        if (data.animateSprites) {
            int i = (this.age - 1) % (data.spriteLocations.size() - 1);
            String sprite = data.spriteLocations.get(i);
            this.setSprite(VersionUtil.getSprite(VersionUtil.parseId(sprite)));
        }
        float rainLevel = level.getRainLevel(1);
        if (data.tintType == ParticleData.TintType.FOG && rainLevel > 0 && rainLevel < 1) {
            data.tintType.applyTint(this, level, pos, data);
        }
        WindManager.applyWind(this, data, level);
        tickCollisions();
    }

    public void tickFading() {
        if (data.fadeType == ParticleData.FadeType.IN_AND_OUT) {
            int halfLife = lifetime / 2;
            this.alpha = (age < halfLife ? (float) age / (halfLife) : (float) (-age + lifetime) / halfLife) * data.opacity;
        } else if (data.fadeType == ParticleData.FadeType.OUT) {
            int halfLife = lifetime / 2;
            this.alpha = (float) (-age + lifetime) / halfLife * data.opacity;
        } else {
            float renderDistance = data.spawnPos.renderDistance();
            int falloff = 3;
            float falloffDist = renderDistance / falloff;
            alpha = Mth.lerp(Mth.clamp(((distance + falloffDist) / falloffDist) - falloff, 0, 1), data.opacity, 0);
        }
        if (data.rotationType == ParticleData.RotationType.VERTICAL) {
            float angle = getPitchAngleToCamera();
            float newAlpha = -(Math.min(angle, QUARTER_PI) / QUARTER_PI) + 1;
            alpha = Math.min(alpha, newAlpha);
        } else if (data.rotationType == ParticleData.RotationType.HORIZONTAL) {
            float angle = getPitchAngleToCamera();
            float newAlpha = Math.min(angle, 0.4f) / 0.4f;
            alpha = Math.min(alpha, newAlpha);
        }
        alpha *= data.weather.getWeatherLevel(level);
    }
    private static final float QUARTER_PI = 0.7853982f;
    private float getPitchAngleToCamera() {
        Vec3 camPos = VersionUtil.camPos(Minecraft.getInstance().gameRenderer.mainCamera());
        float offsetX = (float) (this.x - camPos.x());
        float offsetY = (float) (this.y - camPos.y());
        float offsetZ = (float) (this.z - camPos.z());
        float localZ = Vector2f.length(offsetX, offsetZ);
        return Mth.abs(Math.atan2(offsetY, localZ));
    }

    public static boolean weatherNotAllowed(Level level, BlockPos blockPos) {
        return RainShieldCompat.cancelRain(level, blockPos);
    }
    public boolean climateChanged() {
        return !getConfig().compat.crossBiomeBorder
                && level.getBiome(pos).value().getBaseTemperature() != baseTemp
                && !data.precipitation.contains(VersionUtil.getPrecipitationAt(level, level.getBiome(pos), pos));
    }

    public void onPositionUpdate() {
        if (climateChanged() || weatherNotAllowed(level, pos)
        ) {
            doCollisionAnim = true;
            return;
        }

        BlockState state = level.getBlockState(pos);
        boolean ignoresBlock = getConfig().compat.weatherIgnoreBlocks.contains(state.typeHolder());
        boolean isInFluid = !level.getFluidState(pos).isEmpty();

        if (!ignoresBlock || isInFluid) {
            if (level.getBlockState(pos).isCollisionShapeFullBlock(level, pos)) {
                this.remove();
            }
        }
    }

    public void tickCollisions() {
        Vec3 deltaMotion = new Vec3(xd, yd, zd);
        float length = (float) (quadSize + deltaMotion.length());
        Vec3 quadCenterPos = new Vec3(x, y, z);
        Vec3 quadEdgePos = deltaMotion.normalize().multiply(length, length, length).add(x, y, z);
        final BlockHitResult hitResult = level.clip(VersionUtil.getClipContext(quadCenterPos, quadEdgePos));
        if (!hitResult.getType().equals(HitResult.Type.MISS) && !doCollisionAnim) {
            onCollision(hitResult);
        }
    }

    public void onCollision(BlockHitResult hitResult) {

        BlockState state = level.getBlockState(hitResult.getBlockPos());
        if(getConfig().compat.weatherIgnoreBlocks.contains(state.typeHolder())) {
            return;
        }

        if (!state.getFluidState().isSource() && data.bounciness != 0) {
            final Vector3f normal = hitResult.getDirection().step();
            final float bounciness = data.bounciness * speed * 6;
            if (normal.y == 0) {
                if (edgeBounces++ >= maxEdgeBounces) doCollisionAnim = true;
                this.yd += 0.5 * bounciness;
            }
            this.xd += normal.x * bounciness;
            this.yd += normal.y * bounciness;
            this.zd += normal.z * bounciness;
        } else {
            collision = hitResult;
            doCollisionAnim = true;
        }
    }

    public void tickCollisionAnim() {
        float distanceTravelled = speed;
        oCollisionAnimProgress = collisionAnimProgress;
        collisionAnimProgress -= distanceTravelled;
        if (!data.rotationType.equals(ParticleData.RotationType.RELATIVE_VELOCITY)) {
            quadSize = Math.max(quadSize - distanceTravelled, 0);
        }
        if (collisionAnimProgress <= 0) { remove(); }
    }

    public float getDistanceSize() {
        float scaledSize = baseSize * distance * data.distanceScaling;
        float size = baseSize + scaledSize;
        if (data.distanceScaling != 1) return size;
        float max = 70;
        float fov = Math.min(VersionUtil.getFOV(), max) / max;
        return size * fov;
    }

    @Override
        public SingleQuadParticle.Layer getLayer() {
        return data.renderType.get();
    }

    @Override
    public void extract(QuadParticleRenderState h, Camera camera, float tickPercent) {
        data.rotationType.render(h, camera, tickPercent, this);
    }

    public void renderVerticalQuad(QuadParticleRenderState h, Camera camera, float tickPercent) {
        Vec3 camPos = VersionUtil.camPos(camera);
        float offsetX = (float) (Mth.lerp(tickPercent, this.xo, this.x) - camPos.x());
        float offsetY = (float) (Mth.lerp(tickPercent, this.yo, this.y) - camPos.y());
        float offsetZ = (float) (Mth.lerp(tickPercent, this.zo, this.z) - camPos.z());

        Quaternionf quaternion = new Quaternionf();
        quaternion.rotateY(Math.atan2(offsetX, offsetZ));
        quaternion.rotateZ(Mth.lerp(tickPercent, this.oRoll, this.roll));
        turnBackfaceFlipways(quaternion, new Vector3f(offsetX, offsetY, offsetZ));
        this.renderRotatedQuad(h, quaternion, offsetX, offsetY, offsetZ, tickPercent);
    }

    public void renderRelativeVelocityQuad(QuadParticleRenderState h, Camera camera, float tickPercent) {
        final Vec3 camD = Minecraft.getInstance().getCameraEntity().getDeltaMovement();
        Vector3f deltaMotion = new Vector3f((float) (this.xd - camD.x), (float) (this.yd - camD.y), (float) (this.zd - camD.z));
        renderVelocityQuad(h, camera, tickPercent, deltaMotion);
    }

    public void renderWorldVelocityQuad(QuadParticleRenderState h, Camera camera, float tickPercent) {
        Vector3f deltaMotion = new Vector3f((float) xd, (float) yd, (float) zd);
        renderVelocityQuad(h, camera, tickPercent, deltaMotion);
    }

    public void renderVelocityQuad(QuadParticleRenderState h, Camera camera, float tickPercent, Vector3f deltaMotion) {
        Vec3 camPos = VersionUtil.camPos(camera);
        float offsetX = (float) (Mth.lerp(tickPercent, this.xo, this.x) - camPos.x());
        float offsetY = (float) (Mth.lerp(tickPercent, this.yo, this.y) - camPos.y());
        float offsetZ = (float) (Mth.lerp(tickPercent, this.zo, this.z) - camPos.z());
        final float angle = velocityAngle(deltaMotion);
        Vector3f axis = velocityAxis(deltaMotion);
        Quaternionf quaternion = new Quaternionf(new AxisAngle4f(-angle, axis));
        Vector3f transformedOffset = new Vector3f(offsetX, offsetY, offsetZ);
        transformedOffset.rotateAxis(angle, axis.x, axis.y, axis.z);
        quaternion.mul(Axis.YP.rotation(Math.atan2(transformedOffset.x, transformedOffset.z) + Mth.PI));
        float stretchFactor = Mth.clamp(deltaMotion.lengthSquared(), 0.25F, 1.0F);
        if (doCollisionAnim) {
            float collisionProg = Mth.lerp(tickPercent, oCollisionAnimProgress, collisionAnimProgress);
            if (collisionProg < stretchFactor) stretchFactor = collisionProg;
        }
        renderSquishyRotatedQuad(h, quaternion, offsetX, offsetY, offsetZ, tickPercent, stretchFactor);
    }

    private static float velocityAngle(Vector3f velocity) {
        Vector3f direction = new Vector3f(velocity);
        float length = direction.lengthSquared();
        if (Float.isFinite(length) && length > 1.0e-20F) direction.normalize();
        else direction.set(0, -1, 0);
        return Math.acos(java.lang.Math.max(-1, java.lang.Math.min(1, direction.y)));
    }

    private static Vector3f velocityAxis(Vector3f velocity) {
        Vector3f axis = new Vector3f(-velocity.z(), 0, velocity.x());
        float length = axis.lengthSquared();
        return Float.isFinite(length) && length > 1.0e-20F ? axis.normalize() : axis.set(1, 0, 0);
    }

    public void renderCameraCopyQuad(QuadParticleRenderState h, Camera camera, float tickPercent) {
        Vec3 camPos = VersionUtil.camPos(camera);
        float offsetX = (float) (Mth.lerp(tickPercent, this.xo, this.x) - camPos.x());
        float offsetY = (float) (Mth.lerp(tickPercent, this.yo, this.y) - camPos.y());
        float offsetZ = (float) (Mth.lerp(tickPercent, this.zo, this.z) - camPos.z());

        Quaternionf quaternion = new Quaternionf(camera.rotation());
        if (roll != 0) quaternion.rotateZ(Mth.lerp(tickPercent, oRoll, roll));
        this.renderRotatedQuad(h, quaternion, offsetX, offsetY, offsetZ, tickPercent);
    }

    public void renderHorizontalQuad(QuadParticleRenderState h, Camera camera, float tickPercent) {
        Vec3 camPos = VersionUtil.camPos(camera);
        float offsetX = (float) (Mth.lerp(tickPercent, this.xo, this.x) - camPos.x());
        float offsetY = (float) (Mth.lerp(tickPercent, this.yo, this.y) - camPos.y());
        float offsetZ = (float) (Mth.lerp(tickPercent, this.zo, this.z) - camPos.z());

        Quaternionf quaternion = new Quaternionf(new AxisAngle4d(Mth.HALF_PI, -1, 0, 0));
        if (roll != 0) quaternion.rotateZ(Mth.lerp(tickPercent, oRoll, roll));
        turnBackfaceFlipways(quaternion, new Vector3f(offsetX, offsetY, offsetZ));
        this.renderRotatedQuad(h, quaternion, offsetX, offsetY, offsetZ, tickPercent);
    }

    private void renderSquishyRotatedQuad(QuadParticleRenderState h, Quaternionf quaternion, float x, float y, float z, float tickPercent, float squish) {
        this.extractRotatedQuad(h, quaternion, x, y, z, tickPercent);
    }

    public void renderRotatedQuad(QuadParticleRenderState h, Quaternionf quaternion, float offsetX, float offsetY, float offsetZ, float tickPercent) {
        this.extractRotatedQuad(h, quaternion, offsetX, offsetY, offsetZ, tickPercent);
    }

    public static class Provider implements ParticleProvider<SimpleParticleType> {
        ParticleData data;

        public Provider(ParticleData data) {
            this.data = data;
        }

        public Particle createParticle(SimpleParticleType parameters, ClientLevel level, double x, double y, double z, double velocityX, double velocityY, double velocityZ, RandomSource random) {
            data = ParticleLoader.particles.get(data.id);
            return new CustomParticle(level, x, y, z, data);
        }
    }
}
