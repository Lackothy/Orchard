package com.lackothy.orchard.mixin;

import com.lackothy.orchard.video.VideoOptions;
import net.minecraft.server.level.DistanceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** A local world's chunks past 32 get loaded for the player: its ticket tracker only counts this far from them. */
@Mixin(DistanceManager.class)
abstract class DistanceManagerMixin {

    @ModifyConstant(method = "<init>", constant = @Constant(intValue = 32))
    private int orchard$playerTicketReach(int reach) {
        return VideoOptions.RENDER_DISTANCE_LIMIT;
    }
}
