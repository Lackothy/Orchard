package com.lackothy.russet.fabulous;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.RandomSource;

final class EffectParticle extends SingleQuadParticle {

    private final TextureAtlasSprite small;
    private final float shownAlpha;

    EffectParticle(ClientLevel level, double x, double y, double z, RandomSource random, TextureAtlasSprite big,
                   TextureAtlasSprite small, float alpha) {
        super(level, x, y, z, 0.5 - random.nextDouble(), 1, 0.5 - random.nextDouble(), big);
        this.small = small;
        this.shownAlpha = alpha;
        this.friction = 0.96F;
        this.gravity = -0.1F;
        this.speedUpWhenYMotionIsBlocked = true;
        this.yd *= 0.2F;
        this.quadSize *= 0.75F;
        this.lifetime = (int) (8.0 / (this.random.nextFloat() * 0.8 + 0.2));
        this.hasPhysics = false;
        setAlpha(nearScopingPlayer() ? 0 : alpha);
    }

    @Override
    public void tick() {
        super.tick();
        if (age * 2 >= lifetime) {
            setSprite(small);
        }
        this.alpha = nearScopingPlayer() ? 0 : shownAlpha;
    }

    @Override
    protected Layer getLayer() {
        return Layer.TRANSLUCENT;
    }

    private boolean nearScopingPlayer() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        return player != null && player.isScoping() && minecraft.options.getCameraType().isFirstPerson()
            && player.getEyePosition().distanceToSqr(x, y, z) <= 9;
    }
}
