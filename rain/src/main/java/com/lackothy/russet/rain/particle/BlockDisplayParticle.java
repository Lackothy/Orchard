package com.lackothy.russet.rain.particle;

import com.mojang.math.Transformation;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import com.lackothy.russet.rain.VersionUtil;
import com.lackothy.russet.rain.config.ParticleData;
import com.lackothy.russet.rain.mixin.access.BlockDisplayAccessor;
import com.lackothy.russet.rain.mixin.access.DisplayAccessor;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;

import java.util.ArrayList;
import java.util.NoSuchElementException;

public class BlockDisplayParticle extends CustomParticle {

    private static final ArrayList<Display.BlockDisplay> ENTITIES_IN_LEVEL = new ArrayList<>();
    private final Display.BlockDisplay display;

    public BlockDisplayParticle(ClientLevel level, double x, double y, double z, ParticleData data) {
        super(level, x, y, z, data);

        display = new Display.BlockDisplay(net.minecraft.world.entity.EntityTypes.BLOCK_DISPLAY, level);
        display.setPos(x, y, z);

        final Identifier id = VersionUtil.parseId(data.blockId);
        if (id != null) {
            try {
                ((BlockDisplayAccessor) display).callSetBlockState(
                        BuiltInRegistries.BLOCK.get(id).get().value().defaultBlockState()
                );
            } catch (NoSuchElementException e) {
                this.remove();
            }
        }
        this.rotationVariation = data.rotationAmount;

        display.setId(-this.random.nextInt(Integer.MAX_VALUE));
        level.addEntity(display);
        ENTITIES_IN_LEVEL.add(display);
        updateDisplay();
    }

    @Override
    public void extract(QuadParticleRenderState h, Camera camera, float tickPercent) {
    }

    @Override
    public void tick() {
        super.tick();
        updateDisplay();
    }

    public void updateDisplay() {
        display.setPos(x, y, z);
        float yaw = (float)Math.atan2(-this.xd, this.zd) * (180.0F / (float)Math.PI);
        display.setYRot(yaw);
        ((DisplayAccessor) display).callSetTransformation(new Transformation(
                new Vector3f(0, -Mth.cos(roll + 0.8F) * 0.7F, -Mth.cos(roll + 5.5F) * 0.7F),
                new Quaternionf(),
                new Vector3f(quadSize),
                new Quaternionf(new AxisAngle4f(roll, 1, 0, 0))
        ));
        ((DisplayAccessor) display).callSetTransformationInterpolationDuration(1);
        ((DisplayAccessor) display).callSetTransformationInterpolationDelay(0);
    }

    public static void clearAll() {
        BlockDisplayParticle.ENTITIES_IN_LEVEL.forEach(entity -> entity.remove(Entity.RemovalReason.DISCARDED));
        BlockDisplayParticle.ENTITIES_IN_LEVEL.clear();
    }

   @Override
    public void remove() {
        display.remove(Entity.RemovalReason.DISCARDED);
        ENTITIES_IN_LEVEL.remove(display);
        super.remove();
    }
}
