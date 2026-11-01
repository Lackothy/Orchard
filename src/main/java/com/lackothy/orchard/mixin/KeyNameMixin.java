package com.lackothy.orchard.mixin;

import com.lackothy.orchard.mac.MacPower;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/** On a Mac, the keys go by the names on its keyboard: Command and Option, not Win and Alt. */
@Mixin(InputConstants.Key.class)
abstract class KeyNameMixin {

    private static final Map<String, String> MAC_NAMES = Map.of(
        "key.keyboard.left.win", "orchard.key.left.command",
        "key.keyboard.right.win", "orchard.key.right.command",
        "key.keyboard.left.windows", "orchard.key.left.command",
        "key.keyboard.right.windows", "orchard.key.right.command",
        "key.keyboard.left.alt", "orchard.key.left.option",
        "key.keyboard.right.alt", "orchard.key.right.option");

    @Shadow
    @Final
    private String name;

    @Inject(method = "getDisplayName", at = @At("HEAD"), cancellable = true)
    private void orchard$macName(CallbackInfoReturnable<Component> cir) {
        String mac = MacPower.MAC ? MAC_NAMES.get(this.name) : null;
        if (mac != null) {
            cir.setReturnValue(Component.translatable(mac));
        }
    }
}
