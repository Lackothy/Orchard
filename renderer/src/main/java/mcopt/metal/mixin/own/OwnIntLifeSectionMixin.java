package mcopt.metal.mixin.own;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import mcopt.metal.own.OwnLife;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SectionRenderDispatcher.RenderSection.class)
abstract class OwnIntLifeSectionMixin {
	@Shadow
	@Final
	SectionRenderDispatcher this$0;

	@WrapMethod(method = "reset")
	private void mcopt$lifeReset(Operation<Void> original) {
		if (!OwnLife.FIX) {
			original.call();
			return;
		}
		try {
			long t0 = OwnLife.LOCK_STATS ? System.nanoTime() : 0;
			this.this$0.lock();
			long t1 = OwnLife.LOCK_STATS ? System.nanoTime() : 0;
			try {
				original.call();
			} finally {
				this.this$0.unlock();
				if (OwnLife.LOCK_STATS) OwnLife.lockStats(t1 - t0, System.nanoTime() - t1);
			}
		} catch (OwnLife.TestException e) {
			OwnLife.verifyUnlocked(((OwnIntDispatcherLockAccess) this.this$0).mcopt$copyLock());
		}
	}

	@Inject(method = "reset", at = @At("HEAD"))
	private void mcopt$lifeThrowTest(CallbackInfo ci) {
		if (OwnLife.THROW_TEST > 0) OwnLife.maybeThrow();
	}
}
