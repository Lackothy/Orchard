// Orchard's own file (see NOTICE): not part of mcopt.
package mcopt.metal;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;

/**
 * MetalFX's spatial scaler for Orchard's render scale (com.lackothy.orchard.video.RenderScale, upscale.m): one at a time,
 * made again when the sizes change, the old one freed once the GPU is done with it.
 */
public final class Upscaler {
	private static long scaler;
	private static int inW, inH, outW, outH;

	private Upscaler() {
	}

	/** Whether MetalFX can take from's size up to to's, making the scaler for them if they are new. */
	public static boolean ready(GpuTexture from, GpuTexture to) {
		MetalEncoder encoder = encoder();
		if (encoder == null) return false;
		int w = from.getWidth(0), h = from.getHeight(0), ow = to.getWidth(0), oh = to.getHeight(0);
		if (w != inW || h != inH || ow != outW || oh != outH) {
			long old = scaler;
			if (old != 0) encoder.afterGpuFinishes(() -> Native.upscalerFree(old));
			scaler = Native.upscalerNew(((MetalTexture) from).handle, ((MetalTexture) to).handle);
			inW = w;
			inH = h;
			outW = ow;
			outH = oh;
		}
		return scaler != 0;
	}

	/** Encodes the upscale of from into to at this point of the frame, after {@link #ready} said yes for them. */
	public static void upscale(GpuTexture from, GpuTexture to) {
		MetalEncoder encoder = encoder();
		if (encoder == null || scaler == 0) return;
		Native.upscale(encoder.enc, scaler, ((MetalTexture) from).handle, ((MetalTexture) to).handle);
	}

	private static MetalEncoder encoder() {
		return ((FrontendCommandEncoder) RenderSystem.getDevice().createCommandEncoder()).backend() instanceof MetalEncoder e ? e : null;
	}
}
