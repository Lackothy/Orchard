package com.lackothy.russet.rain.particle;

import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import com.mojang.math.Axis;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import com.lackothy.russet.rain.ParticleSpawner;
import com.lackothy.russet.rain.config.ParticleData;

import static com.lackothy.russet.rain.config.ConfigManager.getConfig;

public abstract class WeatherParticle extends SingleQuadParticle {

    protected BlockPos.MutableBlockPos pos;
    protected BlockPos.MutableBlockPos oPos;
    boolean doCollisionAnim = false;
    float oQuadSize;

    protected WeatherParticle(ClientLevel level, double x, double y, double z, TextureAtlasSprite sprite) {
        super(level, x, y, z, sprite);
        this.setSprite(sprite);

        this.hasPhysics = false;

        this.setSize(quadSize, quadSize);
        this.lifetime = getConfig().perf.particleDistance * 100;
        this.pos = new BlockPos.MutableBlockPos(x, y, z);
        this.oPos = new BlockPos.MutableBlockPos(x, y, z);

        ParticleSpawner.particleCount++;
    }

    @Override
    public void remove() {
        if (this.isAlive()) ParticleSpawner.particleCount--;
        super.remove();
    }

    @Override
        public SingleQuadParticle.Layer getLayer() {

        return ParticleData.RenderType.TRANSLUCENT.get();
    }

    @Override
    public void tick() {
        super.tick();
        oQuadSize = quadSize;
        pos.set(x, y, z);
        if (doCollisionAnim) {
            tickCollisionAnim();
        }
    }

    @Override
    public void move(double x, double y, double z) {
        setPos(this.x + this.xd, this.y + this.yd, this.z + this.zd);
    }

    @Override
    public float getQuadSize(float scaleFactor) {
        return Mth.lerp(scaleFactor, oQuadSize, quadSize);
    }

    public abstract void tickCollisionAnim();

    public Quaternionf turnBackfaceFlipways(Quaternionf quaternion, Vector3f cameraOffset) {
        Vector3f normal = new Vector3f(0, 0, 1);
        normal.rotate(quaternion).normalize();
        float dot = normal.dot(cameraOffset);
        if (dot > 0) {
            return quaternion.mul(Axis.YP.rotation(Mth.PI));
        }
        else return quaternion;
    }

    public void renderRotatedQuad(QuadParticleRenderState h, Quaternionf quaternion, float offsetX, float offsetY, float offsetZ, float tickPercent) {
        this.extractRotatedQuad(h, quaternion, offsetX, offsetY, offsetZ, tickPercent);
    }
}