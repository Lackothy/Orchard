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
package mcopt.metal.shade.clouds;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import mcopt.metal.shade.ShadeFrame;
import mcopt.metal.shade.ShadePost;
import mcopt.metal.shade.ShadeRender;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.ARGB;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

public final class ShadeClouds {
	public static final boolean ON = CloudConfig.ENABLED;
	public static final int RESOURCE_SHADOW = 12, RESOURCE_DOME = 13, RESOURCE_SCREEN = 14;
	private static final int ERR = 4096;

	private static long state;
	private static boolean failed, active, wasActive;
	private static final CloudWeather WEATHER = new CloudWeather();
	private static final CloudParams PARAMS = new CloudParams();
	private static final ByteBuffer PACKED = MemoryUtil.memAlloc(CloudParams.SIZE).order(ByteOrder.nativeOrder());
	private static final long OUT = MemoryUtil.nmemCalloc(3, 8);
	private static final long TIMES = MemoryUtil.nmemCalloc(5, 4);
	private static long lastNanos, lastLog;
	private static int frame, domeFrames;
	private static double lastX, lastY, lastZ;
	private static int lastDimension = -1;
	private static int lastMode = -1;
	private static long lastTicks = Long.MIN_VALUE;
	public static final int HOOK_ORDER = 80;
	private static boolean hooked, over;
	private static final boolean DH = mcopt.metal.DhCompat.loaded() && CloudConfig.flag("mcopt.shade.clouds.withDh", true);

	private ShadeClouds() {
	}

	private static final String DH_API = "com.seibel.distanthorizons.api.";
	private static boolean dhCloudsOff, dhApiFailed;

	private static void dhClouds(boolean ours) {
		if (!DH || dhApiFailed || ours == dhCloudsOff) return;
		try {
			ClassLoader cl = ShadeClouds.class.getClassLoader();
			Object cfg = Class.forName(DH_API + "DhApi$Delayed", true, cl).getField("configs").get(null);
			if (cfg == null) return;
			Object g = Class.forName(DH_API + "interfaces.config.IDhApiConfig", true, cl).getMethod("graphics").invoke(cfg);
			Object gr = Class.forName(DH_API + "interfaces.config.client.IDhApiGraphicsConfig", true, cl).getMethod("genericRendering").invoke(g);
			Object v = Class.forName(DH_API + "interfaces.config.client.IDhApiGenericRenderingConfig", true, cl).getMethod("cloudRenderingEnabled").invoke(gr);
			Class<?> value = Class.forName(DH_API + "interfaces.config.IDhApiConfigValue", true, cl);
			Object r = ours ? value.getMethod("setValue", Object.class).invoke(v, Boolean.FALSE) : value.getMethod("clearValue").invoke(v);
			if (Boolean.FALSE.equals(r)) throw new IllegalStateException("DH refused the API value");
			dhCloudsOff = ours;
			System.out.println("[mcopt] shade clouds: Distant Horizons' own clouds " + (ours ? "off (ours draw)" : "back to its setting"));
		} catch (Throwable t) {
			dhApiFailed = true;
			System.out.println("[mcopt] shade clouds: could not switch Distant Horizons' clouds (" + t + "); set Clouds off in DH's settings");
		}
	}

	public static boolean replacesVanilla() {
		return ON && (active || userOff);
	}

	private static boolean userOff;
	private static int lastStyle = -1;
	private static int lastChecker = -1;
	private static float lastBase = Float.NaN, lastThickness = -1f;
	private static boolean lastDome, lastShadows;

	private static boolean vanillaMask, vanillaTried;
	private static int vanillaW = 256, vanillaH = 256;
	private static double lastVanillaX = Double.NaN;

	private static boolean loadVanillaMask(long ctx) {
		vanillaTried = true;
		Minecraft mc = Minecraft.getInstance();
		try (java.io.InputStream in = mc.getResourceManager().open(net.minecraft.resources.Identifier.withDefaultNamespace("textures/environment/clouds.png"));
			com.mojang.blaze3d.platform.NativeImage img = com.mojang.blaze3d.platform.NativeImage.read(in)) {
			int w = img.getWidth(), h = img.getHeight();
			ByteBuffer mask = MemoryUtil.memAlloc(w * h);
			try {
				for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) mask.put(y * w + x, (byte) (ARGB.alpha(img.getPixel(x, y)) >= 10 ? 255 : 0));
				if (CloudNative.vanilla(state, ctx, MemoryUtil.memAddress(mask), w, h) == 0) return false;
			} finally {
				MemoryUtil.memFree(mask);
			}
			vanillaW = w;
			vanillaH = h;
			System.out.println("[mcopt] shade clouds: blocky style with the game's cloud texture (" + w + "x" + h + ")");
			return true;
		} catch (Throwable t) {
			System.out.println("[mcopt] shade clouds: no vanilla cloud texture for the blocky style (" + t + "); the weather map's cells instead");
			return false;
		}
	}

	static float leanStyle() {
		return blocky() ? 2f : 0f;
	}

	public static boolean blocky() {
		return mcopt.metal.shade.ShadeSettings.clouds() == mcopt.metal.shade.ShadeSettings.CLOUDS_BLOCKY;
	}

	public static void update(long ctx, long enc, ShadeFrame f, ByteBuffer look) {
		active = false;
		over = false;
		userOff = false;
		if (failed) {
			dhClouds(false);
			PARAMS.packLook(look, false);
			return;
		}
		try {
			Minecraft mc = Minecraft.getInstance();
			ClientLevel level = mc.level;
			var game = mc.gameRenderer.gameRenderState();
			boolean option = game.optionsRenderState.cloudStatus != CloudStatus.OFF || DH;
			int userMode = mcopt.metal.shade.ShadeSettings.clouds();
			userOff = userMode == mcopt.metal.shade.ShadeSettings.CLOUDS_OFF && level != null && f.dimension == ShadeFrame.DIM_OVERWORLD;
			boolean want = level != null && f.dimension == ShadeFrame.DIM_OVERWORLD && option && ARGB.alpha(game.levelRenderState.cloudColor) > 0
				&& !Boolean.getBoolean("mcopt.shade.clouds.pause") && userMode <= mcopt.metal.shade.ShadeSettings.CLOUDS_BLOCKY;
			dhClouds(want || userOff);
			if (!want) {
				wasActive = false;
				PARAMS.packLook(look, false);
				return;
			}
			if (state == 0 && !init(ctx)) {
				PARAMS.packLook(look, false);
				return;
			}
			long now = System.nanoTime();
			float dt = lastNanos == 0 ? 0f : Math.min((now - lastNanos) / 1e9f, 0.25f);
			boolean longGap = lastNanos != 0 && now - lastNanos > 1_000_000_000L;
			lastNanos = now;
			double dx = f.cameraX - lastX, dy = f.cameraY - lastY, dz = f.cameraZ - lastZ;
			boolean teleport = dx * dx + dy * dy + dz * dz > 64.0 * 64.0;
			boolean snap = !wasActive || longGap || teleport || lastDimension != f.dimension;
			long dayTicks = level.getDefaultClockTime();
			if (lastTicks != Long.MIN_VALUE && Math.abs(dayTicks - lastTicks) > 200 && !"false".equals(System.getProperty("mcopt.shade.clouds.timeSnap"))) snap = true;
			lastTicks = dayTicks;
			lastX = f.cameraX;
			lastY = f.cameraY;
			lastZ = f.cameraZ;
			lastDimension = f.dimension;

			int dev = CloudConfig.devBits();
			int mode = CloudConfig.modeBits();
			boolean modeChanged = lastMode < 0 || ((mode ^ lastMode) & CloudConfig.MODE_STABLE) != 0;
			lastMode = mode;
			int style = blocky() ? 1 : 0;
			if (style != lastStyle) modeChanged = true;
			lastStyle = style;
			var window = mc.getWindow();
			int[] outSize = mcopt.metal.shade.ShadeOutput.current(window.getWidth(), window.getHeight());
			int ow = outSize[0], oh = outSize[1];
			float scale = (dev & CloudConfig.DEV_SCALE_ALT) != 0 ? CloudConfig.SCALE_ALT : CloudConfig.scale();
			int hw = Math.max(16, Math.round(ow * scale)), hh = Math.max(16, Math.round(oh * scale));
			int checker = (dev & CloudConfig.DEV_CHECKER_ALT) != 0 ? CloudConfig.CHECKER_ALT : CloudConfig.checker();
			if (checker != lastChecker) modeChanged = true;
			lastChecker = checker;
			hw = (hw + checker - 1) / checker * checker;
			hh = (hh + checker - 1) / checker * checker;
			int mw = hw / checker, mh = hh / checker;
			boolean resized = CloudNative.size(state, ctx, hw, hh, mw, mh, CloudConfig.DOME_W, CloudConfig.DOME_H) != 0;

			long ticks = level.getDefaultClockTime();
			double days = (ticks + f.partialTick) / 24000.0;
			WEATHER.update(days, f.rain, f.thunder, dt, snap, CloudConfig.SEED);
			float move = Boolean.getBoolean("mcopt.shade.clouds.freeze") ? 0f : dt;
			float sx = WEATHER.windX * WEATHER.windSpeed * move, sz = WEATHER.windZ * WEATHER.windSpeed * move;
			CloudParams p = PARAMS;
			p.windX += sx;
			p.windZ += sz;
			p.wind3X += sx * CloudConfig.NOISE_DRIFT;
			p.wind3Z += sz * CloudConfig.NOISE_DRIFT;
			p.cirrusX += sx * 1.7;
			p.cirrusZ += sz * 1.7;
			p.windX %= CloudParams.WEATHER_PERIOD * 64;
			p.windZ %= CloudParams.WEATHER_PERIOD * 64;
			p.wind3X %= CloudParams.NOISE_PERIOD * 64;
			p.wind3Z %= CloudParams.NOISE_PERIOD * 64;
			p.cirrusX %= CloudParams.WEATHER_PERIOD * 64;
			p.cirrusZ %= CloudParams.WEATHER_PERIOD * 64;
			p.windStepX = sx;
			p.windStepZ = sz;
			float vanillaHeight = game.levelRenderState.cloudHeight;
			if (blocky() && !vanillaTried) vanillaMask = loadVanillaMask(ctx);
			boolean cells = blocky() && vanillaMask;
			p.vanillaCells = cells;
			if (cells) {
				double drift = ((level.getGameTime() % (vanillaW * 400L)) + f.partialTick) * 0.03;
				double step = Double.isNaN(lastVanillaX) ? 0.0 : drift - lastVanillaX;
				lastVanillaX = drift;
				p.vanillaX = drift;
				p.vanillaStepX = Math.abs(step) < 4.0 ? (float) step : 0f;
				p.vanillaPeriodX = vanillaW * 12.0;
				p.vanillaPeriodZ = vanillaH * 12.0;
			} else {
				lastVanillaX = Double.NaN;
			}
			float baseSet = CloudConfig.base();
			float thickness = blocky() ? CloudConfig.blockThickness(cells) : CloudConfig.thickness();
			if (Float.compare(baseSet, lastBase) != 0 || thickness != lastThickness) modeChanged = true;
			lastBase = baseSet;
			lastThickness = thickness;
			p.base = Float.isNaN(baseSet) ? (Float.isFinite(vanillaHeight) && vanillaHeight > 0f ? vanillaHeight + (cells ? 0f : 8f) : 200f) : baseSet;
			p.top = p.base + thickness;
			p.histW = hw;
			p.histH = hh;
			p.marchW = mw;
			p.marchH = mh;
			p.checker = checker;
			p.frame = frame++;
			boolean domeOn = CloudConfig.dome();
			if (domeOn && !lastDome) modeChanged = true;
			lastDome = domeOn;
			p.reset = snap || resized || modeChanged;
			if (p.reset) domeFrames = 0;
			p.domeValid = domeOn && domeFrames > 0;
			p.domeUpdates = domeFrames;
			p.opacity = CloudConfig.userOpacity();
			p.dev = dev;
			p.mode = mode;
			int domeEvery = (dev & CloudConfig.DEV_OLD_DOME) != 0 ? 4 : CloudConfig.DOME_EVERY;
			boolean dome = (p.frame & (domeEvery - 1)) == 1;
			boolean shadows = CloudConfig.shadows();
			p.shadowUpdate = shadows && ((p.frame & 3) == 3 || p.reset || !lastShadows);
			lastShadows = shadows;
			p.pack(PACKED, f, WEATHER);
			int flags = 2 | (p.shadowUpdate ? 1 : 0) | (domeOn && (dome || p.reset) ? 4 : 0)
				| (CloudConfig.GPU_TIMER ? 8 : 0) | ((mode & CloudConfig.MODE_STABLE) != 0 ? 16 : 0) | (cells ? 32 : 0)
				| (mcopt.metal.shade.Live.b("mcopt.shade.cloudLean", true) ? 64 : 0);
			if (!p.reset) {
				if ((dev & CloudConfig.DEV_NO_SCREEN) != 0) flags &= ~2;
				if ((dev & CloudConfig.DEV_NO_MAPS) != 0) flags &= ~(1 | 4);
			}
			CloudNative.render(enc, state, MemoryUtil.memAddress(PACKED), CloudParams.SIZE, flags);
			if ((flags & 4) != 0) domeFrames++;
			CloudNative.outputs(state, OUT);
			ShadeRender.resource(RESOURCE_SHADOW, MemoryUtil.memGetAddress(OUT));
			ShadeRender.resource(RESOURCE_DOME, MemoryUtil.memGetAddress(OUT + 8));
			ShadeRender.resource(RESOURCE_SCREEN, MemoryUtil.memGetAddress(OUT + 16));
			active = true;
			wasActive = true;
			over = f.cameraY > p.base - 48.0;
			if (!hooked && ShadePost.active()) {
				hooked = true;
				ShadePost.addBeforeTonemap(HOOK_ORDER, ShadeClouds::beforeTonemap);
			}
			PARAMS.packLook(look, true);
			if (CloudConfig.LOG && now - lastLog > 2_000_000_000L) {
				lastLog = now;
				String times = "";
				if (CloudConfig.GPU_TIMER) {
					CloudNative.times(state, TIMES);
					times = String.format(java.util.Locale.ROOT, ", gpu ms shadow %.3f march %.3f resolve %.3f dome %.3f (%d frames)", MemoryUtil.memGetFloat(TIMES),
						MemoryUtil.memGetFloat(TIMES + 4), MemoryUtil.memGetFloat(TIMES + 8), MemoryUtil.memGetFloat(TIMES + 12), (int) MemoryUtil.memGetFloat(TIMES + 16));
				}
				System.out.println(String.format(java.util.Locale.ROOT, "[mcopt] shade clouds: day %.2f, %s, layer %.0f-%.0f, %dx%d (march %dx%d), light %.2f %.2f %.2f %s%s",
					days, WEATHER.describe(), p.base, p.top, hw, hh, mw, mh, p.light[0], p.light[1], p.light[2], p.sunLit ? "sun" : "moon", times));
			}
		} catch (Throwable t) {
			failed = true;
			active = false;
			System.out.println("[mcopt] shade: clouds failed, vanilla clouds from now on: " + t);
			t.printStackTrace();
			PARAMS.packLook(look, false);
		}
	}

	private static void beforeTonemap(ShadePost.Context c) {
		if (!active || !over || failed || state == 0 || c.depth() == 0 || c.scene() == 0) return;
		try {
			CloudNative.over(c.enc(), state, c.scene(), c.depth(), c.width(), c.height(), DH && ShadePost.bridgedThisFrame() ? 1 : 0);
		} catch (Throwable t) {
			System.out.println("[mcopt] shade: clouds over the world failed: " + t);
			over = false;
		}
	}

	private static boolean init(long ctx) {
		ByteBuffer src = MemoryUtil.memUTF8(CloudConfig.source());
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long err = stack.nmalloc(1, ERR);
			MemoryUtil.memPutByte(err, (byte) 0);
			long t0 = mcopt.metal.shade.ShadeCompile.now();
			long s = mcopt.metal.shade.ShadeRobust.failCompile("clouds") ? 0 : CloudNative.create(ctx, MemoryUtil.memAddress(src), err, ERR);
			mcopt.metal.shade.ShadeCompile.setup("clouds", t0);
			if (s == 0) {
				failed = true;
				System.out.println("[mcopt] shade: clouds setup failed, vanilla clouds: " + MemoryUtil.memUTF8(err));
				return false;
			}
			state = s;
			vanillaTried = false;
			if (CloudConfig.LEAN_PASSES) {
				long t1 = mcopt.metal.shade.ShadeCompile.now();
				int ok = CloudNative.lean(s, ctx, CloudConfig.devBits(), CloudConfig.modeBits(), leanStyle());
				mcopt.metal.shade.ShadeCompile.setup("clouds (lean)", t1);
				if (ok == 0) System.out.println("[mcopt] shade: clouds: the specialized passes could not be built (the generic ones run)");
			}
			System.out.println("[mcopt] shade: volumetric clouds on (" + CloudConfig.describe() + ")");
			return true;
		} finally {
			MemoryUtil.memFree(src);
		}
	}
}
