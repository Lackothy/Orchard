package mcopt.metal.mixin.own;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import mcopt.metal.own.OwnLife;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$CompileTask")
abstract class OwnIntLifeTaskMixin {
	@Shadow
	@Final
	SectionRenderDispatcher.RenderSection this$1;

	@WrapMethod(method = "doTask")
	private SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult mcopt$lifeTask(SectionBufferBuilderPack buffers,
		Operation<SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult> original) {
		if (!OwnLife.ON) return original.call(buffers);
		Object[] prev = OwnLife.enter(this, this.this$1.getSectionNode());
		try {
			return original.call(buffers);
		} finally {
			OwnLife.exit(prev);
		}
	}
}
