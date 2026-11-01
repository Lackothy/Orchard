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
package mcopt.metal.shade.vl;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import mcopt.metal.MetalBridge;
import mcopt.metal.shade.Shade;
import mcopt.metal.shade.ShadeFrame;
import mcopt.metal.shade.ShadeLook;
import mcopt.metal.shade.ShadePost;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

public final class ShadeVl {
	private static final int PARAM_BYTES = 496;
	private static final int ERR = 2048;
	private static final float SEA = 63f;
	private static final float HDR_HAZE = 4.6f, HDR_MIST = 4.2f;
	private static final float HDR_AIR = 0.6f, HDR_WATER = VlConfig.WATER_M3 ? 0.6f : 0.45f, HDR_VEIL = 0.1f, HDR_EXT = 0.35f, HDR_OPEN_CUT = 0.7f, HDR_CARVE = 0.6f,
		HDR_CARVE_WATER = VlConfig.WATER_M3 ? 0.62f : 0.55f;

	private static long state;
	private static boolean failed, logged;
	private static final ByteBuffer PARAMS = MemoryUtil.memAlloc(PARAM_BYTES).order(ByteOrder.nativeOrder());
	private static final long CANOPY_OUT = MemoryUtil.nmemCalloc(2, 4);
	private static final long TIMES_OUT = MemoryUtil.nmemCalloc(4, 4);
	private static final boolean FOLD_SKIP = Boolean.getBoolean("mcopt.shade.vl.foldSkip");
	private static final long OUTPUTS_OUT = MemoryUtil.nmemCalloc(2, 8);
	private static double prevX, prevY, prevZ;
	private static boolean hasPrevious;
	private static long lastNanos, lastLog;
	private static int frame;
	private static boolean lastUnderwater;
	private static int allocW, allocH, prevLiveW, prevLiveH;
	private static Object lastLevel;
	private static float humidity = -1f;
	private static boolean resetCanopy = true;
	public static final int HOOK_ORDER = 100;
	private static boolean hooked, stateHdr;
	private static boolean airActive;
	private static float airEndUsed;
	private static float lastCell = -1f;

	private ShadeVl() {
	}

	public static void beforeHand() {
		if (!VlConfig.ENABLED || failed) return;
		if (ShadePost.active()) {
			if (!hooked) {
				hooked = true;
				ShadePost.addBeforeTonemap(HOOK_ORDER, ShadeVl::beforeTonemap);
			}
			return;
		}
		try {
			Minecraft mc = Minecraft.getInstance();
			Object encoder = MetalBridge.encoder(((FrontendCommandEncoder) RenderSystem.getDevice().createCommandEncoder()).backend());
			if (encoder == null || MetalBridge.inRenderPass(encoder)) return;
			RenderTarget main = mc.gameRenderer.mainRenderTarget();
			var color = main.getColorTexture();
			var depth = main.getDepthTexture();
			if (color == null || depth == null || !ready(encoder, false)) return;
			MetalBridge.flushClear(encoder, color);
			MetalBridge.flushClear(encoder, depth);
			int liveW = main.width, liveH = main.height;
			mcopt.metal.shade.taa.ShadeTaa taa = mcopt.metal.shade.taa.ShadeTaa.ON ? mcopt.metal.shade.taa.ShadeTaa.get() : null;
			if (taa != null && taa.swapped()) {
				liveW = Math.min(taa.width(main.width), main.width);
				liveH = Math.min(taa.height(main.height), main.height);
			}
			render(encoder, MetalBridge.enc(encoder), MetalBridge.textureHandle(color), MetalBridge.textureHandle(depth), liveW, liveH, main.width, main.height, null);
		} catch (Throwable t) {
			fail(t);
		}
	}

	private static void beforeTonemap(ShadePost.Context c) {
		if (failed || c.depth() == 0 || c.scene() == 0) return;
		try {
			if (!ready(c.encoder(), true)) return;
			render(c.encoder(), c.enc(), c.scene(), c.depth(), c.width(), c.height(), c.texWidth(), c.texHeight(), c.look());
		} catch (Throwable t) {
			fail(t);
		}
	}

	private static void fail(Throwable t) {
		failed = true;
		System.out.println("[mcopt] shade: volumetric light failed, off from now on: " + t);
		t.printStackTrace();
	}

	private static boolean ready(Object encoder, boolean hdr) {
		if (state != 0) return stateHdr == hdr;
		stateHdr = hdr;
		return init(MetalBridge.ctx(encoder), hdr);
	}

	private static void render(Object encoder, long enc, long color, long depth, int width, int height, int texWidth, int texHeight, ShadeLook look) {
		ShadeFrame f = Shade.frame();
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null || f.dimension != ShadeFrame.DIM_OVERWORLD) {
			hasPrevious = false;
			return;
		}
		if (f.fogColor[3] > 0.5f && !f.underwater || f.underwater && !VlConfig.underwater()) {
			hasPrevious = false;
			return;
		}

		long now = System.nanoTime();
		float dt = lastNanos == 0 ? 0f : Math.min((now - lastNanos) / 1e9f, 1f);
		lastNanos = now;
		boolean reset = !hasPrevious || lastLevel != level || !VlConfig.TEMPORAL || f.underwater != lastUnderwater;
		lastUnderwater = f.underwater;
		double dx = f.cameraX - prevX, dy = f.cameraY - prevY, dz = f.cameraZ - prevZ;
		if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > 8) reset = true;
		if (reset) {
			dx = dy = dz = 0;
		}
		prevX = f.cameraX;
		prevY = f.cameraY;
		prevZ = f.cameraZ;
		hasPrevious = true;
		if (lastLevel != level) resetCanopy = true;
		lastLevel = level;

		float vlScale = VlConfig.live("mcopt.shade.vl.scaleLive", VlConfig.ft21() ? VlConfig.SCALE
			: Math.max(VlConfig.SCALE, Math.min(0.5f, 540f / Math.max(texHeight, 1))));
		int vw = Math.max(1, Math.round(texWidth * vlScale)), vh = Math.max(1, Math.round(texHeight * vlScale));
		int lw = Math.clamp(Math.round(width * vlScale), 1, vw), lh = Math.clamp(Math.round(height * vlScale), 1, vh);
		if (vw != allocW || vh != allocH) {
			allocW = vw;
			allocH = vh;
			prevLiveW = lw;
			prevLiveH = lh;
		}
		if (reset) {
			prevLiveW = lw;
			prevLiveH = lh;
		}
		if (!pack(f, look, level, width, height, lw, lh, (float) prevLiveW / vw, (float) prevLiveH / vh, dx, dy, dz, dt, reset)) {
			hasPrevious = false;
			return;
		}

		boolean fold = look != null && VlConfig.FOLD;
		int flags = (VlConfig.CANOPY < 0 ? 1 : 0) | (reset ? 2 : 0) | (look != null ? 4 : 0) | (VlConfig.GPU_TIMER ? 8 : 0) | (fold ? 16 : 0);
		if (MetalBridge.tracing(encoder)) System.out.println("mcopt-metal trace: shade pass 'VL canopy/march/temporal/composite' (" + vw + "x" + vh + ")");
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long err = stack.ncalloc(1, ERR, 1);
			int r = VlNative.render(enc, state, color, depth, MemoryUtil.memAddress0(PARAMS), PARAM_BYTES, vw, vh, lw, lh, flags, err, ERR);
			if (r != 0) throw new IllegalStateException("volumetric light: " + MemoryUtil.memUTF8(err));
		}
		if (fold && !FOLD_SKIP) {
			VlNative.outputs(state, OUTPUTS_OUT);
			ShadePost.setVolumetric(MemoryUtil.memGetAddress(OUTPUTS_OUT), MemoryUtil.memGetAddress(OUTPUTS_OUT + 8), airEndUsed, (float) lw / vw,
				(float) lh / vh, airActive ? Math.max(VlConfig.live("mcopt.shade.vl.airFloor", 0.3f), 1e-3f) : 0f);
		}
		prevLiveW = lw;
		prevLiveH = lh;
		resetCanopy = false;
		frame++;
		if (!logged) {
			logged = true;
			System.out.println("[mcopt] shade: volumetric light " + lw + "x" + lh + " of " + vw + "x" + vh + " (" + VlConfig.describe() + (look != null ? ", HDR" : ", LDR") + ")");
		}
		if (VlConfig.LOG && now - lastLog > 2_000_000_000L) {
			lastLog = now;
			VlNative.canopy(state, CANOPY_OUT);
			System.out.println(String.format(java.util.Locale.ROOT, "[mcopt] shade: vl canopy %.2f (shadowed %.2f) haze %.5f mist %.5f canopy mist %.5f strength %.2f g %.2f humidity %.2f underwater %b sunY %.3f",
				MemoryUtil.memGetFloat(CANOPY_OUT), MemoryUtil.memGetFloat(CANOPY_OUT + 4), PARAMS.getFloat(32), PARAMS.getFloat(36), PARAMS.getFloat(76),
				PARAMS.getFloat(16), PARAMS.getFloat(28), humidity, f.underwater, f.sunDir.y));
			if (VlConfig.GPU_TIMER) {
				VlNative.times(state, TIMES_OUT);
				float m = MemoryUtil.memGetFloat(TIMES_OUT), t = MemoryUtil.memGetFloat(TIMES_OUT + 4), c = MemoryUtil.memGetFloat(TIMES_OUT + 8);
				System.out.println(String.format(java.util.Locale.ROOT, "[mcopt] shade: vl gpu march %.3f temporal %.3f composite %.3f = %.3f ms (%dx%d of %dx%d, %.0f frames)",
					m, t, c, m + t + c, lw, lh, width, height, MemoryUtil.memGetFloat(TIMES_OUT + 12)));
			}
		}
	}

	private static boolean init(long ctx, boolean hdr) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			ByteBuffer source = MemoryUtil.memUTF8(VlConfig.source());
			try {
				long err = stack.ncalloc(1, ERR, 1);
				mcopt.metal.shade.ShadeRobust.maybeFail("vl");
				long t0 = mcopt.metal.shade.ShadeCompile.now();
				long s = VlNative.create(ctx, MemoryUtil.memAddress(source), (VlConfig.LOSSY ? 1 : 0) | (hdr ? 2 : 0), err, ERR);
				mcopt.metal.shade.ShadeCompile.setup("volumetric light", t0);
				if (s == 0) throw new IllegalStateException("volumetric light setup: " + MemoryUtil.memUTF8(err));
				state = s;
				return true;
			} finally {
				MemoryUtil.memFree(source);
			}
		} catch (Throwable t) {
			failed = true;
			System.out.println("[mcopt] shade: volumetric light off: " + t);
			return false;
		}
	}

	private static float smoothstep(float a, float b, float x) {
		float t = Math.max(0f, Math.min(1f, (x - a) / (b - a)));
		return t * t * (3f - 2f * t);
	}

	private static float mix(float a, float b, float t) {
		return a + (b - a) * t;
	}

	private static float humidity(ClientLevel level, BlockPos pos, float dt) {
		Biome b = level.getBiome(pos).value();
		float t = b.getBaseTemperature();
		float target = !b.hasPrecipitation() ? 0.1f : t >= 0.9f ? 0.95f : t >= 0.75f ? 0.75f : t < 0.2f ? 0.45f : 0.6f;
		humidity = humidity < 0f ? target : mix(humidity, target, 1f - (float) Math.exp(-dt / 6f));
		return humidity;
	}

	private static float waterSurface(ClientLevel level, ShadeFrame f) {
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(Math.floor(f.cameraX), Math.floor(f.cameraY), Math.floor(f.cameraZ));
		for (int i = 0; i < 32; i++) {
			FluidState fs = level.getFluidState(p);
			boolean water = fs.getType() == Fluids.WATER || fs.getType() == Fluids.FLOWING_WATER;
			if (!water) return i == 0 ? Float.NaN : p.getY();
			FluidState above = level.getFluidState(p.above());
			boolean waterAbove = above.getType() == Fluids.WATER || above.getType() == Fluids.FLOWING_WATER;
			if (!waterAbove) return p.getY() + fs.getHeight(level, p);
			p.move(0, 1, 0);
		}
		return Float.NaN;
	}

	private static boolean pack(ShadeFrame f, ShadeLook look, ClientLevel level, int width, int height, int vw, int vh, float histU, float histV, double dx,
		double dy, double dz, float dt, boolean reset) {
		ByteBuffer b = PARAMS;
		float sunY = f.sunDir.y;
		boolean sunLight = f.sky.sunLight;
		boolean morning = f.dayTime > 0.5f;
		float rain = f.rain;
		float hum = humidity(level, BlockPos.containing(f.cameraX, f.cameraY, f.cameraZ), dt);
		float golden = sunLight ? 1f - smoothstep(0.05f, 0.55f, sunY) : 0f;
		float day = smoothstep(-0.05f, 0.25f, sunY);

		float mistLow = morning ? 0.0105f : 0.0065f;
		float mist = sunLight ? mix(0.0016f, mistLow, golden) : 0.0045f;
		float haze = 0.00085f * (1f + 0.6f * golden) * (sunLight ? 1f : 1.3f);
		float mistBase = SEA + 2f, mistH = 12f, hazeBase = SEA + 16f, hazeH = 48f;
		if (look != null) {
			haze = look.hazeSigmaLuma(f) * HDR_HAZE * (1f + 0.6f * golden) * (sunLight ? 1f : 1.3f);
			mist = look.mistSigmaBlock() * HDR_MIST;
			hazeBase = ShadeLook.SEA + ShadeLook.HAZE_BASE;
			hazeH = ShadeLook.HAZE_H;
			mistBase = ShadeLook.SEA + ShadeLook.MIST_BASE;
			mistH = look.mistHeight(f);
			mist *= 0.6f + 0.8f * hum;
		} else {
			mist *= 0.6f + 0.8f * hum;
			mist *= 1f + 3f * rain;
			haze *= 1f + 2f * rain;
		}
		float canopyMist = (sunLight ? mix(0.008f, 0.03f, golden) : 0.01f) * (0.7f + 0.6f * hum) * (1f + rain) * VlConfig.mist() * eyeLight(f)
			* VlConfig.live("mcopt.shade.vl.mistGain", 1f);
		float[] amb = f.sky.ambientSky;
		boolean hdr = look != null;
		float ambK = (hdr ? HDR_VEIL : 0.2f) * (f.underwater ? 0f : eyeLight(f));
		float camY = (float) f.cameraY;

		float eye = eyeLight(f);
		float lightUp = smoothstep(0.0f, 0.08f, sunLight ? sunY : f.moonDir.y);
		float strength = VlConfig.strength() * (1f - 0.6f * rain) * (f.underwater ? 1f : eye) * lightUp * (sunLight ? 1f : 2.0f * VlConfig.moon());
		if (hdr) strength *= look.vlFactor() * (f.underwater ? HDR_WATER : HDR_AIR);
		float userWater = f.underwater ? VlConfig.userUnderwater() : 1f;
		strength *= userWater;
		strength *= VlConfig.look("mcopt.shade.vl.gain", 1f, 1f);

		float[] c = f.sky.light;
		float lum = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2];
		float sat = 1f + 0.55f * golden * day;

		boolean air = hdr && VlConfig.FOLD && !f.underwater && VlConfig.look("mcopt.shade.vl.air", 1f, 0f) > 0.5f;
		airActive = air;
		float airEnd = air && f.fogRenderEnd > 16f && f.fogRenderEnd < 1e5f ? Math.min(f.fogRenderEnd, 512f) : VlConfig.dist();
		airEndUsed = airEnd;
		if (air) {
			strength = VlConfig.strength() * (1f - 0.6f * rain) * eye * lightUp * (sunLight ? 1f : VlConfig.moon())
				* VlConfig.live("mcopt.shade.vl.airGain", 1f);
			canopyMist = VlConfig.live("mcopt.shade.vl.airForest", 0.017f) * (1f + VlConfig.live("mcopt.shade.vl.airGolden", 1f) * golden)
				* (0.7f + 0.6f * hum) * (1f + rain) * eye;
		}
		float lr = Math.max(lum + (c[0] - lum) * sat, 0f), lg = Math.max(lum + (c[1] - lum) * sat, 0f), lb = Math.max(lum + (c[2] - lum) * sat, 0f);

		float g = VlConfig.live("mcopt.shade.vl.g", VlConfig.ft21() ? (sunLight ? mix(0.5f, 0.72f, golden) : 0.6f) : (sunLight ? 0.78f : 0.7f));

		float[] l = f.sky.lightDir;
		Vector4f clip = f.viewProj.transform(new Vector4f(l[0], l[1], l[2], 0f));
		float onScreen = 0f, sx = 0f, sy = 0f;
		if (clip.w > 1e-4f && VlConfig.SS_TAPS > 0 && !f.underwater) {
			float nx = clip.x / clip.w, ny = clip.y / clip.w;
			onScreen = 1f - smoothstep(1.05f, 1.9f, Math.max(Math.abs(nx), Math.abs(ny)));
			sx = (nx * 0.5f + 0.5f) * width;
			sy = (ny * 0.5f + 0.5f) * height;
		}
		onScreen *= smoothstep(-0.02f, 0.08f, l[1]) * eye;

		float surface = f.underwater ? waterSurface(level, f) : Float.NaN;
		float surfaceRel = Float.isNaN(surface) ? 4f : (float) (surface - f.cameraY);

		float canopyUp = 1f - (float) Math.exp(-dt / 1.5f), canopyDown = 1f - (float) Math.exp(-dt / 4f);

		float userRays = mcopt.metal.shade.ShadeSettings.godrays();
		strength *= userRays;
		canopyMist *= userRays;

		b.clear();
		put(b, vw, vh, width, height);
		put(b, strength, airEnd, VlConfig.live("mcopt.shade.vl.stepsLive", VlConfig.STEPS), g);
		put(b, haze, mist, mistBase - camY, mistH);
		put(b, hazeBase - camY, hazeH, VlConfig.look("mcopt.shade.vl.iso", 0.12f, 0.35f), 0.05f);
		put(b, lr, lg, lb, canopyMist);
		put(b, VlConfig.SS_TAPS > 0 ? (hdr ? 0.08f + 0.12f * golden : 0.12f + 0.2f * golden) * VlConfig.live("mcopt.shade.vl.crepGain", 1f) * userRays : 0f,
			VlConfig.SS_TAPS > 0 ? VlConfig.live("mcopt.shade.vl.crepTaps", VlConfig.SS_TAPS) : 0f, onScreen, VlConfig.live("mcopt.shade.vl.crepFall", 6f));
		put(b, sx, sy, VlConfig.TEMPORAL ? VlConfig.STILL : 0f, (VlConfig.BOX3 ? 1 : 0) + (VlConfig.HIST_DEPTH ? 2 : 0)
			+ (VlConfig.CREP_SMOOTH ? 4 : 0) + (VlConfig.NEAR_DITHER ? 8 : 0) + (VlConfig.WATER_DITHER ? 16 : 0)
			+ (VlConfig.look("mcopt.shade.vl.marchDither", 1f, 0f) > 0.5f ? 32 : 0)
			+ (VlConfig.look("mcopt.shade.vl.farShadow", 1f, 0f) > 0.5f ? 64 : 0));
		put(b, 0.4f, 0.11f, 0.06f, 0.022f);
		put(b, surfaceRel, f.underwater ? 1f : 0f, 48f, 1f);
		put(b, VlConfig.TEMPORAL ? (VlConfig.WATER_M3 && f.underwater ? 0.8f : 0.9f) : 0f, reset ? 1f : 0f, frame & 1023, VlConfig.WATER_M3 ? f.waterTime : f.gameTimeSeconds);
		put(b, (float) dx, (float) dy, (float) dz, canopyUp);
		put(b, 0.7f * VlConfig.look("mcopt.shade.vl.openGain", 1f, 1f), (hdr ? 2.4f : 1.6f) * VlConfig.look("mcopt.shade.vl.canopyGain", 2.5f, 1f),
			VlConfig.CANOPY, VlConfig.DEBUG);
		put(b, resetCanopy ? 1f : 0f, VlConfig.look("mcopt.shade.vl.mistTopH", 16f, 8f), canopyDown,
			air ? 0f : VlConfig.look("mcopt.shade.vl.nearStepsLive", 2f * VlConfig.NEAR_STEPS, VlConfig.NEAR_STEPS));
		float airExt = Math.max(VlConfig.live("mcopt.shade.vl.airExt", 1f), 0.05f);
		put(b, VlConfig.look("mcopt.shade.vl.mistRange", 24f, 16f), VlConfig.look("mcopt.shade.vl.mistTop", 16f, 4f), VlConfig.extinction() && !air ? 1f : 0f,
			air ? airExt : hdr ? HDR_EXT : 0.5f);
		float sunK = 0.25f * ambK;
		float openCut = hdr ? VlConfig.live("mcopt.shade.vl.openCut", HDR_OPEN_CUT) : 0.5f, carve = hdr ? VlConfig.look("mcopt.shade.vl.carve", HDR_CARVE, HDR_CARVE) : 0f;
		if (air) {
			put(b, 0f, 0f, 0f, openCut);
		} else {
			put(b, amb[0] * ambK + lr * sunK, amb[1] * ambK + lg * sunK, amb[2] * ambK + lb * sunK, openCut);
		}
		put(b, carve, hdr ? HDR_CARVE_WATER : 0f, histU, histV);
		float sunsetArt = mcopt.metal.shade.ShadeSky.sunsetArt ? golden : 0f;
		put(b, VlConfig.skyRays(), (VlConfig.sunsetRays() - 1f) * sunsetArt, VlConfig.sunsetFar() ? 1f : 0f, VlConfig.sunsetFall());
		put(b, VlConfig.live("mcopt.shade.vl.canopyMin", 0f), hdr ? VlConfig.live("mcopt.shade.vl.openCutSide", openCut) : openCut,
			hdr ? VlConfig.live("mcopt.shade.vl.carveSide", carve) : carve,
			hdr && sunLight && !air ? VlConfig.live("mcopt.shade.vl.farDist", 0f) * smoothstep(0.0f, 0.35f, golden) : 0f);
		put(b, VlConfig.live("mcopt.shade.vl.farGain", 1f), VlConfig.live("mcopt.shade.vl.farCut", hdr ? HDR_OPEN_CUT : 0.5f), VlConfig.look("mcopt.shade.vl.soft", 0.8f, 0f),
			VlConfig.live("mcopt.shade.vl.radialStep", 2f));
		put(b, VlConfig.look("mcopt.shade.vl.hpKeep", 0.2f, 1f), VlConfig.look("mcopt.shade.vl.hpGain", 1.8f, 1f),
			VlConfig.live("mcopt.shade.vl.hpRadius", 2f), hdr && VlConfig.FOLD && !air && underTrees() ? VlConfig.look("mcopt.shade.vl.hp", 1f, 0f) : 0f);
		float vm = Math.max(Math.max(Math.abs(clip.x), Math.abs(clip.y)), Math.max(Math.abs(clip.w), 1e-6f));
		put(b, clip.x / vm, clip.y / vm, clip.w / vm, hdr && VlConfig.FOLD && !f.underwater ? VlConfig.live("mcopt.shade.vl.radialTaps", 0f) : 0f);
		put(b, VlConfig.look("mcopt.shade.vl.crepGeo", 0f, 1f), VlConfig.live("mcopt.shade.vl.airGridUp", 16f),
			VlConfig.live("mcopt.shade.vl.airGateLo", 0.6f), VlConfig.live("mcopt.shade.vl.airGateHi", 0.95f));
		put(b, air ? 1f : 0f, VlConfig.live("mcopt.shade.vl.airMin", 8f), VlConfig.live("mcopt.shade.vl.airGrowth", 0.1f),
			VlConfig.live("mcopt.shade.vl.airSteps", VlConfig.STEPS + VlConfig.NEAR_STEPS));
		put(b, airEnd, VlConfig.live("mcopt.shade.vl.airSkyCut", 0f), VlConfig.live("mcopt.shade.vl.airShadow", 1f), VlConfig.live("mcopt.shade.vl.airForestGain", 0.55f));
		float cell = Math.max(VlConfig.live("mcopt.shade.vl.airCell", 2f), 0.5f);
		long cx = (long) Math.floor(f.cameraX / cell) - 32, cy = (long) Math.floor(f.cameraY / cell) - 12, cz = (long) Math.floor(f.cameraZ / cell) - 32;
		put(b, (float) (cx * (double) cell - f.cameraX), (float) (cy * (double) cell - f.cameraY), (float) (cz * (double) cell - f.cameraZ), cell);
		put(b, Math.floorMod(cx, 64), Math.floorMod(cy, 32), Math.floorMod(cz, 64), Math.max(VlConfig.live("mcopt.shade.vl.airGridR", 4f), 0.5f));
		put(b, VlConfig.live("mcopt.shade.vl.airLitLo", 0.3f), VlConfig.live("mcopt.shade.vl.airLitHi", 0.85f), VlConfig.live("mcopt.shade.vl.airNear", 24f),
			VlConfig.live("mcopt.shade.vl.airSky", 0.05f));
		boolean gridAll = reset || cell != lastCell || VlConfig.live("mcopt.shade.vl.airGridAll", 0f) > 0.5f;
		lastCell = cell;
		put(b, VlConfig.live("mcopt.shade.vl.airBase", 0f), VlConfig.live("mcopt.shade.vl.airG", 0.7f), VlConfig.live("mcopt.shade.vl.airIso", 0.05f),
			gridAll ? -1f : (float) Math.floorMod((long) frame - cz, 4L));
		put(b, VlConfig.live("mcopt.shade.vl.airFar0", 0f), VlConfig.live("mcopt.shade.vl.airFar1", 0f), VlConfig.live("mcopt.shade.vl.airOpen", 0.4f),
			VlConfig.live("mcopt.shade.vl.airGridUpR", 0f));
		put(b, VlConfig.live("mcopt.shade.vl.airCols", 1f), VlConfig.live("mcopt.shade.vl.airColTaps", 5f), VlConfig.live("mcopt.shade.vl.airColR", 0f),
			VlConfig.live("mcopt.shade.vl.airSkyCull", 1f));
		put(b, VlConfig.live("mcopt.shade.vl.airColLo", 1.5f), VlConfig.live("mcopt.shade.vl.airColNear", 12f), VlConfig.live("mcopt.shade.vl.airColBox", 0f),
			VlConfig.live("mcopt.shade.vl.crepReproj", 1f));
		if (userRays <= 0f) return false;
		if (userWater <= 0f) return false;
		return strength > 1e-3f || canopyMist > 1e-6f || f.underwater || air && lightUp > 1e-3f;
	}

	private static boolean underTrees() {
		if (state == 0) return false;
		VlNative.canopy(state, CANOPY_OUT);
		return Math.max(MemoryUtil.memGetFloat(CANOPY_OUT), VlConfig.live("mcopt.shade.vl.canopyMin", 0f)) > 0.005f || VlConfig.CANOPY > 0.005f;
	}

	private static float eyeLight(ShadeFrame f) {
		return smoothstep(2.5f / 15f, 9.5f / 15f, f.eyeSkyLight / 15f);
	}

	private static void put(ByteBuffer b, float x, float y, float z, float w) {
		b.putFloat(x).putFloat(y).putFloat(z).putFloat(w);
	}
}
