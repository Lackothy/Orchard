// Modified for Russet: the interval comes from FrameWait, which knows when the display holds frames to its refreshes.
package mcopt.metal.mixin;

import mcopt.metal.FrameWait;
import net.minecraft.client.FramerateLimiter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FramerateLimiter.class)
abstract class FramerateLimiterMixin {
	@Shadow
	private static long lastFrameTime;

	@Inject(method = "limitDisplayFPS", at = @At("HEAD"), cancellable = true)
	private static void mcopt$preciseWait(int framerateLimit, CallbackInfo ci) {
		if (!FrameWait.ENABLED) return;
		long remaining = lastFrameTime + FrameWait.interval(framerateLimit) - System.nanoTime();
		if (remaining > 0) FrameWait.sleep(remaining);
		lastFrameTime = System.nanoTime();
		ci.cancel();
	}
}
