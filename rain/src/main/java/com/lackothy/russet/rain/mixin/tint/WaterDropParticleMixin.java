package com.lackothy.russet.rain.mixin.tint;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.WaterDropParticle;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.lackothy.russet.rain.ParticleRain;
import com.lackothy.russet.rain.VersionUtil;
import com.lackothy.russet.rain.TextureUtil;
import com.lackothy.russet.rain.config.ConfigManager;
import net.minecraft.client.particle.SingleQuadParticle;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

@Mixin(WaterDropParticle.class)
public abstract class WaterDropParticleMixin extends SingleQuadParticle {

    protected WaterDropParticleMixin(ClientLevel level, double x, double y, double z, TextureAtlasSprite sprite) {
        super(level, x, y, z, sprite);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    public void pickSprite(ClientLevel level, double x, double y, double z, TextureAtlasSprite sprite, CallbackInfo ci) {

        if (ParticleRain.on() && ConfigManager.getConfig().compat.waterTint) { // Russet: on()
            try {
                this.setSprite(VersionUtil.getSprite(VersionUtil.getId("splash_" + random.nextInt(4))));
            } catch (IllegalStateException e) {
                ParticleRain.LOGGER.error(e.getMessage());
                this.remove();
            }
            TextureUtil.copyRainTint(this, this.level, BlockPos.containing(x, y, z));
        }
    }
}
