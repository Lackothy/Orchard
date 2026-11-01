package mcopt.metal;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import mcopt.metal.mixin.AtlasDoubleAtlasAccess;
import mcopt.metal.mixin.AtlasDoubleSpriteAccess;
import mcopt.metal.mixin.AtlasDoubleStateAccess;
import mcopt.metal.mixin.AtlasDoubleTextureAccess;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.lwjgl.system.MemoryUtil;

public final class AtlasDouble {
	public static final boolean ON = Boolean.getBoolean("mcopt.metal.atlasDouble");
	private static final int VERIFY = Integer.getInteger("mcopt.metal.atlasDoubleVerify", 0);

	private static final class State {
		final GpuTexture[] tex = new GpuTexture[2];
		final GpuTextureView[] view = new GpuTextureView[2];
		final GpuTextureView[][] mips = new GpuTextureView[2][];
		boolean[][] stale;
		List<TextureAtlasSprite> sprites;
		int cur, maxMip, width, height, swaps;
		boolean[] drawn, forced;
	}

	private static final Map<TextureAtlas, State> STATES = new WeakHashMap<>();
	private static long verifyTexels, verifyBad;

	private AtlasDouble() {
	}

	private static final boolean ALL = Boolean.getBoolean("mcopt.metal.atlasDoubleAll");

	private static boolean blocks(TextureAtlas atlas) {
		return atlas.location().getPath().endsWith("atlas/blocks.png");
	}

	public static void uploaded(TextureAtlas atlas) {
		AtlasDoubleAtlasAccess a = (AtlasDoubleAtlasAccess) atlas;
		if (ALL ? a.mcopt$adSprites().stream().noneMatch(TextureAtlasSprite::isAnimated) : !blocks(atlas)) return;
		State s = new State();
		GpuDevice device = RenderSystem.getDevice();
		s.tex[0] = atlas.getTexture();
		s.view[0] = ((AtlasDoubleTextureAccess) atlas).mcopt$adTextureView();
		s.mips[0] = a.mcopt$adMipViews();
		s.maxMip = a.mcopt$adMaxMip();
		s.width = a.mcopt$adWidth();
		s.height = a.mcopt$adHeight();
		GpuTexture t0 = s.tex[0];
		long ctx = ((FrontendCommandEncoder) device.createCommandEncoder()).backend() instanceof MetalEncoder me ? me.ctx : 0;
		long before = ctx != 0 ? Native.deviceAllocated(ctx) : 0;
		s.tex[1] = device.createTexture(() -> "mcopt atlas second copy " + atlas.location(), t0.usage() | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC,
			t0.getFormat(), s.width, s.height, 1, s.maxMip + 1);
		s.view[1] = device.createTextureView(s.tex[1]);
		s.mips[1] = new GpuTextureView[s.maxMip + 1];
		for (int m = 0; m <= s.maxMip; m++) s.mips[1][m] = device.createTextureView(s.tex[1], m, 1);
		CommandEncoder encoder = device.createCommandEncoder();
		for (int m = 0; m <= s.maxMip; m++) encoder.copyTextureToTexture(s.tex[0], s.tex[1], m, 0, 0, 0, 0, Math.max(1, s.width >> m), Math.max(1, s.height >> m));
		s.sprites = new ArrayList<>();
		for (TextureAtlasSprite sp : a.mcopt$adSprites()) if (sp.isAnimated()) s.sprites.add(sp);
		s.stale = new boolean[2][s.sprites.size()];
		STATES.put(atlas, s);
		long bytes = 0;
		for (int m = 0; m <= s.maxMip; m++) bytes += (long) Math.max(1, s.width >> m) * Math.max(1, s.height >> m) * t0.getFormat().blockSize();
		long after = ctx != 0 ? Native.deviceAllocated(ctx) : 0;
		System.out.println("mcopt-metal: atlas double-buffered: " + atlas.location() + " " + s.width + "x" + s.height + ", " + (s.maxMip + 1) + " mips, second copy "
			+ bytes / 1024 + " KB (Metal allocated " + before / 1024 + " -> " + after / 1024 + " KB, +" + (after - before) / 1024 + " KB), " + s.sprites.size()
			+ " animated sprites");
	}

	public static void release(TextureAtlas atlas) {
		State s = STATES.remove(atlas);
		if (s == null) return;
		((AtlasDoubleTextureAccess) atlas).mcopt$adSetTexture(s.tex[0]);
		((AtlasDoubleTextureAccess) atlas).mcopt$adSetTextureView(s.view[0]);
		((AtlasDoubleAtlasAccess) atlas).mcopt$adSetMipViews(s.mips[0]);
		for (GpuTextureView v : s.mips[1]) v.close();
		s.view[1].close();
		s.tex[1].close();
	}

	public static void before(TextureAtlas atlas) {
		if (takenOver.test(atlas)) return;
		State s = STATES.get(atlas);
		if (s == null) return;
		List<SpriteContents.AnimationState> states = ((AtlasDoubleAtlasAccess) atlas).mcopt$adStates();
		s.drawn = null;
		if (states.size() != s.sprites.size()) return;
		boolean any = false;
		boolean[] drawn = new boolean[states.size()];
		for (int i = 0; i < drawn.length; i++) any |= drawn[i] = states.get(i).needsToDraw();
		if (!any) return;
		int spare = 1 - s.cur;
		boolean[] stale = s.stale[spare];
		s.forced = new boolean[stale.length];
		for (int i = 0; i < stale.length; i++) {
			if (stale[i] && !drawn[i]) {
				((AtlasDoubleStateAccess) (Object) states.get(i)).mcopt$adSetDirty(true);
				s.forced[i] = true;
			}
			stale[i] = false;
		}
		((AtlasDoubleAtlasAccess) atlas).mcopt$adSetMipViews(s.mips[spare]);
		s.drawn = drawn;
	}

	public static void after(TextureAtlas atlas) {
		if (takenOver.test(atlas)) return;
		State s = STATES.get(atlas);
		if (s == null || s.drawn == null) return;
		int spare = 1 - s.cur;
		List<SpriteContents.AnimationState> states = ((AtlasDoubleAtlasAccess) atlas).mcopt$adStates();
		for (int i = 0; i < s.forced.length; i++) if (s.forced[i]) ((AtlasDoubleStateAccess) (Object) states.get(i)).mcopt$adSetDirty(false);
		((AtlasDoubleTextureAccess) atlas).mcopt$adSetTexture(s.tex[spare]);
		((AtlasDoubleTextureAccess) atlas).mcopt$adSetTextureView(s.view[spare]);
		boolean[] staleOld = s.stale[s.cur];
		for (int i = 0; i < staleOld.length; i++) staleOld[i] |= s.drawn[i];
		int old = s.cur;
		s.cur = spare;
		if (VERIFY > 0 && ++s.swaps % VERIFY == 0) verify(s, old, s.drawn);
		s.drawn = null;
	}

	public static volatile java.util.function.Predicate<TextureAtlas> takenOver = atlas -> false;

	public static GpuTexture spare(TextureAtlas atlas) {
		State s = STATES.get(atlas);
		return s == null ? null : s.tex[1 - s.cur];
	}

	public static boolean[] staleOfSpare(TextureAtlas atlas) {
		State s = STATES.get(atlas);
		return s == null ? null : s.stale[1 - s.cur].clone();
	}

	public static void committed(TextureAtlas atlas, boolean[] drawn) {
		State s = STATES.get(atlas);
		if (s == null) return;
		int spare = 1 - s.cur;
		java.util.Arrays.fill(s.stale[spare], false);
		((AtlasDoubleTextureAccess) atlas).mcopt$adSetTexture(s.tex[spare]);
		((AtlasDoubleTextureAccess) atlas).mcopt$adSetTextureView(s.view[spare]);
		((AtlasDoubleAtlasAccess) atlas).mcopt$adSetMipViews(s.mips[spare]);
		boolean[] staleOld = s.stale[s.cur];
		for (int i = 0; i < staleOld.length && i < drawn.length; i++) staleOld[i] |= drawn[i];
		int old = s.cur;
		s.cur = spare;
		if (VERIFY > 0 && ++s.swaps % VERIFY == 0) verify(s, old, drawn);
	}

	private static void verify(State s, int old, boolean[] drawn) {
		GpuDevice device = RenderSystem.getDevice();
		CommandEncoder encoder = device.createCommandEncoder();
		int bpp = s.tex[0].getFormat().blockSize();
		for (int m = 0; m <= s.maxMip; m++) {
			int mip = m, w = Math.max(1, s.width >> m), h = Math.max(1, s.height >> m);
			ByteBuffer[] got = new ByteBuffer[2];
			Runnable compare = () -> {
				if (got[0] == null || got[1] == null) return;
				boolean[] in = new boolean[w * h];
				for (int i = 0; i < drawn.length; i++) {
					if (!drawn[i]) continue;
					TextureAtlasSprite sp = s.sprites.get(i);
					int p = ((AtlasDoubleSpriteAccess) sp).mcopt$adPadding(), rw = sp.contents().width() + 2 * p >> mip, rh = sp.contents().height() + 2 * p >> mip;
					for (int y = 0; y < rh; y++) for (int x = 0; x < rw; x++) in[((sp.getY() >> mip) + y) * w + (sp.getX() >> mip) + x] = true;
				}
				long bad = 0;
				for (int t = 0; t < w * h; t++) {
					if (in[t]) continue;
					verifyTexels++;
					for (int c = 0; c < bpp; c++) {
						if (got[0].get(t * bpp + c) != got[1].get(t * bpp + c)) {
							bad++;
							break;
						}
					}
				}
				verifyBad += bad;
				System.out.println("mcopt-atlasdouble-verify mip " + mip + ": texels outside the drawn rectangles " + verifyTexels + ", different " + verifyBad);
				MemoryUtil.memFree(got[0]);
				MemoryUtil.memFree(got[1]);
			};
			for (int k = 0; k < 2; k++) {
				int which = k == 0 ? s.cur : old, slot = k;
				GpuBuffer b = device.createBuffer(() -> "mcopt atlas double verify", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) w * h * bpp);
				encoder.copyTextureToBuffer(s.tex[which], b, 0, () -> {
					try (GpuBufferSlice.MappedView v = b.map(true, false)) {
						got[slot] = MemoryUtil.memAlloc(v.data().remaining()).put(v.data()).flip();
					}
					b.close();
					compare.run();
				}, mip);
			}
		}
	}
}
