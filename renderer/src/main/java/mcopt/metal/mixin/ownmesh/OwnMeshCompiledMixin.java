package mcopt.metal.mixin.ownmesh;

import mcopt.metal.own.OwnQuadHolder;
import mcopt.metal.own.OwnQuads;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.TranslucencyPointOfView;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CompiledSectionMesh.class)
abstract class OwnMeshCompiledMixin implements OwnQuadHolder {
	@Unique
	private @Nullable OwnQuads mcopt$quads;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void mcopt$ownQuads(TranslucencyPointOfView translucencyPointOfView, SectionCompiler.Results results, long compileTaskStartTimeNs, CallbackInfo ci) {
		this.mcopt$quads = ((OwnQuadHolder) (Object) results).mcopt$quads();
	}

	@Override
	public @Nullable OwnQuads mcopt$quads() {
		return this.mcopt$quads;
	}

	@Override
	public void mcopt$setQuads(@Nullable OwnQuads quads) {
		this.mcopt$quads = quads;
	}
}
