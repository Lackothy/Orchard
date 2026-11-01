package mcopt.metal.own;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import mcopt.metal.mixin.own.OwnAnimAtlasAccess;
import mcopt.metal.mixin.own.OwnAnimSpriteAccess;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

public final class AnimOnePass {
	public static final boolean BLIT = Boolean.getBoolean("mcopt.own.int.onePassBlit");
	public static final boolean ON = Boolean.getBoolean("mcopt.own.int.animOnePass") && (AnimCopy.ATLAS_WRITE || BLIT);
	private static final int VERIFY = Integer.getInteger("mcopt.own.int.onePassVerify", 0), VERIFY_COPY = Integer.getInteger("mcopt.own.int.onePassVerifyCopy", 0);
	private static final int SCRATCH_W = 512;
	private static final String TEST = System.getProperty("mcopt.own.int.onePassTest", "");
	private static String MISMATCH;
	private static final int WIDTH_LIMIT = TEST.equals("wide") ? 8 : SCRATCH_W;
	private static final Map<TextureAtlas, Object> REJECTED = new WeakHashMap<>();
	private static int flushes, kernelReady;
	private static final Map<SpriteContents.AnimationState, SpriteContents> OWNER = java.util.Collections.synchronizedMap(new WeakHashMap<>());

	public static void owned(SpriteContents.AnimationState state, SpriteContents contents) {
		OWNER.put(state, contents);
	}

	private static final class Entry {
		List<SpriteContents.AnimationState> states;
		List<TextureAtlasSprite> sprites;
		int maxMip;
		int[][] rx, ry, rw, rh;
		int uboBase;
		int uploads;
		int copy;
		String key;
	}

	private static final Map<TextureAtlas, Entry> ENTRIES = new WeakHashMap<>();
	private static final Map<TextureAtlas, boolean[]> PENDING = new LinkedHashMap<>();
	private static final Map<TextureAtlas, Boolean> PENDING_VERIFY = new LinkedHashMap<>();
	private static final Map<TextureAtlas, boolean[]> PENDING_FORCED = new LinkedHashMap<>();

	static {
		if (ON) mcopt.metal.AtlasDouble.takenOver = atlas -> OwnTerrain.get() != null && ensure(atlas) != null;
	}
	private static boolean dirty = true, warned;
	private static GpuTexture scratch;
	private static GpuTextureView scratchView;
	private static GpuBuffer ubos;
	private static int stride, scratchH;
	private static long verifyRects, verifyPixels, verifyBad;
	private static int verifyMax;

	private AnimOnePass() {
	}

	public static boolean upload(TextureAtlas atlas) {
		if (!ON || OwnTerrain.get() == null) return false;
		OwnAnimAtlasAccess a = (OwnAnimAtlasAccess) atlas;
		List<SpriteContents.AnimationState> states = a.mcopt$animationStates();
		boolean any = false;
		for (SpriteContents.AnimationState s : states) any |= s.needsToDraw();
		if (!any) return true;
		if (PENDING.containsKey(atlas)) flush();
		Entry e = ensure(atlas);
		if (e == null) return false;
		boolean[] drawn = new boolean[states.size()];
		for (int i = 0; i < drawn.length; i++) drawn[i] = states.get(i).needsToDraw();
		e.uploads++;
		boolean verify = VERIFY > 0 && e.uploads % VERIFY == 0;
		boolean[] stale = mcopt.metal.AtlasDouble.staleOfSpare(atlas);
		boolean[] forced = new boolean[drawn.length];
		if (stale != null) {
			for (int i = 0; i < drawn.length && i < stale.length; i++) forced[i] = stale[i] && !drawn[i];
			verify = false;
		}
		PENDING.put(atlas, drawn);
		PENDING_VERIFY.put(atlas, verify);
		PENDING_FORCED.put(atlas, forced);
		return !verify;
	}

	private static Entry ensure(TextureAtlas atlas) {
		OwnAnimAtlasAccess a = (OwnAnimAtlasAccess) atlas;
		List<SpriteContents.AnimationState> states = a.mcopt$animationStates();
		Entry e = ENTRIES.get(atlas);
		if (e == null || e.states != states) {
			if (REJECTED.get(atlas) == states) return null;
			MISMATCH = null;
			e = entry(atlas, a, states);
			String why = e == null ? (MISMATCH != null ? MISMATCH : "sprite and animation-state counts differ") : preflight(atlas, a, e);
			if (why != null) {
				reject(atlas, states, why);
				return null;
			}
			ENTRIES.put(atlas, e);
			dirty = true;
		}
		return e;
	}

	private static void reject(TextureAtlas atlas, Object states, String why) {
		REJECTED.put(atlas, states);
		if (ENTRIES.remove(atlas) != null) dirty = true;
		System.out.println("mcopt-own: one-pass animation: " + atlas.location() + " stays with vanilla's upload (" + why + ")");
	}

	private static String preflight(TextureAtlas atlas, OwnAnimAtlasAccess a, Entry e) {
		GpuTexture tex = atlas.getTexture();
		e.key = atlas.location().toString();
		if (tex.getMipLevels() < e.maxMip + 1) return "the atlas texture has " + tex.getMipLevels() + " mip levels, its animations draw " + (e.maxMip + 1);
		for (Map.Entry<TextureAtlas, Entry> o : ENTRIES.entrySet()) {
			if (o.getKey() != atlas && o.getKey().getTexture().getFormat() != tex.getFormat()) return "its format differs from " + o.getKey().location() + "'s";
		}
		int aw = a.mcopt$width(), ah = a.mcopt$height();
		for (TextureAtlasSprite s : e.sprites) {
			int p = ((OwnAnimSpriteAccess) s).mcopt$padding(), w = s.contents().width() + 2 * p, h = s.contents().height() + 2 * p;
			for (int mip = 0; mip <= e.maxMip; mip++) {
				int mw = w >> mip, mh = h >> mip;
				if (mw == 0 || mh == 0) continue;
				if (mw > WIDTH_LIMIT) return "sprite " + s.contents().name() + " is " + mw + " px wide at mip " + mip + ", the scratch " + WIDTH_LIMIT;
				int dx = s.getX() >> mip, dy = s.getY() >> mip;
				if (dx < 0 || dy < 0 || dx + mw > Math.max(1, aw >> mip) || dy + mh > Math.max(1, ah >> mip))
					return "sprite " + s.contents().name() + "'s rectangle leaves the atlas at mip " + mip;
			}
		}
		List<Entry> all = new ArrayList<>(ENTRIES.values());
		all.removeIf(o -> o == ENTRIES.get(atlas));
		all.add(e);
		int max = TEST.equals("tall") ? 64 : RenderSystem.getDevice().getDeviceInfo().limits().maxTextureSize();
		int h = layout(all, false);
		if (h > max) return "the scratch would be " + h + " px tall, the limit " + max;
		boolean writable = !TEST.equals("nowrite") && mcopt.metal.MetalBridge.shaderWritable(tex);
		if (AnimCopy.ATLAS_WRITE && writable && kernelReady()) e.copy = 1;
		else if (BLIT) e.copy = 2;
		else return writable ? "the compute copy's kernel isn't available" : "its texture isn't shader-writable (the compute copy needs it)";
		return null;
	}

	private static boolean kernelReady() {
		if (kernelReady == 0) {
			OwnTerrain own = OwnTerrain.get();
			if (own == null) return false;
			kernelReady = OwnNative.animKernelReady(own.ownHandle()) ? 1 : -1;
		}
		return kernelReady > 0;
	}

	private static int layout(List<Entry> entries, boolean assign) {
		List<Entry> list = new ArrayList<>(entries);
		list.sort(java.util.Comparator.comparing(o -> o.key));
		int x = 0, y = 0, shelf = 0, count = 0;
		for (Entry e : list) {
			int n = e.sprites.size(), mips = e.maxMip + 1;
			if (assign) {
				e.rx = new int[n][mips];
				e.ry = new int[n][mips];
				e.rw = new int[n][mips];
				e.rh = new int[n][mips];
				e.uboBase = count;
			}
			count += n * mips;
			for (int i = 0; i < n; i++) {
				TextureAtlasSprite s = e.sprites.get(i);
				int p = ((OwnAnimSpriteAccess) s).mcopt$padding(), w = s.contents().width() + 2 * p, h = s.contents().height() + 2 * p;
				for (int mip = 0; mip < mips; mip++) {
					int mw = w >> mip, mh = h >> mip;
					if (assign) {
						e.rw[i][mip] = mw;
						e.rh[i][mip] = mh;
					}
					if (mw == 0 || mh == 0) continue;
					if (x + mw > SCRATCH_W) {
						x = 0;
						y += shelf;
						shelf = 0;
					}
					if (assign) {
						e.rx[i][mip] = x;
						e.ry[i][mip] = y;
					}
					x += mw;
					shelf = Math.max(shelf, mh);
				}
			}
		}
		return Math.max(16, Integer.highestOneBit(Math.max(1, y + shelf) - 1) << 1);
	}

	private static Entry entry(TextureAtlas atlas, OwnAnimAtlasAccess a, List<SpriteContents.AnimationState> states) {
		List<TextureAtlasSprite> sprites = new ArrayList<>();
		for (TextureAtlasSprite s : a.mcopt$sprites()) if (s.isAnimated()) sprites.add(s);
		if (sprites.size() != states.size()) {
			if (!warned) System.out.println("mcopt-own: one-pass animation off for " + atlas.location() + " (sprites " + sprites.size() + ", states " + states.size() + ")");
			warned = true;
			return null;
		}
		for (int i = 0; i < sprites.size(); i++) {
			SpriteContents owner = OWNER.get(states.get(i));
			if (TEST.equals("mismatch") && i == sprites.size() - 1) owner = null;
			if (owner != sprites.get(i).contents()) {
				MISMATCH = "animation state " + i + " doesn't belong to sprite " + sprites.get(i).contents().name()
					+ (owner == null ? " (its owner is unknown)" : " (it belongs to " + owner.name() + ")");
				return null;
			}
		}
		Entry e = new Entry();
		e.states = states;
		e.sprites = sprites;
		e.maxMip = a.mcopt$maxMipLevel();
		return e;
	}

	private static void rebuild() {
		int count = 0;
		for (Entry e : ENTRIES.values()) count += e.sprites.size() * (e.maxMip + 1);
		int h = layout(new ArrayList<>(ENTRIES.values()), true);
		GpuDevice device = RenderSystem.getDevice();
		if (scratch == null || h != scratchH) {
			if (scratchView != null) scratchView.close();
			if (scratch != null) scratch.close();
			GpuTexture any = ENTRIES.keySet().iterator().next().getTexture();
			scratch = device.createTexture(() -> "mcopt one-pass animation scratch", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC
				| GpuTexture.USAGE_TEXTURE_BINDING, any.getFormat(), SCRATCH_W, h, 1, 1);
			scratchView = device.createTextureView(scratch);
			scratchH = h;
		}
		stride = Mth.roundToward(SpriteContents.UBO_SIZE, device.getDeviceInfo().limits().minUniformOffsetAlignment());
		ByteBuffer data = MemoryUtil.memAlloc(stride * Math.max(1, count));
		try {
			for (Entry e : ENTRIES.values()) {
				for (int i = 0; i < e.sprites.size(); i++) {
					TextureAtlasSprite s = e.sprites.get(i);
					int p = ((OwnAnimSpriteAccess) s).mcopt$padding(), w = s.contents().width(), hh = s.contents().height();
					for (int mip = 0; mip <= e.maxMip; mip++) {
						Std140Builder.intoBuffer(MemoryUtil.memSlice(data, (e.uboBase + i * (e.maxMip + 1) + mip) * stride, stride))
							.putMat4f(new Matrix4f().ortho2D(0.0F, (float) SCRATCH_W, 0.0F, (float) h))
							.putMat4f(new Matrix4f().translate((float) e.rx[i][mip], (float) e.ry[i][mip], 0.0F)
								.scale((float) (w + p * 2 >> mip), (float) (hh + p * 2 >> mip), 1.0F))
							.putFloat((float) p / (float) w)
							.putFloat((float) p / (float) hh)
							.putInt(mip);
					}
				}
			}
			if (ubos != null) ubos.close();
			ubos = device.createBuffer(() -> "mcopt one-pass animation UBOs", GpuBuffer.USAGE_UNIFORM, data);
		} finally {
			MemoryUtil.memFree(data);
		}
		dirty = false;
		System.out.println("mcopt-own: one-pass animation: " + ENTRIES.size() + " atlases, " + count + " sprite mips, scratch " + SCRATCH_W + "x" + h
			+ " (" + SCRATCH_W * h * 4 / 1024 + " KB)");
	}

	public static void flush() {
		if (PENDING.isEmpty()) return;
		if (dirty) rebuild();
		GpuDevice device = RenderSystem.getDevice();
		CommandEncoder encoder = device.createCommandEncoder();
		try (RenderPass pass = encoder.createRenderPass(() -> "mcopt one-pass animation", scratchView, Optional.of(new Vector4f(0, 0, 0, 0)))) {
			RenderSystem.bindDefaultUniforms(pass);
			for (Map.Entry<TextureAtlas, boolean[]> pe : PENDING.entrySet()) {
				Entry e = ENTRIES.get(pe.getKey());
				boolean[] drawn = pe.getValue(), forced = PENDING_FORCED.get(pe.getKey());
				for (int i = 0; i < drawn.length; i++) {
					boolean force = forced != null && forced[i];
					if (!drawn[i] && !force) continue;
					if (force) ((mcopt.metal.mixin.AtlasDoubleStateAccess) (Object) e.states.get(i)).mcopt$adSetDirty(true);
					for (int mip = 0; mip <= e.maxMip; mip++) {
						if (e.rw[i][mip] == 0 || e.rh[i][mip] == 0) continue;
						e.states.get(i).drawToAtlas(pass, ubos.slice((long) (e.uboBase + i * (e.maxMip + 1) + mip) * stride, SpriteContents.UBO_SIZE));
					}
					if (force) ((mcopt.metal.mixin.AtlasDoubleStateAccess) (Object) e.states.get(i)).mcopt$adSetDirty(false);
				}
			}
		}
		OwnTerrain own = OwnTerrain.get();
		flushes++;
		Map<TextureAtlas, String> failed = new LinkedHashMap<>();
		for (Map.Entry<TextureAtlas, boolean[]> pe : PENDING.entrySet()) {
			TextureAtlas atlas = pe.getKey();
			Entry e = ENTRIES.get(atlas);
			boolean[] drawn = pe.getValue(), forced = PENDING_FORCED.get(atlas);
			boolean verify = PENDING_VERIFY.getOrDefault(atlas, false);
			GpuTexture spare = mcopt.metal.AtlasDouble.spare(atlas);
			if (!verify) {
				if (own == null) continue;
				try (MemoryStack stack = MemoryStack.stackPush()) {
					long rects = stack.nmalloc(4, Math.max(1, drawn.length * (e.maxMip + 1)) * 28);
					int count = 0;
					for (int mip = 0; mip <= e.maxMip; mip++) {
						for (int i = 0; i < drawn.length; i++) {
							if (!drawn[i] && !(forced != null && forced[i]) || e.rw[i][mip] == 0 || e.rh[i][mip] == 0) continue;
							TextureAtlasSprite s = e.sprites.get(i);
							long r = rects + count * 28L;
							MemoryUtil.memPutInt(r, e.rx[i][mip]);
							MemoryUtil.memPutInt(r + 4, e.ry[i][mip]);
							MemoryUtil.memPutInt(r + 8, s.getX() >> mip);
							MemoryUtil.memPutInt(r + 12, s.getY() >> mip);
							MemoryUtil.memPutInt(r + 16, e.rw[i][mip]);
							MemoryUtil.memPutInt(r + 20, e.rh[i][mip]);
							MemoryUtil.memPutInt(r + 24, mip);
							count++;
						}
					}
					GpuTexture dstTex = spare != null ? spare : atlas.getTexture();
					long src = mcopt.metal.MetalBridge.textureHandle(scratch), dst = mcopt.metal.MetalBridge.textureHandle(dstTex);
					boolean ok;
					String why = "the native copy failed";
					if (e.copy == 1 && (TEST.equals("nowrite") || !mcopt.metal.MetalBridge.shaderWritable(dstTex))) {
						ok = false;
						why = "the copy's destination isn't shader-writable";
					} else if (e.copy == 2) {
						ok = OwnNative.animBlitMips(own.ownHandle(), own.encHandle(), src, dst, rects, count);
					} else {
						ok = OwnNative.animCopyMips(own.ownHandle(), own.encHandle(), src, dst, rects, count);
					}
					if (TEST.equals("copyfail") && flushes >= 40) ok = false;
					if (!ok) {
						failed.put(atlas, why);
						continue;
					}
				}
				if (spare != null) mcopt.metal.AtlasDouble.committed(atlas, drawn);
			}
			if (verify || VERIFY_COPY > 0 && e.uploads % VERIFY_COPY == 0) compare(atlas, e, drawn, verify ? "onepass-verify" : "onepass-verifycopy");
		}
		PENDING.clear();
		PENDING_VERIFY.clear();
		PENDING_FORCED.clear();
		for (Map.Entry<TextureAtlas, String> f : failed.entrySet()) {
			TextureAtlas atlas = f.getKey();
			reject(atlas, ((OwnAnimAtlasAccess) atlas).mcopt$animationStates(), f.getValue());
			((OwnAnimAtlasAccess) atlas).mcopt$uploadAnimationFrames();
		}
	}

	private static void compare(TextureAtlas atlas, Entry e, boolean[] drawn, String tag) {
		GpuDevice device = RenderSystem.getDevice();
		CommandEncoder encoder = device.createCommandEncoder();
		GpuTexture at = atlas.getTexture();
		int bpp = at.getFormat().blockSize();
		ByteBuffer[] sc = new ByteBuffer[1];
		GpuBuffer sb = device.createBuffer(() -> "mcopt one-pass verify scratch", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) SCRATCH_W * scratchH * bpp);
		int sh = scratchH;
		encoder.copyTextureToBuffer(scratch, sb, 0, () -> {
			try (GpuBufferSlice.MappedView v = sb.map(true, false)) {
				sc[0] = MemoryUtil.memAlloc(v.data().remaining()).put(v.data()).flip();
			}
			sb.close();
		}, 0);
		OwnAnimAtlasAccess a = (OwnAnimAtlasAccess) atlas;
		for (int mip = 0; mip <= e.maxMip; mip++) {
			int m = mip, aw = a.mcopt$width() >> mip, ah = a.mcopt$height() >> mip;
			GpuBuffer ab = device.createBuffer(() -> "mcopt one-pass verify atlas", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) aw * ah * bpp);
			encoder.copyTextureToBuffer(at, ab, 0, () -> {
				try (GpuBufferSlice.MappedView v = ab.map(true, false)) {
					ByteBuffer d = v.data();
					if (sc[0] == null) return;
					for (int i = 0; i < drawn.length; i++) {
						int w = e.rw[i][m], h = e.rh[i][m];
						if (!drawn[i] || w == 0 || h == 0) continue;
						TextureAtlasSprite s = e.sprites.get(i);
						int ax = s.getX() >> m, ay = s.getY() >> m, sx = e.rx[i][m], sy = e.ry[i][m];
						verifyRects++;
						for (int yy = 0; yy < h; yy++) {
							for (int xx = 0; xx < w; xx++) {
								int pa = ((ay + yy) * aw + ax + xx) * bpp, ps = ((sy + yy) * SCRATCH_W + sx + xx) * bpp, diff = 0;
								for (int c = 0; c < bpp; c++) diff = Math.max(diff, Math.abs((d.get(pa + c) & 255) - (sc[0].get(ps + c) & 255)));
								verifyPixels++;
								if (diff != 0) verifyBad++;
								verifyMax = Math.max(verifyMax, diff);
							}
						}
					}
					System.out.println("mcopt-" + tag + " " + atlas.location() + " mip " + m + ": rects " + verifyRects + ", pixels " + verifyPixels + ", different "
						+ verifyBad + ", max diff " + verifyMax);
					if (m == e.maxMip) {
						MemoryUtil.memFree(sc[0]);
						sc[0] = null;
					}
				}
				ab.close();
			}, mip);
		}
	}
}
