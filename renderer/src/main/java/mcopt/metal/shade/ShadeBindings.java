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

import java.nio.ByteBuffer;
import mcopt.metal.MetalBridge;
import mcopt.metal.shade.water.ShadeWater;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

public final class ShadeBindings {
	public static final boolean ON = Shade.ENABLED;
	private static final int RING = Math.max(3, Integer.getInteger("mcopt.metal.inFlight", 2) + 1);
	private static final int SLOT_SIZE = 2304, LOOK_OFFSET = 1024;

	private static long ctx, state, compareSampler, dummyShadow, shadowMap, gpuBuffer;
	private static ByteBuffer gpuView;
	private static final long[] buffers = new long[RING];
	private static final ByteBuffer[] views = new ByteBuffer[RING];
	private static int slot;
	private static boolean failed, fallback;

	private ShadeBindings() {
	}

	public static void bind(long ctx, long enc) {
		if (state == 0 && !init(ctx)) return;
		ShadeNative.bind(enc);
		if (ShadeWater.ON) ShadeWater.bind(ctx, enc);
		if (mcopt.metal.shade.light.DynLights.ON) mcopt.metal.shade.light.DynLights.bind(enc);
	}

	static boolean ready() {
		return state != 0;
	}

	static synchronized boolean init(long context) {
		if (state != 0) return true;
		if (failed) return false;
		ShadeCompile.worldStart();
		try {
			ctx = context;
			state = ShadeNative.create(context);
			compareSampler = ShadeNative.compareSampler(state);
			dummyShadow = ShadeNative.dummyShadow(state);
			for (int i = 0; i < RING; i++) {
				buffers[i] = MetalBridge.newBuffer(context, SLOT_SIZE);
				views[i] = MemoryUtil.memByteBuffer(MetalBridge.bufferContents(buffers[i]), SLOT_SIZE);
				ShadeFrameUpdater.FRAME.pack(views[i]);
				ShadeFrameUpdater.LOOK.pack(views[i].slice(LOOK_OFFSET, ShadeLook.SIZE), ShadeFrameUpdater.FRAME);
			}
			gpuBuffer = ShadeNative.buffer(context, 512);
			gpuView = MemoryUtil.memByteBuffer(MetalBridge.bufferContents(gpuBuffer), 512).order(java.nio.ByteOrder.LITTLE_ENDIAN);
			gpuView.putFloat(176, 1f).putFloat(180, 1f).putFloat(184, 1f);
			if (mcopt.metal.shade.light.DynLights.ON) mcopt.metal.shade.light.DynLights.init(context);
			ShadeNative.reopenHook(true);
		} catch (Throwable e) {
			failed = true;
			state = 0;
			System.out.println("[mcopt] shade: setup failed, shading stays off: " + e);
			e.printStackTrace();
			Shade.setFaceShadeOff(false);
			return false;
		}
		try {
			ShadeRobust.maybeFail("lut");
			ByteBuffer source = MemoryUtil.memUTF8(ShadeMsl.lut());
			try (MemoryStack stack = MemoryStack.stackPush()) {
				long err = stack.nmalloc(1, 4096), t0 = ShadeCompile.now();
				int r = ShadeNative.lutInit(state, context, MemoryUtil.memAddress(source), ShadeMsl.LUT_WIDTH, ShadeMsl.LUT_HEIGHT, err, 4096);
				ShadeCompile.setup("sky LUT", t0);
				if (r != 0) throw new IllegalStateException("sky LUT: " + MemoryUtil.memUTF8(err));
			} finally {
				MemoryUtil.memFree(source);
			}
		} catch (Throwable e) {
			fallback = true;
			System.out.println("[mcopt] shade: setup failed, vanilla look from now on: " + e);
			e.printStackTrace();
			for (int i = 0; i < RING; i++) {
				ShadeFrameUpdater.FRAME.dimension = ShadeFrame.DIM_VANILLA;
				ShadeFrameUpdater.FRAME.pack(views[i]);
			}
		}
		publish();
		return true;
	}

	public static boolean fallback() {
		return fallback;
	}

	static ByteBuffer nextSlot() {
		slot = (slot + 1) % RING;
		ShadeNative.resourcesAdvance(RING + 1);
		return views[slot];
	}

	static ByteBuffer currentSlot() {
		return views[slot];
	}

	static void publish() {
		ShadeNative.set(buffers[slot], 0, shadowMap != 0 ? shadowMap : dummyShadow, compareSampler);
		ShadeNative.setM2(ctx, buffers[slot], LOOK_OFFSET, gpuBuffer);
	}

	static void setPrevSceneValid(boolean valid) {
		if (state != 0) currentLook().order(java.nio.ByteOrder.LITTLE_ENDIAN).putFloat(ShadeLook.SSR, valid ? 1f : 0f);
	}

	static void setPrevDepthValid(boolean valid) {
		if (state != 0) currentLook().order(java.nio.ByteOrder.LITTLE_ENDIAN).putFloat(ShadeLook.SSR + 4, valid ? 1f : 0f);
	}

	public static void setShadowTint(boolean casters, float x0, float y0, float x1, float y1) {
		setShadowTint(casters ? 1 : 0, x0, y0, x1, y1);
	}

	public static void setShadowTint(int mode, float x0, float y0, float x1, float y1) {
		if (state == 0) return;
		boolean casters = mode > 0;
		ByteBuffer b = currentLook().order(java.nio.ByteOrder.LITTLE_ENDIAN);
		b.putFloat(ShadeLook.SSR + 12, mode);
		b.putFloat(ShadeLook.TINT_RECT, casters ? x0 : 1f).putFloat(ShadeLook.TINT_RECT + 4, casters ? y0 : 1f);
		b.putFloat(ShadeLook.TINT_RECT + 8, casters ? x1 : -1f).putFloat(ShadeLook.TINT_RECT + 12, casters ? y1 : -1f);
	}

	static ByteBuffer currentLook() {
		return views[slot].slice(LOOK_OFFSET, ShadeLook.SIZE);
	}

	public static float gpuExposure() {
		return gpuView != null ? gpuView.getFloat(176) : 0f;
	}

	public static boolean takeXdrHot() {
		if (gpuView == null || gpuView.getInt(464) == 0) return false;
		gpuView.putInt(464, 0);
		return true;
	}

	public static float gpuMeteredLog2() {
		return gpuView != null ? gpuView.getFloat(192) : 0f;
	}

	static void renderSkyLut(long enc) {
		ShadeNative.lutRender(enc, state);
	}

	public static void rebind(long enc) {
		if (state == 0) return;
		ShadeNative.bind(enc);
		if (ShadeWater.ON) ShadeWater.bind(ctx, enc);
		if (mcopt.metal.shade.light.DynLights.ON) mcopt.metal.shade.light.DynLights.bind(enc);
	}

	public static void setShadowMap(long texture) {
		shadowMap = texture;
		if (state != 0) publish();
	}

	public static long skyLutTexture() {
		return state != 0 ? ShadeNative.lutTexture(state) : 0;
	}

	public static long compareSampler() {
		return compareSampler;
	}

	public static void updateShadow() {
		if (state != 0) ShadeFrameUpdater.FRAME.packShadow(views[slot]);
	}

	static long ctx() {
		return ctx;
	}
}
