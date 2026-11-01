package mcopt.metal.mixin.own;

import java.util.List;
import java.util.Map;
import mcopt.metal.own.OwnTerrain;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelRenderer.class)
abstract class OwnIntListsMixin {
	@Unique
	private static final String MCOPT$MODE = System.getProperty("mcopt.own.int.lists", "false");
	@Unique
	private static final boolean MCOPT$ON = "true".equals(MCOPT$MODE), MCOPT$VERIFY = "verify".equals(MCOPT$MODE);
	@Unique
	private boolean mcopt$wouldSkip;
	@Unique
	private long mcopt$checked, mcopt$bad, mcopt$skipped, mcopt$lastLog;

	@Inject(method = "extractSectionDrawGroups", at = @At("HEAD"), cancellable = true)
	private void mcopt$skipVanillaLists(boolean respectTranslucentOrder, List<?> sectionInfos, Map<?, ?> drawGroups, CallbackInfoReturnable<Integer> cir) {
		this.mcopt$wouldSkip = false;
		if (!MCOPT$ON && !MCOPT$VERIFY || OwnTerrain.get() == null) return;
		SectionRenderDispatcher d = ((LevelRenderer) (Object) this).sectionRenderDispatcher();
		if (d == null) return;
		d.lock();
		try {
			for (Object buffers : ((OwnIntDispatcherAccess) d).mcopt$uberBuffers().values()) {
				if (!((OwnIntUberAccess) ((OwnIntUberBuffersAccess) buffers).mcopt$vertexBuffer()).mcopt$allocations().isEmpty()) return;
			}
		} finally {
			d.unlock();
		}
		this.mcopt$wouldSkip = true;
		if (MCOPT$ON) cir.setReturnValue(0);
	}

	@Inject(method = "extractSectionDrawGroups", at = @At("RETURN"))
	private void mcopt$verifyVanillaLists(boolean respectTranslucentOrder, List<?> sectionInfos, Map<?, ?> drawGroups, CallbackInfoReturnable<Integer> cir) {
		if (!MCOPT$VERIFY) return;
		this.mcopt$checked++;
		if (this.mcopt$wouldSkip) {
			this.mcopt$skipped++;
			boolean empty = cir.getReturnValueI() == 0 && sectionInfos.isEmpty();
			for (Object l : drawGroups.values()) empty &= ((List<?>) l).isEmpty();
			if (!empty) this.mcopt$bad++;
		}
		long now = System.nanoTime();
		if (now - this.mcopt$lastLog > 5_000_000_000L) {
			System.out.println("mcopt-own lists verify: " + this.mcopt$checked + " frames, skip would apply in " + this.mcopt$skipped + ", mismatches " + this.mcopt$bad);
			this.mcopt$lastLog = now;
		}
	}
}
