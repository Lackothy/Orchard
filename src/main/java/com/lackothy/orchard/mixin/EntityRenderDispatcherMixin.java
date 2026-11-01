package com.lackothy.orchard.mixin;

import com.lackothy.orchard.video.DroppedItemLimit;
import com.lackothy.orchard.video.VideoOptions;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.painting.Painting;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The Entities page's switches: what is off is never drawn. It is still there, and can be hit.
 * Of the items on the ground that would be drawn, only the nearest are (DroppedItemLimit).
 */
@Mixin(EntityRenderDispatcher.class)
abstract class EntityRenderDispatcherMixin {

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void orchard$shown(Entity entity, Frustum frustum, double x, double y, double z, float delta,
                               CallbackInfoReturnable<Boolean> cir) {
        if (entity instanceof ItemFrame && !VideoOptions.ITEM_FRAMES.on()
            || entity instanceof ArmorStand && !VideoOptions.ARMOR_STANDS.on()
            || entity instanceof Painting && !VideoOptions.PAINTINGS.on()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "shouldRender", at = @At("RETURN"), cancellable = true)
    private void orchard$nearestItems(Entity entity, Frustum frustum, double x, double y, double z, float delta,
                                      CallbackInfoReturnable<Boolean> cir) {
        if (entity instanceof ItemEntity && cir.getReturnValueZ() && !DroppedItemLimit.shown(entity.distanceToSqr(x, y, z))) {
            cir.setReturnValue(false);
        }
    }
}
