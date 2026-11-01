package com.lackothy.orchard.mixin;

import com.lackothy.orchard.video.BakedBlockEntities;
import com.lackothy.orchard.video.FarmChanges;
import com.lackothy.orchard.video.VideoOptions;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The particles of breaking and mining blocks; the sounds are left alone. And the block change
 * that the sections being marked are for (FarmChanges), around the two calls of Level.setBlock
 * that mark them: setBlocksDirty, then sendBlockUpdated (8 in its flags: the player's own).
 */
@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {

    @Inject(method = "addDestroyBlockEffect", at = @At("HEAD"), cancellable = true)
    private void orchard$breaking(BlockPos pos, BlockState state, CallbackInfo ci) {
        if (!VideoOptions.BLOCK_BREAKING.on()) {
            ci.cancel();
        }
    }

    @Inject(method = "addBreakingParticles", at = @At("HEAD"), cancellable = true)
    private void orchard$mining(BlockPos pos, Direction side, BlockState state, CallbackInfo ci) {
        if (!VideoOptions.BLOCK_CRACKING.on()) {
            ci.cancel();
        }
    }

    /** BakedBlockEntities: the end of a block's cracks is noticed each frame, however they ended. */
    @Inject(method = "destroyBlockProgress", at = @At("TAIL"))
    private void orchard$cracking(int breaker, BlockPos pos, int progress, CallbackInfo ci) {
        if (progress >= 0 && progress < 10) {
            BakedBlockEntities.cracking((ClientLevel) (Object) this, pos);
        }
    }

    @Inject(method = "setBlocksDirty", at = @At("HEAD"))
    private void orchard$changing(BlockPos pos, BlockState from, BlockState to, CallbackInfo ci) {
        FarmChanges.begin(pos, from, false);
    }

    @Inject(method = "setBlocksDirty", at = @At("RETURN"))
    private void orchard$changed(BlockPos pos, BlockState from, BlockState to, CallbackInfo ci) {
        FarmChanges.end();
    }

    @Inject(method = "sendBlockUpdated", at = @At("HEAD"))
    private void orchard$updating(BlockPos pos, BlockState from, BlockState to, int flags, CallbackInfo ci) {
        FarmChanges.begin(pos, from, (flags & 8) != 0);
    }

    @Inject(method = "sendBlockUpdated", at = @At("RETURN"))
    private void orchard$updated(BlockPos pos, BlockState from, BlockState to, int flags, CallbackInfo ci) {
        FarmChanges.end();
    }
}
