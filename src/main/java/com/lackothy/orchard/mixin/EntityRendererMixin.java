package com.lackothy.orchard.mixin;

import com.lackothy.orchard.video.VideoOptions;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Name tags off: the entity is drawn with no name over it, and players with no score under it. */
@Mixin(EntityRenderer.class)
abstract class EntityRendererMixin {

    @Inject(method = "extractNameTags(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;FDD)V",
        at = @At("RETURN"))
    private void orchard$names(Entity entity, EntityRenderState state, float delta, double x, double y, CallbackInfo ci) {
        boolean player = entity instanceof Player;
        if (player ? !VideoOptions.PLAYER_NAMES.on() : !VideoOptions.OTHER_NAMES.on()) {
            state.nameTag = null;
            state.scoreText = null;
        }
    }
}
