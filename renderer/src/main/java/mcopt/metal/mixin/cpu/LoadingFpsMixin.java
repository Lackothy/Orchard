package mcopt.metal.mixin.cpu;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import mcopt.metal.cpu.Cpu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FramerateLimitTracker.class)
abstract class LoadingFpsMixin {
	@Shadow @Final private Minecraft minecraft;

	@Inject(method = "getFramerateLimit", at = @At("RETURN"), cancellable = true)
	private void mcopt$capLoading(CallbackInfoReturnable<Integer> cir) {
		if (this.minecraft.gui.screen() instanceof LevelLoadingScreen && cir.getReturnValueI() > Cpu.LOADING_FPS) cir.setReturnValue(Cpu.LOADING_FPS);
	}
}
