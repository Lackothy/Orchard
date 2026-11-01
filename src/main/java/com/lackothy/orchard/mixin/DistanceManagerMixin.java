package com.lackothy.orchard.mixin;

import com.lackothy.orchard.video.VideoOptions;
import net.minecraft.server.level.DistanceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(DistanceManager.class)
abstract class DistanceManagerMixin {

    @ModifyConstant(method = "<init>", constant = @Constant(intValue = 32))
    private int orchard$playerTicketReach(int reach) {
        return VideoOptions.RENDER_DISTANCE_LIMIT;
    }
}
