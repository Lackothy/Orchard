package mcopt.metal.mixin.startup;

import mcopt.metal.Startup;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Blocks.class)
abstract class BlockCacheMixin {
	@Redirect(method = "lambda$static$429", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;initCache()V"))
	private static void mcopt$defer(BlockState state) {
		if (Startup.parallelBlockCache()) Startup.deferInitCache(state);
		else state.initCache();
	}
}
