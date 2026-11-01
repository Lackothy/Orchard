package mcopt.metal.mixin.own;

import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$CompileTask")
abstract class OwnIntCompileRegionMixin {
	private static final boolean MCOPT$ON = Boolean.getBoolean("mcopt.own.int.compileRegionDrop");
	private static final AtomicLong MCOPT$DROPPED = new AtomicLong();

	@Shadow
	@Final
	@Mutable
	private RenderSectionRegion region;

	@Inject(method = "doTask", at = @At("RETURN"))
	private void mcopt$dropRegion(CallbackInfoReturnable<?> cir) {
		if (!MCOPT$ON) return;
		this.region = null;
		long n = MCOPT$DROPPED.incrementAndGet();
		if (n == 1 || n % 5_000 == 0) System.out.println("mcopt-own: compile regions dropped after their compile: " + n);
	}
}
