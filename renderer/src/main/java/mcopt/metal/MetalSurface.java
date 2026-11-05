// Modified for Russet: vsync is always on (FIFO is the only present mode offered), the pacer is off unless asked for, and
// FrameWait is told when the display holds frames to its refreshes.
package mcopt.metal;

import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import java.util.Collection;
import java.util.List;
import org.lwjgl.sdl.SDLMetal;

final class MetalSurface implements GpuSurfaceBackend {
	private static final boolean PACE = Boolean.getBoolean("mcopt.metal.pace");
	private static final double PACE_MARGIN_S = Double.parseDouble(System.getProperty("mcopt.metal.paceMarginMs", "2")) / 1000;
	private static final int DRAWABLES = Integer.getInteger("mcopt.metal.drawables", 0);
	private static final boolean PACE_SYNC = Boolean.getBoolean("mcopt.metal.paceSync");
	static final boolean PACE_ADAPT = Boolean.getBoolean("mcopt.metal.paceAdapt");
	private static final boolean PACE_FOLLOW = Boolean.getBoolean("mcopt.metal.paceFollow");
	private boolean paced;
	private int loggedDisplay, paceFrames;
	private final long ctx;
	private final MetalEncoder encoder;
	private final long view;
	private final long layer;

	MetalSurface(long ctx, MetalEncoder encoder, long window) {
		this.ctx = ctx;
		this.encoder = encoder;
		this.view = SDLMetal.SDL_Metal_CreateView(window);
		this.layer = SDLMetal.SDL_Metal_GetLayer(this.view);
	}

	@Override
	public void configure(GpuSurface.Configuration config) {
		boolean vsync = config.presentMode() == GpuSurface.PresentMode.FIFO;
		this.paced = !vsync && PACE;
		boolean sync = vsync || PACE_SYNC && this.paced;
		FrameWait.displayHolds(!this.paced);
		if (PACE_ADAPT) Native.paceAdapt(this.paced ? 1 : 0);
		if (PACE_FOLLOW) Native.paceFollow(1);
		if (mcopt.metal.shade.Shade.ENABLED) mcopt.metal.shade.dims.ShadeXdr.configure(this.layer);
		Native.layerConfigure(this.ctx, this.layer, config.width(), config.height(), (sync ? 1 : 0) | DRAWABLES << 8);
	}

	@Override
	public boolean isSuboptimal() {
		return false;
	}

	@Override
	public void acquireNextTexture() {
	}

	@Override
	public void blitFromTexture(CommandEncoderBackend commandEncoder, GpuTextureView textureView) {
		if (mcopt.metal.rec.Rec.ON) mcopt.metal.rec.Rec.frame(this.encoder, textureView);
		if (this.paced && PACE_FOLLOW && (this.paceFrames++ & 255) == 0) {
			int display = Native.paceDisplay();
			if (display != 0 && display != this.loggedDisplay) {
				System.out.println("[mcopt] metal: frames paced to the refreshes of display " + Integer.toUnsignedString(display));
				this.loggedDisplay = display;
			}
		}
		long t0 = WaitStats.ON ? System.nanoTime() : 0;
		if (this.paced && !Native.pace(PACE_MARGIN_S)) {
			if (WaitStats.ON) WaitStats.wait(WaitStats.PACE, System.nanoTime() - t0, 0);
			return;
		}
		long t1 = WaitStats.ON ? System.nanoTime() : 0;
		if (WaitStats.ON && this.paced) WaitStats.wait(WaitStats.PACE, t1 - t0, 0);
		if (MetalEncoder.PRESENT_ACQUIRE) {
			this.encoder.presentAcquire(this.layer, textureView);
			return;
		}
		MetalEvents.Operation event = MetalEvents.begin("nextDrawable", -1, 0);
		long drawable;
		try {
			drawable = Native.layerNext(this.layer);
		} finally {
			MetalEvents.end(event);
		}
		if (WaitStats.ON) WaitStats.wait(WaitStats.DRAWABLE, System.nanoTime() - t1, 0);
		if (drawable == 0) return;
		this.encoder.presentTexture(drawable, textureView);
		this.encoder.afterGpuFinishes(() -> Native.release(drawable));
	}

	@Override
	public void present() {
	}

	@Override
	public Collection<GpuSurface.PresentMode> supportedPresentModes() {
		// Russet: vsync is always on with Metal, whatever the option says (Minecraft falls back to FIFO when IMMEDIATE and
		// MAILBOX aren't offered). Vsync off stuttered at a steady frame rate on the Air, in a window and fullscreen, through
		// mcopt's pacer and three tries of Russet's (see CLAUDE.md), and a 60 Hz screen shows 60 frames either way.
		return List.of(GpuSurface.PresentMode.FIFO);
	}

	@Override
	public void close() {
		FrameWait.displayHolds(false);
		SDLMetal.SDL_Metal_DestroyView(this.view);
	}
}
