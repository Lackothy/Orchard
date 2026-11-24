// From Enhanced Attack Indicator 1.2.0 by Minenash (MIT, see licenses/enhanced-attack-indicator.txt), modified for Russet:
// on Minecraft 26.3's Hud, wrapping its calls rather than redirecting them, and standing down when the mod is installed.
package com.lackothy.russet.mixin.hud;

import com.lackothy.russet.hud.AttackIndicator;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Hud.class)
abstract class AttackIndicatorMixin {

    @Unique
    private static final Identifier CROSSHAIR_ATTACK_INDICATOR_FULL = Identifier.withDefaultNamespace("hud/crosshair_attack_indicator_full");

    @Unique
    private boolean russet$renderFullness;

    @WrapOperation(method = "extractCrosshair", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/player/LocalPlayer;getAttackStrengthScale(F)F"))
    private float russet$setBarProgress(LocalPlayer player, float baseTime, Operation<Float> original) {
        float weapon = original.call(player, baseTime);
        if (AttackIndicator.STOOD_DOWN) {
            return weapon;
        }
        float progress = AttackIndicator.progress(weapon);
        russet$renderFullness = progress == AttackIndicator.FULL;
        return russet$renderFullness ? 1.0F : progress;
    }

    @WrapOperation(method = "extractCrosshair", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;isAlive()Z"))
    private boolean russet$dontShowPlus(Entity entity, Operation<Boolean> original) {
        return AttackIndicator.STOOD_DOWN && original.call(entity);
    }

    @Inject(method = "extractCrosshair", at = @At("TAIL"))
    private void russet$showPlus(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo info) {
        if (russet$renderFullness) {
            int y = graphics.guiHeight() / 2 - 7 + 16;
            int x = graphics.guiWidth() / 2 - 8;
            graphics.blitSprite(RenderPipelines.CROSSHAIR, CROSSHAIR_ATTACK_INDICATOR_FULL, x, y, 16, 16);
            russet$renderFullness = false;
        }
    }

    @WrapOperation(method = "extractItemHotbar", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/player/LocalPlayer;getAttackStrengthScale(F)F"))
    private float russet$setHotBarProgress(LocalPlayer player, float baseTime, Operation<Float> original) {
        float weapon = original.call(player, baseTime);
        if (AttackIndicator.STOOD_DOWN) {
            return weapon;
        }
        float progress = AttackIndicator.progress(weapon);
        return progress == AttackIndicator.FULL ? 0.99F : progress;
    }
}
