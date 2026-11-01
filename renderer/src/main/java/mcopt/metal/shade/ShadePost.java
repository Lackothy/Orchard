/*
 * Copyright 2026 mcopt-shade contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package mcopt.metal.shade;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import mcopt.metal.MetalBridge;
import mcopt.metal.MetalHooks;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

public final class ShadePost {
	public interface Hook {
		void run(Context c);
	}

	public record Context(long enc, Object encoder, long scene, long prevScene, long depth, int width, int height, int hdrFormat, ShadeFrame frame,
		ShadeLook look, int texWidth, int texHeight) {
	}

	public interface Resolver {
		long resolve(Context c);
	}

	private static volatile @Nullable Resolver resolver;

	public static void setResolver(@Nullable Resolver r) {
		resolver = r;
	}

	private record Ordered(int order, Hook hook) {
	}

	private static final List<Ordered> BEFORE_TONEMAP = new ArrayList<>();

	public static synchronized void addBeforeTonemap(int order, Hook hook) {
		BEFORE_TONEMAP.add(new Ordered(order, hook));
		BEFORE_TONEMAP.sort((a, b) -> Integer.compare(a.order, b.order));
	}

	private static boolean initialized, failed;
	private static long library, psoDown0, psoDown, psoUp, psoFinal, psoEncode, psoDecode, psoHistogram, psoExposure, milkyWay;
	private static long psoFinalXdr, xdrExcess;
	private static long psoFinalHdr, psoFinalHdrDims, psoFinalHdrLean;
	private static boolean fullHdrFailed;
	private static int xdrFormat;
	private static long psoFinalDims, psoFinalXdrDims;
	private static long psoMotion, motionTarget;
	private static int motionW, motionH, motionFormat;
	private static boolean motionFailed;
	private static final ByteBuffer MOTION_PARAMS = ByteBuffer.allocateDirect(32).order(ByteOrder.LITTLE_ENDIAN);
	private static long psoFinalLean, psoFinalXdrLean;
	private static boolean loggedLean;
	private static int xdrWidth, xdrHeight;
	private static long psoUpscale, outScaled;
	private static int outScaledWidth, outScaledHeight;
	private static final ByteBuffer UPSCALE_PARAMS = ByteBuffer.allocateDirect(16).order(ByteOrder.LITTLE_ENDIAN);
	private static final long[] hdr = new long[2];
	private static long[] bloomLevels = new long[0], bloomAccum = new long[0];
	private static int width, height, frame, bloomWidth, bloomHeight;
	private static boolean world;
	private static boolean bridgeOpen, bridgeUsed;
	private static boolean prevWritten;
	private static @Nullable GpuTexture mainColor;
	private static @Nullable Object encoder;
	private static long enc;
	private static final List<long[]> retired = new ArrayList<>();
	private static final ByteBuffer params = ByteBuffer.allocateDirect(96).order(ByteOrder.LITTLE_ENDIAN);
	private static boolean loggedDh;
	private static int liveWidth, liveHeight;
	private static @Nullable GpuTexture worldDepth;
	private static final boolean VL_UNJITTER = Boolean.parseBoolean(System.getProperty("mcopt.shade.vl.unjitter", "true"));
	private static final boolean DH_MERGE_MAIN = !"false".equals(System.getProperty("mcopt.shade.dh.mergeMain"));
	private static final boolean DH_NO_LOAD = !"false".equals(System.getProperty("mcopt.shade.dh.noLoad"));
	private static final ByteBuffer downParams = ByteBuffer.allocateDirect(16).order(ByteOrder.LITTLE_ENDIAN);
	private static long vlLight, vlDist;
	private static long histogramSource;
	private static float vlMaxDist, vlScaleU = 1f, vlScaleV = 1f, vlAirFloor;

	private ShadePost() {
	}

	public static long sceneTexture() {
		return ShadeConfig.HDR && initialized ? hdr[frame & 1] : 0;
	}

	public static void setVolumetric(long light, long dist, float maxDist) {
		setVolumetric(light, dist, maxDist, 1f, 1f);
	}

	public static void setVolumetric(long light, long dist, float maxDist, float uScale, float vScale) {
		setVolumetric(light, dist, maxDist, uScale, vScale, 0f);
	}

	public static void setVolumetric(long light, long dist, float maxDist, float uScale, float vScale, float airFloor) {
		vlAirFloor = airFloor;
		vlLight = light;
		vlDist = dist;
		vlMaxDist = maxDist;
		vlScaleU = uScale;
		vlScaleV = vScale;
	}

	private static mcopt.metal.shade.taa.@Nullable ShadeTaa taa() {
		if (!mcopt.metal.shade.taa.ShadeTaa.ON) return null;
		mcopt.metal.shade.taa.ShadeTaa t = mcopt.metal.shade.taa.ShadeTaa.get();
		return t != null && t.swapped() && t.outputTarget() != null ? t : null;
	}

	public static int liveWidth() {
		return liveWidth;
	}

	public static int liveHeight() {
		return liveHeight;
	}

	public static boolean inWorld() {
		return world;
	}

	static synchronized boolean init() {
		if (initialized) return true;
		if (failed || !ShadeConfig.HDR) return false;
		try {
			long t0 = ShadeCompile.now();
			library = ShadeRender.library(ShadeMsl.post());
			psoDown0 = ShadeRender.pipeline(library, "shade_fs_vs", "shade_bloom_down0", ShadeRender.RGBA16F, 0);
			psoDown = ShadeRender.pipeline(library, "shade_fs_vs", "shade_bloom_down", ShadeRender.RGBA16F, 0);
			psoUp = ShadeRender.pipeline(library, "shade_fs_vs", "shade_bloom_up", ShadeRender.RGBA16F, 0);
			psoFinal = ShadeRender.pipeline(library, "shade_fs_vs", "shade_final", ShadeRender.RGBA8, 0);
			psoUpscale = ShadeRender.pipeline(library, "shade_fs_vs", "shade_upscale", ShadeRender.RGBA8, 0);
			psoFinalLean = ShadeRender.pipeline(library, "shade_fs_vs", "shade_final_lean", ShadeRender.RGBA8, 0);
			boolean dimsVariant = mcopt.metal.shade.dims.DimsConfig.DIMS || mcopt.metal.shade.dims.DimsConfig.MEDIA;
			if (dimsVariant) psoFinalDims = ShadeRender.pipeline(library, "shade_fs_vs", "shade_final_dims", ShadeRender.RGBA8, 0);
			if (mcopt.metal.shade.dims.ShadeXdr.active()) {
				psoFinalXdr = mcopt.metal.shade.dims.ShadeXdr.pipeline2(ShadeBindings.ctx(), library, "shade_fs_vs", "shade_final_xdr", ShadeRender.RGBA8,
					ShadeRender.RG11B10F);
				psoFinalXdrLean = mcopt.metal.shade.dims.ShadeXdr.pipeline2(ShadeBindings.ctx(), library, "shade_fs_vs", "shade_final_xdr_lean",
					ShadeRender.RGBA8, ShadeRender.RG11B10F);
				if (dimsVariant) psoFinalXdrDims = mcopt.metal.shade.dims.ShadeXdr.pipeline2(ShadeBindings.ctx(), library, "shade_fs_vs", "shade_final_xdr_dims",
					ShadeRender.RGBA8, ShadeRender.RG11B10F);
			}
			psoEncode = ShadeRender.pipeline(library, "shade_fs_vs", "shade_bridge_encode", ShadeRender.RGBA8, 0);
			psoDecode = ShadeRender.pipeline(library, "shade_fs_vs", "shade_bridge_decode", ShadeConfig.HDR_FORMAT, 0);
			psoHistogram = ShadeRender.computePipeline(library, "shade_histogram");
			psoExposure = ShadeRender.computePipeline(library, "shade_exposure");
			long bake = ShadeRender.pipeline(library, "shade_fs_vs", "shade_milkyway_bake", ShadeRender.RGBA16F, 0);
			ShadeCompile.setup("post chain", t0);
			milkyWay = ShadeRender.texture(ShadeRender.RGBA16F, 1024, 512, false, "mcopt shade: Milky Way");
			ShadeNative.bake(ShadeBindings.ctx(), bake, milkyWay);
			MetalBridge.release(bake);
			ShadeRender.resource(0, milkyWay);
			MetalHooks.addRedirector(0, ShadePost::redirect);
			initialized = true;
			System.out.println("[mcopt] shade: HDR post chain ready (" + ShadeConfig.describe() + "; " + mcopt.metal.shade.dims.DimsConfig.describe() + ")");
			return true;
		} catch (Throwable e) {
			failed = true;
			System.out.println("[mcopt] shade: HDR post chain failed, M1's tone curve instead: " + e);
			ShadeRobust.postFailed();
			e.printStackTrace();
			return false;
		}
	}

	public static boolean active() {
		return initialized && !failed;
	}

	static void beginWorld(Object metalEncoder, long nativeEnc, RenderTarget main) {
		if (!init()) return;
		encoder = metalEncoder;
		enc = nativeEnc;
		releaseRetired();
		GpuTexture color = main.getColorTexture();
		if (color == null) return;
		int w = color.getWidth(0), h = color.getHeight(0);
		boolean resized = w != width || h != height || hdr[0] == 0;
		if (resized) resize(w, h);
		frame++;
		mainColor = color;
		worldDepth = main.getDepthTexture();
		world = true;
		bridgeOpen = false;
		bridgeUsed = false;
		mcopt.metal.shade.dims.DimsDebug.beginFrame();
		vlLight = vlDist = 0;
		liveWidth = w;
		liveHeight = h;
		long prevScene = hdr[(frame + 1) & 1];
		boolean prevValid = !resized && prevWritten;
		mcopt.metal.shade.taa.ShadeTaa taa = taa();
		if (taa != null) {
			liveWidth = taa.width(w);
			liveHeight = taa.height(h);
			long history = taa.history();
			if (history != 0) prevScene = history;
			prevValid = history != 0 && taa.historyValid();
		} else if (hdr[0] == hdr[1]) {
			prevScene = 0;
			prevValid = false;
		}
		ShadeBindings.setPrevSceneValid(prevValid);
		ShadeRender.resource(1, prevScene);
		ShadeRender.resource(2, hdr[frame & 1]);
		long prevDepth = taa != null && prevValid ? taa.historyDepth() : 0;
		ShadeRender.resource(5, prevDepth);
		ShadeBindings.setPrevDepthValid(prevDepth != 0);
	}

	private static void resize(int w, int h) {
		retire(hdr[0]);
		if (hdr[1] != hdr[0]) retire(hdr[1]);
		prevWritten = false;
		width = w;
		height = h;
		hdr[0] = ShadeRender.texture(ShadeConfig.HDR_FORMAT, w, h, false, "mcopt shade: HDR world A");
		hdr[1] = mcopt.metal.shade.taa.ShadeTaa.ON ? hdr[0] : ShadeRender.texture(ShadeConfig.HDR_FORMAT, w, h, false, "mcopt shade: HDR world B");
		sizeBloom(w, h);
		ShadeFrameUpdater.LOOK.snapExposure();
		System.out.println("[mcopt] shade: HDR targets " + w + "x" + h + ", bloom " + bloomLevels.length + " levels");
	}

	private static void sizeBloom(int w, int h) {
		if (w == bloomWidth && h == bloomHeight) return;
		for (long t : bloomLevels) retire(t);
		for (long t : bloomAccum) retire(t);
		bloomWidth = w;
		bloomHeight = h;
		int n = ShadeConfig.BLOOM ? ShadeConfig.BLOOM_LEVELS : 0;
		bloomLevels = new long[n];
		bloomAccum = new long[n];
		int lw = w, lh = h;
		for (int i = 0; i < n; i++) {
			lw = Math.max(1, (lw + 1) / 2);
			lh = Math.max(1, (lh + 1) / 2);
			bloomLevels[i] = ShadeRender.texture(ShadeRender.RGBA16F, lw, lh, true, "mcopt shade: bloom L" + i);
			bloomAccum[i] = i == 0 ? 0 : ShadeRender.texture(ShadeRender.RGBA16F, lw, lh, true, "mcopt shade: bloom U" + i);
		}
		ShadeRender.resource(3, n > 1 ? bloomAccum[1] : 0);
	}

	private static long motion(long scene, long depth) {
		float strength = Live.f("mcopt.shade.motionBlur", 0f, 0f, 2f);
		if (strength <= 0f || depth == 0 || motionFailed) {
			if (motionTarget != 0) { retired.add(new long[] {motionTarget, frame + 8}); motionTarget = 0; }
			return scene;
		}
		try {
			if (psoMotion == 0) {
				long lib = ShadeRender.library(ShadeMsl.prelude() + ShadeMsl.resource("motion_blur.metal"));
				try { psoMotion = ShadeRender.pipeline(lib, "shade_motion_vs", "shade_motion_fs", ShadeConfig.HDR_FORMAT, 0); }
				finally { MetalBridge.release(lib); }
			}
			int w = ShadeRender.width(scene), h = ShadeRender.height(scene);
			if (motionTarget == 0 || w != motionW || h != motionH || motionFormat != ShadeConfig.HDR_FORMAT) {
				if (motionTarget != 0) retired.add(new long[] {motionTarget, frame + 8});
				motionTarget = 0;
				motionTarget = ShadeRender.texture(ShadeConfig.HDR_FORMAT, w, h, false, "mcopt motion blur");
				motionW = w; motionH = h; motionFormat = ShadeConfig.HDR_FORMAT;
			}
			boolean resolved = scene != hdr[frame & 1];
			MOTION_PARAMS.clear();
			MOTION_PARAMS.putFloat(strength).putFloat(resolved ? 1f : (float)liveWidth/w)
				.putFloat(resolved ? 1f : (float)liveHeight/h).putFloat(resolved ? 1f : 0f)
				.putFloat(liveWidth).putFloat(liveHeight).putFloat(0f).putFloat(0f).flip();
			pass(psoMotion, motionTarget, ShadeRender.DONT_CARE, new long[] {scene, depth}, MOTION_PARAMS, "mcopt shade: motion blur");
			return motionTarget;
		} catch (RuntimeException e) {
			motionFailed = true;
			System.out.println("[mcopt] shade: motion blur unavailable: " + e);
			return scene;
		}
	}

	private static boolean fullHdrReady() {
		if (psoFinalHdr != 0) return true;
		if (fullHdrFailed || library == 0) return false;
		try {
			long t0 = System.nanoTime();
			long ctx = ShadeBindings.ctx();
			psoFinalHdrLean = mcopt.metal.shade.dims.ShadeXdr.pipeline2(ctx, library, "shade_fs_vs", "shade_final_hdr_lean", ShadeRender.RGBA8, ShadeRender.RGBA16F);
			if (psoFinalDims != 0) psoFinalHdrDims = mcopt.metal.shade.dims.ShadeXdr.pipeline2(ctx, library, "shade_fs_vs", "shade_final_hdr_dims", ShadeRender.RGBA8, ShadeRender.RGBA16F);
			psoFinalHdr = mcopt.metal.shade.dims.ShadeXdr.pipeline2(ctx, library, "shade_fs_vs", "shade_final_hdr", ShadeRender.RGBA8, ShadeRender.RGBA16F);
			System.out.println(String.format(java.util.Locale.ROOT, "[mcopt] shade: full-scene HDR final passes built (%.0f ms)", (System.nanoTime() - t0) / 1e6));
			return true;
		} catch (RuntimeException e) {
			fullHdrFailed = true;
			System.out.println("[mcopt] shade: full-scene HDR unavailable, SDR frame instead: " + e);
			return false;
		}
	}

	private static void retire(long t) {
		if (t != 0) retired.add(new long[] {t, frame + 4});
	}

	private static void releaseRetired() {
		retired.removeIf(r -> {
			if (r[1] > frame) return false;
			MetalBridge.release(r[0]);
			return true;
		});
	}

	private static MetalHooks.@Nullable Redirect redirect(RenderPassDescriptor d) {
		if (!world && ShadeEntity.HAND) return handRedirect(d);
		if (!world || mainColor == null) return null;
		if (mcopt.metal.shade.dims.DimsDebug.logging()) mcopt.metal.shade.dims.DimsDebug.pass(d, mainColor);
		String label = d.label().get();
		var colors = d.colorAttachments();
		boolean dhComposite = false;
		if (label != null && label.startsWith("distantHorizons:")) {
			boolean intoMain = colors.size() == 1 && colors.get(0) != null && colors.get(0).textureView().texture() == mainColor;
			boolean direct = mcopt.metal.shade.dh.ShadeDh.directFrame();
			if (direct && !bridgeOpen) applyPendingClear();
			if (!(intoMain && direct && !bridgeOpen)) {
				if (ShadeConfig.DH_BRIDGE && !bridgeOpen && !direct && !mcopt.metal.shade.dims.DimsDebug.noBridge()) openBridge();
				if (direct && DH_NO_LOAD && !intoMain && colors.size() == 1 && colors.get(0) != null && colors.get(0).clearValue().isEmpty()
					&& colors.get(0).textureView().baseMipLevel() == 0
					&& ("distantHorizons:DhFarFadeRenderer".equals(label) || "distantHorizons:CopyRenderer".equals(label))) {
					var t = colors.get(0).textureView().texture();
					MetalBridge.takePendingColorClear(encoder, t);
					return new MetalHooks.Redirect(new long[] {MetalBridge.textureHandle(t)}, new float[][] {MetalBridge.DONT_CARE}, t.getWidth(0),
						t.getHeight(0), 0, MetalHooks.Redirect.NO_CLEAR, null, null);
				}
				return null;
			}
			bridgeUsed = true;
			if (!loggedDh) {
				loggedDh = true;
				System.out.println("[mcopt] shade: Distant Horizons passes seen: its composite goes straight into the HDR frame (FT2; mcopt.shade.dh.direct=false: through the bridge)");
			}
			mcopt.metal.shade.dims.DimsDebug.note("DH composite straight into the HDR target");
			dhComposite = true;
		}
		if (colors.size() != 1 || colors.get(0) == null) return null;
		var attachment = colors.get(0);
		if (attachment.textureView().texture() != mainColor || attachment.textureView().baseMipLevel() != 0) return null;
		if (bridgeOpen) closeBridge();
		float[] clear = null;
		Optional<Vector4fc> explicit = attachment.clearValue();
		if (explicit.isPresent()) {
			Vector4fc c = explicit.get();
			clear = new float[] {c.x(), c.y(), c.z(), c.w()};
			MetalBridge.takePendingColorClear(encoder, mainColor);
		} else {
			clear = MetalBridge.takePendingColorClear(encoder, mainColor);
		}
		float[][] clears = null;
		if (clear != null) {
			float[] h = ShadeFrameUpdater.LOOK.ldrToHdr(clear[0], clear[1], clear[2]);
			clears = new float[][] {{h[0], h[1], h[2], clear[3]}};
		}
		int[] formats = {ShadeConfig.HDR_FORMAT | ShadeLean.tag() << ShadeMsl.LEAN_SHIFT};
		if (dhComposite && worldDepth != null && DH_MERGE_MAIN)
			return new MetalHooks.Redirect(new long[] {hdr[frame & 1]}, clears, width, height, MetalBridge.textureHandle(worldDepth),
				MetalHooks.Redirect.NO_CLEAR, null, formats);
		return MetalHooks.Redirect.formats(new long[] {hdr[frame & 1]}, clears, width, height, formats);
	}

	private static MetalHooks.@Nullable Redirect handRedirect(RenderPassDescriptor d) {
		String label = d.label().get();
		if (!"Item in hand".equals(label)) return null;
		var colors = d.colorAttachments();
		if (colors.size() != 1 || colors.get(0) == null || colors.get(0).clearValue().isPresent()) return null;
		var texture = colors.get(0).textureView().texture();
		if (colors.get(0).textureView().baseMipLevel() != 0 || MetalBridge.textureFormat(texture) != ShadeRender.RGBA8) return null;
		float[] clear = MetalBridge.takePendingColorClear(encoder, texture);
		return MetalHooks.Redirect.formats(new long[] {MetalBridge.textureHandle(texture)}, clear == null ? null : new float[][] {clear}, texture.getWidth(0),
			texture.getHeight(0), new int[] {ShadeEntity.HAND_FORMAT});
	}

	private static void openBridge() {
		bridgeOpen = true;
		bridgeUsed = true;
		if (!loggedDh) {
			loggedDh = true;
			System.out.println("[mcopt] shade: Distant Horizons passes seen: bridging them through the HDR frame");
		}
		long main = MetalBridge.textureHandle(mainColor);
		mcopt.metal.shade.dims.DimsDebug.note("bridge encode (HDR -> main)");
		applyPendingClear();
		pass(psoEncode, main, ShadeRender.DONT_CARE, new long[] {hdr[frame & 1]}, null, "mcopt shade: DH bridge encode");
	}

	private static void applyPendingClear() {
		float[] clear = MetalBridge.takePendingColorClear(encoder, mainColor);
		if (clear != null && mcopt.metal.shade.dims.DimsDebug.dhClear()) {
			float[] h = ShadeFrameUpdater.LOOK.ldrToHdr(clear[0], clear[1], clear[2]);
			MetalBridge.renderBegin(enc, new long[] {hdr[frame & 1]}, new float[][] {{h[0], h[1], h[2], clear[3]}}, 0, false, 0f, width, height);
			mcopt.metal.shade.dims.DimsDebug.note("main's pending clear applied to the HDR target");
		}
	}

	private static void closeBridge() {
		bridgeOpen = false;
		long main = MetalBridge.textureHandle(mainColor);
		mcopt.metal.shade.dims.DimsDebug.note("bridge decode (main -> HDR)");
		pass(psoDecode, hdr[frame & 1], ShadeRender.LOAD, new long[] {main}, null, "mcopt shade: DH bridge decode");
	}

	static void beforeHand(RenderTarget main) {
		if (!world) return;
		world = false;
		if (bridgeOpen) closeBridge();
		mcopt.metal.shade.dims.DimsDebug.endFrame();
		if (mcopt.metal.shade.shadow.EntityCasters.ON) mcopt.metal.shade.shadow.EntityCasters.commit(enc);
		if (mainColor == null) return;
		prevWritten = true;
		long scene = hdr[frame & 1], prev = hdr[0] != hdr[1] ? hdr[(frame + 1) & 1] : 0;
		GpuTexture depthTexture = worldDepth != null ? worldDepth : main.getDepthTexture();
		long depth = 0;
		if (depthTexture != null) {
			MetalBridge.flushClear(encoder, depthTexture);
			depth = MetalBridge.textureHandle(depthTexture);
		}
		ShadeFrame f = ShadeFrameUpdater.FRAME;
		ShadeLook look = ShadeFrameUpdater.LOOK;
		if (!BEFORE_TONEMAP.isEmpty()) {
			Context c = new Context(enc, encoder, scene, prev, depth, liveWidth, liveHeight, ShadeConfig.HDR_FORMAT, f, look, width, height);
			for (Ordered o : List.copyOf(BEFORE_TONEMAP)) {
				try {
					o.hook.run(c);
				} catch (RuntimeException e) {
					System.out.println("[mcopt] shade: post hook failed: " + e);
				}
			}
		}
		long bloomSource = scene;
		float srcU = (float) liveWidth / width, srcV = (float) liveHeight / height;
		GpuTexture out = mainColor;
		mcopt.metal.shade.taa.ShadeTaa taa = taa();
		float sharpen = 0f;
		if (taa != null && depth != 0) {
			scene = taa.resolveHdr(scene, depth);
			out = taa.outputTarget().getColorTexture();
			sharpen = mcopt.metal.shade.taa.TaaConfig.sharpen(taa.scale());
		} else {
			Resolver r = resolver;
			if (r != null) {
				try {
					long resolved = r.resolve(new Context(enc, encoder, scene, prev, depth, liveWidth, liveHeight, ShadeConfig.HDR_FORMAT, f, look, width, height));
					if (resolved != 0) {
						scene = resolved;
						bloomSource = resolved;
						srcU = srcV = 1f;
					}
				} catch (RuntimeException e) {
					System.out.println("[mcopt] shade: resolver failed: " + e);
				}
			}
		}
		if (out == null) return;
		long blurred = motion(scene, depth);
		if (blurred != scene) {
			scene = bloomSource = blurred;
			if (taa != null) srcU = srcV = 1f;
		}
		if (scene != hdr[frame & 1]) sizeBloom(ShadeRender.width(scene), ShadeRender.height(scene));
		else sizeBloom(width, height);
		float bloomNorm = 0f, w0 = 0f;
		int n = bloomLevels.length;
		float bloomShift = n > 0 && mcopt.metal.shade.vl.VlConfig.live("mcopt.shade.bloomScale", 1f) > 0.5f
			? Math.max(0f, (float) (Math.log(1964.0 / Math.max(bloomHeight, 1)) / Math.log(2.0))) : 0f;
		if (n > 4) n = Math.clamp(n - Math.round(bloomShift), 4, n);
		if (n > 0) {
			downParams.clear();
			downParams.putFloat(srcU).putFloat(srcV).putFloat(mcopt.metal.shade.dims.ShadeXdr.hotThreshold()).putFloat(0f).flip();
			pass(psoDown0, bloomLevels[0], ShadeRender.DONT_CARE, new long[] {bloomSource}, downParams, "mcopt shade: bloom down 0");
			for (int i = 1; i < n; i++) {
				pass(psoDown, bloomLevels[i], ShadeRender.DONT_CARE, new long[] {bloomLevels[i - 1]}, null, "mcopt shade: bloom down");
			}
			if (ShadeConfig.AUTO_EXPOSURE && n > 2) histogramSource = bloomLevels[2];
			float spectrum = ShadeSettings.look() == 1 ? 0.9f : ShadeSettings.look() == 2 ? 0.3f : 0.6f;
			float sum = 0f;
			for (int i = n - 1; i >= 1; i--) {
				float w = (float) Math.pow(i + 1 + bloomShift, spectrum);
				sum += w;
				params.clear();
				params.putFloat(w).putFloat(i == n - 1 ? 0f : 1f).putFloat(0f).putFloat(0f).flip();
				long coarser = i == n - 1 ? bloomLevels[i] : bloomAccum[i + 1];
				pass(psoUp, bloomAccum[i], ShadeRender.DONT_CARE, new long[] {bloomLevels[i], coarser}, params, "mcopt shade: bloom up");
			}
			w0 = (float) Math.pow(1 + bloomShift, spectrum);
			sum += w0;
			bloomNorm = 1f / sum;
		}
		params.clear();
		boolean vl = vlLight != 0 && vlDist != 0;
		float upMode = mcopt.metal.shade.vl.VlConfig.look("mcopt.shade.vl.upMode", 1f, 0f);
		params.putFloat(bloomNorm).putFloat(n > 1 ? 1f : 0f).putFloat(frame & 0xffff).putFloat(f.debug).putFloat(w0)
			.putFloat(upMode).putFloat(mcopt.metal.shade.vl.VlConfig.look("mcopt.shade.vl.upTol", 1.6f, 1f))
			.putFloat(vl && vlAirFloor > 0f ? 0f : mcopt.metal.shade.vl.VlConfig.look("mcopt.shade.vl.knee", 0.4f, 0f));
		params.putFloat(vl ? (vlLight == vlDist ? 2f : 1f) : 0f).putFloat(vlMaxDist).putFloat(vlScaleU).putFloat(vlScaleV);
		params.putFloat(liveWidth).putFloat(liveHeight).putFloat(sharpen)
			.putFloat((bridgeUsed ? 1f : 0f) + (taa != null && depth != 0 && VL_UNJITTER ? 2f : 0f));
		boolean xdr = psoFinalXdr != 0 && mcopt.metal.shade.dims.ShadeXdr.highlights()
			&& (!mcopt.metal.shade.dims.ShadeXdr.fullScene() || f.debug == 0f);
		if (xdr && mcopt.metal.shade.dims.ShadeXdr.fullScene() && !fullHdrReady()) xdr = false;
		boolean fullHdr = xdr && mcopt.metal.shade.dims.ShadeXdr.fullSceneFrame();
		boolean dimsPass = f.dimension != ShadeFrame.DIM_OVERWORLD || f.fogType == ShadeFrame.FOG_LAVA || f.fogType == ShadeFrame.FOG_POWDER_SNOW;
		if (xdr) mcopt.metal.shade.dims.ShadeXdr.params(params);
		else params.putFloat(0f).putFloat(1f).putFloat(1f).putFloat(1f);
		float edgeFix = vl && vlAirFloor > 0f ? mcopt.metal.shade.vl.VlConfig.live("mcopt.shade.vl.edgeFix", 0f) : 0f;
		params.putFloat(vl ? vlAirFloor : 0f).putFloat(edgeFix)
			.putFloat(vl && vlAirFloor > 0f && mcopt.metal.shade.vl.VlConfig.live("mcopt.shade.vl.airClamp", 1f) > 0f ? 1f : 0f)
			.putFloat(fullHdr ? mcopt.metal.shade.dims.ShadeXdr.sceneBrightness() : 0f);
		params.flip();
		boolean lean = !dimsPass && f.debug == 0f && upMode == 1f && (int) edgeFix == 0 && Live.b("mcopt.shade.finalLean", true);
		if (lean != loggedLean) {
			loggedLean = lean;
			System.out.println("[mcopt] shade: final pass " + (lean ? "lean (mcopt.shade.finalLean)" : "full"));
		}
		long mainHandle = MetalBridge.textureHandle(out);
		MetalBridge.takePendingColorClear(encoder, out);
		boolean upscale = taa != null && depth != 0 && taa.outputScaled() && psoUpscale != 0;
		long finalTarget = mainHandle;
		int targetW = out.getWidth(0), targetH = out.getHeight(0);
		if (upscale) {
			targetW = taa.outWidth();
			targetH = taa.outHeight();
			if (outScaled == 0 || targetW != outScaledWidth || targetH != outScaledHeight) {
				retire(outScaled);
				outScaledWidth = targetW;
				outScaledHeight = targetH;
				outScaled = ShadeRender.texture(ShadeRender.RGBA8, targetW, targetH, false, "mcopt shade: output");
			}
			finalTarget = outScaled;
		}
		long[] finalInputs = {scene, n > 1 ? bloomAccum[1] : scene, depth, n > 1 ? bloomLevels[0] : scene, vl ? vlLight : scene, vl ? vlDist : scene};
		if (xdr) {
			int ow = targetW, oh = targetH;
			int format = fullHdr ? ShadeRender.RGBA16F : ShadeRender.RG11B10F;
			if (xdrExcess == 0 || ow != xdrWidth || oh != xdrHeight || format != xdrFormat) {
				retire(xdrExcess);
				xdrWidth = ow;
				xdrHeight = oh;
				xdrFormat = format;
				xdrExcess = ShadeRender.texture(format, ow, oh, false, fullHdr ? "mcopt shade: HDR world output" : "mcopt shade: XDR excess");
			}
			if (encoder != null && MetalBridge.tracing(encoder)) System.out.println("mcopt-metal trace: shade pass 'mcopt shade: final (XDR)'");
			long finalPso = fullHdr ? (dimsPass && psoFinalHdrDims != 0 ? psoFinalHdrDims : lean ? psoFinalHdrLean : psoFinalHdr)
				: (dimsPass && psoFinalXdrDims != 0 ? psoFinalXdrDims : lean ? psoFinalXdrLean : psoFinalXdr);
			mcopt.metal.shade.dims.ShadeXdr.finalPass(enc, finalPso, finalTarget, xdrExcess, finalInputs, params,
				fullHdr ? "mcopt shade: final (full HDR)" : "mcopt shade: final (XDR)");
		} else {
			pass(dimsPass && psoFinalDims != 0 ? psoFinalDims : lean && psoFinalLean != 0 ? psoFinalLean : psoFinal, finalTarget, ShadeRender.DONT_CARE, finalInputs,
				params, "mcopt shade: final");
		}
		if (upscale) {
			UPSCALE_PARAMS.clear();
			UPSCALE_PARAMS.putFloat(Math.max(mcopt.metal.shade.vl.VlConfig.live("mcopt.shade.outputSharpen", 0.3f), 0f)).putFloat(0f).putFloat(0f).putFloat(0f).flip();
			pass(psoUpscale, mainHandle, ShadeRender.DONT_CARE, new long[] {outScaled}, UPSCALE_PARAMS, "mcopt shade: upscale");
		}
		if (fullHdr) mcopt.metal.shade.dims.ShadeXdr.fullFrame(enc, xdrExcess, mainHandle);
		if (histogramSource != 0) {
			long l2 = histogramSource;
			histogramSource = 0;
			ShadeRender.dispatch(enc, psoHistogram, ShadeRender.width(l2), ShadeRender.height(l2), 16, 16, new long[] {l2}, null, "mcopt shade: histogram");
		}
		ShadeRender.dispatch(enc, psoExposure, 1, 1, 1, 1, null, null, "mcopt shade: exposure");
		vlLight = vlDist = 0;
	}

	private static void pass(long pso, long target, int load, long @Nullable [] textures, @Nullable ByteBuffer p, String label) {
		if (encoder != null && MetalBridge.tracing(encoder)) System.out.println("mcopt-metal trace: shade pass '" + label + "'");
		ShadeRender.pass(enc, pso, target, load, textures, p, label);
	}

	public static boolean bridgedThisFrame() {
		return bridgeUsed;
	}
}
