package mcopt.metal.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.Lightmap;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Lightmap.class)
public class LightmapDoubleMixin {
	@Unique
	private static final boolean MCOPT_ON = Boolean.getBoolean("mcopt.metal.lightmapDouble");

	@Shadow
	@Final
	private GpuTextureView textureView;

	@Shadow
	@Final
	private static Vector4fc CLEAR_COLOR;

	@Unique
	private GpuTexture mcopt$texture2;
	@Unique
	private GpuTextureView mcopt$view2;
	@Unique
	private boolean mcopt$secondCurrent, mcopt$drew;

	@Redirect(method = "render", at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/Lightmap;textureView:Lcom/mojang/renderpearl/api/textures/GpuTextureView;"))
	private GpuTextureView mcopt$target(Lightmap self) {
		if (!MCOPT_ON) return this.textureView;
		if (this.mcopt$view2 == null) {
			GpuDevice device = RenderSystem.getDevice();
			this.mcopt$texture2 = device.createTexture("Lightmap 2", 13, GpuFormat.RGBA8_UNORM, 16, 16, 1, 1);
			this.mcopt$view2 = device.createTextureView(this.mcopt$texture2);
			device.createCommandEncoder().clearColorTexture(this.mcopt$texture2, CLEAR_COLOR);
		}
		this.mcopt$drew = true;
		return this.mcopt$secondCurrent ? this.textureView : this.mcopt$view2;
	}

	@Inject(method = "render", at = @At("RETURN"))
	private void mcopt$swap(CallbackInfo ci) {
		if (this.mcopt$drew) this.mcopt$secondCurrent = !this.mcopt$secondCurrent;
		this.mcopt$drew = false;
	}

	@Inject(method = "getTextureView", at = @At("HEAD"), cancellable = true)
	private void mcopt$current(CallbackInfoReturnable<GpuTextureView> cir) {
		if (MCOPT_ON && this.mcopt$secondCurrent) cir.setReturnValue(this.mcopt$view2);
	}

	@Inject(method = "close", at = @At("HEAD"))
	private void mcopt$close(CallbackInfo ci) {
		if (this.mcopt$view2 != null) {
			this.mcopt$view2.close();
			this.mcopt$texture2.close();
		}
	}
}
