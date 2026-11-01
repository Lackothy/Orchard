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
package mcopt.metal.shade.dims;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import mcopt.metal.shade.ShadeFrame;
import mcopt.metal.shade.ShadeLook;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import org.joml.Matrix3f;
import org.joml.Vector3f;

public final class ShadeDims {
	static final float E_REF = 0.86f;
	static final float END_EXPOSURE = 0.76f;
	public static final int FLAG_DIMS_LIT = 1 << 23;
	public static final int FEATURE_DIMS = 1 << 20, FEATURE_MEDIA = 1 << 21, FEATURE_SHIMMER = 1 << 22;

	private static final int[][] NETHER_HEX = {
		{0x3A0F08, 0x7A2A14, 0xFF6A1E},
		{0x3A0610, 0x8A1E2A, 0xFFB060},
		{0x0A2A2E, 0x1E6E6A, 0x5A2A7A},
		{0x0E2A33, 0x2E7A8A, 0x3FE0FF},
		{0x2E2C33, 0x6A6A72, 0xCFCFD6},
	};
	private static final float[] NETHER_DENSITY = {1.0f, 1.15f, 1.05f, 1.2f, 1.3f};
	private static final float[] NETHER_LAVA = {1.0f, 0.8f, 0.45f, 0.35f, 0.7f};

	static final float NETHER_AMBIENT = 0.26f, NETHER_FOG_GAIN = 3.0f, NETHER_LAVA_UP = 0.55f, NETHER_LAVA_Y = 31f, NETHER_SIGMA = 1.2f;
	static final float END_KEY = 0.72f, END_AMBIENT = 0.17f, END_FOG = 0.05f, END_FOG_SIGMA = 0.0045f;
	static final float END_ELEVATION = 55f, END_AZIMUTH = 30f;

	private static final float[] netherW = {1f, 0f, 0f, 0f, 0f};
	private static float cold, aurora, flash;
	private static float auroraGain = 1f;
	private static final Vector3f flashDir = new Vector3f(0, 1, 0);
	private static int sampleTick;
	private static int lastDim = -1;
	private static volatile float vanillaFogEnd = 96f, vanillaFogStart = 10f;
	private static float fogEnd = 96f;
	private static boolean dimsLit;
	private static int dimension;
	private static final float[] endLight = endDirection();

	private ShadeDims() {
	}

	public static float[] endLight() {
		return endLight;
	}

	private static float[] endDirection() {
		double e = Math.toRadians(END_ELEVATION), a = Math.toRadians(END_AZIMUTH);
		return new float[] {(float) (Math.cos(e) * Math.sin(a)), (float) Math.sin(e), (float) (Math.cos(e) * Math.cos(a))};
	}

	public static void vanillaFog(float start, float end) {
		vanillaFogStart = start;
		vanillaFogEnd = end;
	}

	public static boolean paused() {
		return Boolean.getBoolean("mcopt.shade.dims.pause");
	}

	public static boolean endDropsBlob() {
		return endDropsBlob;
	}

	private static volatile boolean endDropsBlob;

	public static boolean netherLean() {
		return !"false".equals(System.getProperty("mcopt.shade.dims.netherLean"));
	}

	private static boolean netherVanillaLight() {
		String v = System.getProperty("mcopt.shade.dims.netherLight");
		return v == null ? mcopt.metal.shade.Shade.TIER == 0 && NETHER_VANILLA_AT_PERFORMANCE : "vanilla".equalsIgnoreCase(v.trim());
	}

	static final boolean NETHER_VANILLA_AT_PERFORMANCE = false;

	public static boolean on() {
		return DimsConfig.DIMS && !paused();
	}

	static final float NETHER_VIEW = DimsConfig.floatFlag("mcopt.shade.netherView", 0f, 0f, 100f);
	private static volatile float netherCull;

	public static float netherCull(float vanillaEnd) {
		float c = NETHER_VIEW > 0f ? Math.max(vanillaEnd * NETHER_VIEW, 32f) : 0f;
		netherCull = c;
		return c;
	}

	public static boolean ownsFog(int dimension) {
		return on() && (dimension == ShadeFrame.DIM_NETHER || dimension == ShadeFrame.DIM_END);
	}

	public static void update(ShadeFrame f, ClientLevel level, SkyRenderState sky, BlockPos camera, float dt, boolean snap) {
		dimension = f.dimension;
		boolean changed = lastDim != f.dimension;
		lastDim = f.dimension;
		float k = snap || changed ? 1f : 1f - (float) Math.exp(-Math.max(dt, 0f) / 3f);
		dimsLit = on() && (f.dimension == ShadeFrame.DIM_NETHER || f.dimension == ShadeFrame.DIM_END);
		endDropsBlob = dimsLit && f.dimension == ShadeFrame.DIM_END && DimsConfig.endShadows() && !"false".equals(System.getProperty("mcopt.shade.dims.endBlob"));
		auroraGain = DimsConfig.user("mcopt.shade.user.auroraBrightness");
		if (f.dimension == ShadeFrame.DIM_OVERWORLD) {
			overworld(f, level, camera, dt, snap || changed);
			ShadeAurora.decide(aurora * auroraGain);
			return;
		}
		aurora = 0f;
		ShadeAurora.off();
		if (!dimsLit) return;
		fogEnd += (Math.max(vanillaFogEnd, 8f) - fogEnd) * k;
		if (f.fogType == ShadeFrame.FOG_AIR) f.fogColor[3] = 0f;
		f.exposure = f.dimension == ShadeFrame.DIM_END ? E_REF * END_EXPOSURE : E_REF;
		for (int i = 0; i < 8; i++) java.util.Arrays.fill(f.sky.horizon[i], 0f);
		if (f.dimension == ShadeFrame.DIM_NETHER) nether(f, level, camera, k);
		else end(f, sky, k);
	}

	private static void nether(ShadeFrame f, ClientLevel level, BlockPos camera, float k) {
		int b = 0;
		if (level != null) {
			Holder<Biome> h = level.getBiomeManager().getBiome(camera);
			b = h.is(Biomes.CRIMSON_FOREST) ? 1 : h.is(Biomes.WARPED_FOREST) ? 2 : h.is(Biomes.SOUL_SAND_VALLEY) ? 3 : h.is(Biomes.BASALT_DELTAS) ? 4 : 0;
		}
		for (int i = 0; i < 5; i++) netherW[i] += ((i == b ? 1f : 0f) - netherW[i]) * k;
		float[] fog = new float[3], amb = new float[3], acc = new float[3];
		float density = 0f, lava = 0f, fogLuma = 0f;
		for (int i = 0; i < 5; i++) {
			float w = netherW[i];
			if (w < 1e-4f) continue;
			addChroma(fog, NETHER_HEX[i][0], w);
			fogLuma += luma(srgbLin(NETHER_HEX[i][0])) * w;
			addChroma(amb, NETHER_HEX[i][1], w);
			addChroma(acc, NETHER_HEX[i][2], w);
			density += NETHER_DENSITY[i] * w;
			lava += NETHER_LAVA[i] * w;
		}
		float[] warm = {1.0f, 0.9f, 0.8f};
		for (int c = 0; c < 3; c++) amb[c] = 0.5f * amb[c] + 0.5f * warm[c];
		setLuma(amb, NETHER_AMBIENT * DimsConfig.user("mcopt.shade.user.netherLight") / E_REF);
		float[] up = amb.clone(), down = amb.clone(), side = amb.clone();
		scale(up, 0.85f);
		scale(down, 1.0f);
		copy3(up, f.sky.ambientSky);
		copy3(down, f.sky.ambientGround);
		copy3(side, f.sky.ambientToward);
		copy3(side, f.sky.ambientAway);
		java.util.Arrays.fill(f.sky.light, 0f);
		f.sky.sunLight = false;
		float glow = NETHER_FOG_GAIN * DimsConfig.user("mcopt.shade.user.netherFogGlow");
		setLuma(fog, fogLuma * glow / E_REF);
		copy3(fog, f.sky.horizon[0]);
		float[] lavaC = {1.0f, 0.42f, 0.12f};
		setLuma(lavaC, NETHER_LAVA_UP * DimsConfig.user("mcopt.shade.user.lavaGlow") / E_REF * lava);
		copy3(lavaC, f.sky.horizon[1]);
		setLuma(acc, fogLuma * glow / E_REF);
		copy3(acc, f.sky.horizon[2]);
		fogDensity = NETHER_SIGMA / Math.max(fogEnd, 16f) * density * DimsConfig.user("mcopt.shade.user.netherFog");
	}

	private static float fogDensity;
	private static boolean bossFog;

	private static void end(ShadeFrame f, SkyRenderState sky, float k) {
		float[] key = srgbLin(0xC7B8FF);
		for (int c = 0; c < 3; c++) key[c] = 0.6f * key[c] / Math.max(luma(key), 1e-6f) + 0.4f;
		float light = DimsConfig.user("mcopt.shade.user.endLight");
		setLuma(key, END_KEY * light / E_REF);
		copy3(key, f.sky.light);
		f.sky.sunLight = true;
		System.arraycopy(endLight, 0, f.sky.lightDir, 0, 3);
		float[] amb = srgbLin(0x5A4A9A);
		setLuma(amb, END_AMBIENT * light / E_REF);
		copy3(amb, f.sky.ambientSky);
		float[] ground = amb.clone();
		scale(ground, 0.55f);
		copy3(ground, f.sky.ambientGround);
		float[] side = amb.clone();
		scale(side, 0.8f);
		copy3(side, f.sky.ambientToward);
		copy3(side, f.sky.ambientAway);
		float[] fog = srgbLin(0x1A1030);
		setLuma(fog, END_FOG / E_REF);
		copy3(fog, f.sky.horizon[0]);
		float skyGain = DimsConfig.user("mcopt.shade.user.endSky");
		putSky(f.sky.horizon[2], 0x07050F, skyGain);
		putSky(f.sky.horizon[3], 0x1C1230, skyGain);
		putSky(f.sky.horizon[4], 0x9A6AFF, skyGain);
		putSky(f.sky.horizon[5], 0x6AE0FF, skyGain);
		putSky(f.sky.horizon[6], 0x6A3FA0, 0.7f * skyGain);
		putSky(f.sky.horizon[7], 0x2F7F8F, 0.6f * skyGain);
		bossFog = net.minecraft.client.Minecraft.getInstance().gui.hud.getBossOverlay().shouldCreateWorldFog();
		fogDensity = END_FOG_SIGMA * DimsConfig.user("mcopt.shade.user.endFog") * (bossFog ? 2.5f : 1f);
		float target = sky.endFlashIntensity;
		flash += (target - flash) * Math.min(1f, k * 6f);
		if (target > 1e-4f) {
			Matrix3f m = new Matrix3f().rotateY((float) Math.toRadians(180f - sky.endFlashYAngle)).rotateX((float) Math.toRadians(-90f - sky.endFlashXAngle));
			m.transform(new Vector3f(0, 1, 0), flashDir).normalize();
		}
	}

	private static void overworld(ShadeFrame f, ClientLevel level, BlockPos camera, float dt, boolean snap) {
		if (DimsConfig.aurora() == 0 || level == null || Boolean.getBoolean("mcopt.shade.aurora.pause")) {
			aurora = 0f;
			return;
		}
		if ((sampleTick++ & 7) == 0 || snap) {
			Holder<Biome> h = level.getBiomeManager().getBiome(camera);
			float t = h.value().getBaseTemperature();
			float target = t < 0.05f ? 1f : t < 0.3f ? 0.6f : 0f;
			cold = snap ? target : cold + (target - cold) * (1f - (float) Math.exp(-8f * Math.max(dt, 0f) / 6f));
		}
		long day = Math.floorDiv(level.getDefaultClockTime() + 6000L, 24000L);
		float chance = mcHash(day) < 0.35f ? 1f : 0f;
		float want = switch (DimsConfig.aurora()) {
			case 2 -> 1f;
			case 3 -> cold * chance;
			default -> Math.max(cold, 0.5f * chance);
		};
		float night = 1f - smooth(-0.22f, -0.06f, f.sunDir.y);
		want *= night * (1f - f.rain);
		float k = snap ? 1f : 1f - (float) Math.exp(-Math.max(dt, 0f) / 4f);
		aurora += (want - aurora) * k;
		if (aurora > 1e-3f) {
			float l = 0.2126f * f.sky.ambientSky[0] + 0.7152f * f.sky.ambientSky[1] + 0.0722f * f.sky.ambientSky[2];
			float a = aurora * auroraGain * Math.max(l, 1e-5f) * 0.9f;
			f.sky.ambientSky[0] += a * 0.25f;
			f.sky.ambientSky[1] += a * 1.0f;
			f.sky.ambientSky[2] += a * 0.55f;
		}
	}

	public static void pack(ByteBuffer look, ByteBuffer frame, ShadeFrame f) {
		ByteBuffer L = look.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		ByteBuffer F = frame.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		int features = L.getInt(ShadeLook.TIER + 4);
		boolean on = on();
		if (on) features |= FEATURE_DIMS;
		if (DimsConfig.media()) features |= FEATURE_MEDIA;
		if (on && DimsConfig.netherShimmer()) features |= FEATURE_SHIMMER;
		L.putInt(ShadeLook.TIER + 4, features);
		L.putFloat(ShadeLook.NIGHT + 8, aurora * auroraGain);
		int dimBits = (!"false".equals(System.getProperty("mcopt.shade.dims.motes")) ? 1 : 0)
			| (ShadeAurora.active() ? 2 : 0) | (DimsDebug.dhClear() ? 4 : 0);
		if (dimsLit) {
			boolean nether = dimension == ShadeFrame.DIM_NETHER;
			if (!(nether && netherVanillaLight())) {
				int bits = (int) F.getFloat(ShadeFrame.SUN_PATH + 8) | FLAG_DIMS_LIT;
				F.putFloat(ShadeFrame.SUN_PATH + 8, bits);
			}
			float border = f.fogRenderStart < f.fogRenderEnd && f.fogRenderEnd < 1e5f ? f.fogRenderEnd : 0f;
			if (nether && netherCull > 0f) border = border > 0f ? Math.min(border, netherCull * 0.97f) : netherCull * 0.97f;
			put4(L, ShadeLook.DIM_TINT, fogDensity, NETHER_LAVA_Y, border, (nether ? 1 : bossFog ? 3 : 2) + 4 * dimBits);
			put4(L, ShadeLook.FLASH, flashDir.x, flashDir.y, flashDir.z, nether ? 0f : flash);
			L.putFloat(ShadeLook.EXPOSURE_REF + 4, mcopt.metal.shade.ShadeConfig.AUTO_EXPOSURE ? 0.3f : 0f);
			L.putFloat(ShadeLook.GRADE1 + 12, 0f);
			L.putFloat(ShadeLook.BLOOM, L.getFloat(ShadeLook.BLOOM) + (nether ? 0.03f : 0.02f));
		} else {
			put4(L, ShadeLook.DIM_TINT, 0f, 0f, 0f, 4 * dimBits);
		}
	}

	public static float aurora() {
		return aurora * auroraGain;
	}

	private static float mcHash(long day) {
		long x = day * 0x9E3779B97F4A7C15L + 0x632BE59BD9B4E019L;
		x ^= x >>> 31;
		x *= 0xBF58476D1CE4E5B9L;
		x ^= x >>> 29;
		return (x >>> 40) / (float) (1L << 24);
	}

	private static float[] srgbLin(int hex) {
		float[] c = {((hex >> 16) & 255) / 255f, ((hex >> 8) & 255) / 255f, (hex & 255) / 255f};
		for (int i = 0; i < 3; i++) c[i] = c[i] <= 0.04045f ? c[i] / 12.92f : (float) Math.pow((c[i] + 0.055f) / 1.055f, 2.4f);
		return c;
	}

	private static void addChroma(float[] acc, int hex, float w) {
		float[] c = srgbLin(hex);
		float l = Math.max(luma(c), 1e-6f);
		for (int i = 0; i < 3; i++) acc[i] += c[i] / l * w;
	}

	private static void putSky(float[] out, int hex, float gain) {
		float r = ((hex >> 16) & 255) / 255f, g = ((hex >> 8) & 255) / 255f, b = (hex & 255) / 255f;
		float[] h = mcopt.metal.shade.ShadeFrameUpdater.look().ldrToHdr(r, g, b);
		for (int i = 0; i < 3; i++) out[i] = h[i] * gain / E_REF;
	}

	private static float luma(float[] c) {
		return 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2];
	}

	private static void setLuma(float[] c, float y) {
		float l = luma(c);
		if (l <= 0f) return;
		for (int i = 0; i < 3; i++) c[i] *= y / l;
	}

	private static void scale(float[] c, float s) {
		for (int i = 0; i < 3; i++) c[i] *= s;
	}

	private static void copy3(float[] src, float[] dst) {
		System.arraycopy(src, 0, dst, 0, 3);
	}

	private static float smooth(float e0, float e1, float x) {
		float t = Math.min(Math.max((x - e0) / (e1 - e0), 0f), 1f);
		return t * t * (3f - 2f * t);
	}

	private static void put4(ByteBuffer b, int at, float x, float y, float z, float w) {
		b.putFloat(at, x).putFloat(at + 4, y).putFloat(at + 8, z).putFloat(at + 12, w);
	}
}
