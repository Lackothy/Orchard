// Copyright 2026 mcopt-shade contributors
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
// License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
// law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
// OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
// limitations under the License.
//
// mcopt native shading: the water and reflections module (shader bible E14, E15 basics, E26 surface). Original code; the
// techniques are public (Schlick 1994, Walter et al. 2007 GGX, Beer-Lambert, McGuire & Mara 2014 screen-space ray
// marching, Tessendorf 2001 deep-water dispersion, caustics as the inverse Jacobian of the refracted light field).
//
// Assembled by ShadeMsl after shade_common.metal (mcopt.metal.shade.water.ShadeWater.prelude puts the SHADE_* defines in
// front) for the translucent terrain fragment; written as a self-contained include so other water renderers (far terrain,
// Distant Horizons' transparent pass) can call the same functions.
//
// UNITS AND SPACES: shade_common.metal's. Positions are camera-relative world (blocks), +y up. Colours are linear radiance
// BEFORE exposure (what shadeLit returns); the caller applies air fog and the output transform (M1: shadeFogAir +
// shadeFinish; M2 HDR: pre-exposure and the post tonemap). Depth is reversed-Z in [0, 1] (1 near, 0 = sky/clear).
//
// INPUTS (bound by mcwater.m on every shaded encoder, fragment stage):
//   texture(18) sceneCopy  the main pass's colour attachment 0, copied at the switch to translucent terrain: the opaque
//                          scene (sky, terrain, entities) without the translucents. M1: tone-mapped sRGB in RGBA8 (read
//                          back to radiance by shadeWaterSceneRadiance); M2 HDR: pre-exposed linear radiance (MCOPT_HDR or
//                          SHADE_HDR).
//   texture(19) depthCopy  the depth attachment at the same moment (Depth32Float, reversed-Z).
//   texture(20) waves      (SHADE_WAVE_BAKE) the baked wave field, RGBA16F SHADE_WAVE_BAKE_SIZE^2 mipmapped (slope xz, height, Laplacian)
//   texture(21) coarse     the SSR march's half-size nearest depth (R32F reversed-Z), made after the copy
//   buffer(23)  ShadeWaterParams (below). state.x == 1 only after this frame's copy: otherwise callers must not read the
//                          copies (they are 1x1 dummies or last frame's).
//   prev                   (M2 HDR, optional) last frame's finished HDR world (ShadeResources.prevScene: everything incl.
//                          clouds, translucents, Distant Horizons, pre-exposed with ShadeGpu.exposure.z). Where the caller
//                          gives it (ShadeWaterIn.prevExposure > 0), SSR hits take their colour from it (reprojected with
//                          prevViewProj) and the sky reflection reads the rendered sky on screen in the reflected direction
//                          (clouds, sun disc, stars, far LODs) instead of only the atmosphere LUT.
#ifndef MCOPT_SHADE_WATER
#define MCOPT_SHADE_WATER

#if defined(SHADE_LEAN_ABOVE) && defined(SHADE_WDEV_ON)
#undef SHADE_WDEV_IS
#define SHADE_WDEV_IS(f, k) (shadeWdev(f) == (k))
#endif
#ifndef SHADE_SSR_THICK_K
#define SHADE_SSR_THICK_K 1.0
#endif
#ifndef SHADE_SSR_BEHIND
#define SHADE_SSR_BEHIND 0
#endif
#ifndef SHADE_SSR_STEPS
#define SHADE_SSR_STEPS 16
#endif

struct ShadeWaterParams {
	float4 still;
	float4 flow;
	float4 state;
	float4 tune;
};

constexpr sampler shadeWaterLinear(filter::linear, address::clamp_to_edge, coord::normalized);

static inline int shadeWaterSprite(float2 uv, constant ShadeWaterParams &wp) {
	if (wp.still.z <= 0.0) return -1;
	if (all(uv >= wp.still.xy) && all(uv <= wp.still.zw)) return 1;
	if (all(uv >= wp.flow.xy) && all(uv <= wp.flow.zw)) return 2;
	return 0;
}

static inline float shadeLinearW(float z, constant ShadeFrame &f) {
	return f.proj[3][2] / max(z + f.proj[2][2], 1e-7);
}

#define SHADE_WATER_SKY_W 1e6
static inline float shadeWaterSceneW(depth2d<float> depth, uint2 px, constant ShadeFrame &f) {
	float z = depth.read(px);
	return z > 0.0 ? shadeLinearW(z, f) : SHADE_WATER_SKY_W;
}

static inline float shadeWaterCoarseW(texture2d<float> hz, uint2 px, constant ShadeFrame &f) {
	float z = hz.read(px >> 1).r;
	return z > 0.0 ? shadeLinearW(z, f) : SHADE_WATER_SKY_W;
}

fragment float shade_water_hz_fs(float4 pos [[position]], depth2d<float> d [[texture(0)]]) {
	constexpr sampler g(coord::normalized, filter::nearest, address::clamp_to_edge);
	float2 p = float2(uint2(pos.xy) * 2u);
	float4 z = d.gather(g, (p + 1.0) / float2(d.get_width(), d.get_height()));
	return max(max(z.x, z.y), max(z.z, z.w));
}

static inline float3 shadeWaterDisplayRadiance(half3 stored, float exposure) {
	float3 o = float3(shadeToLinear(stored));
	float mo = max(max(o.r, o.g), max(o.b, 1e-5));
	float tm = min(mo, 0.9985);
	float y = (tm + sqrt(tm * tm + 0.024144 * tm)) * (1.0 / 2.012);
	float m = -log2(max(1.0 - y, 1e-4)) * (0.69314718 / 1.1);
	return o * (m / mo) / max(exposure, 1e-6);
}

static inline float3 shadeWaterSceneRadiance(half3 stored, float exposure) {
#if defined(MCOPT_HDR) || defined(SHADE_HDR)
	return float3(stored) / max(exposure, 1e-6);
#else
	return shadeWaterDisplayRadiance(stored, exposure);
#endif
}

#ifdef SHADE_WATER_TAA
#define SHADE_WATER_NOISE_FRAME(f) ((f).flags2.x)
#else
#define SHADE_WATER_NOISE_FRAME(f) 0
#endif

static inline float2 shadeWaterRenderSize(constant ShadeFrame &f, float2 textureSize) {
#ifdef SHADE_WATER_SUBRECT
	return min(f.screen.xy, textureSize);
#else
	return textureSize;
#endif
}

#define SHADE_WAVE_COUNT 7
constant float4 SHADE_WAVES[SHADE_WAVE_COUNT] = {
	float4( 0.920,  0.392, 8.70, 0.060),
	float4( 0.616,  0.788, 5.90, 0.040),
	float4( 0.993, -0.118, 3.70, 0.024),
	float4( 0.309,  0.951, 2.45, 0.015),
	float4( 0.809, -0.588, 1.55, 0.009),
	float4(-0.182,  0.983, 1.00, 0.0055),
	float4( 0.707,  0.707, 0.62, 0.0032),
};
constant float SHADE_WAVE_PHASE[SHADE_WAVE_COUNT] = {0.0, 1.7, 4.1, 2.3, 5.2, 0.6, 3.4};

static inline float shadeWaterTime(constant ShadeFrame &f) { return f.sunPath.w; }
#define SHADE_WATER_CLOCK_WRAP 4096.0
static inline float shadeWaterOmega(float w) {
	return rint(w * (SHADE_WATER_CLOCK_WRAP / 6.2831853)) * (6.2831853 / SHADE_WATER_CLOCK_WRAP);
}
#define SHADE_WATER_FLOW_SPEED 1.40625

static inline float2 shadeWaterXZ(float2 rel, constant ShadeFrame &f) {
	return rel + f.cameraFract.xz + fmod(f.cameraBlock.xz, 2048.0);
}

#define SHADE_WAVE_TILE 64.0
constant int2 SHADE_WAVE_LATTICE[SHADE_WAVE_COUNT] = {
	int2(7, 3), int2(7, 9), int2(17, -2), int2(8, 25), int2(33, -24), int2(-12, 63), int2(73, 73),
};
constant int2 SHADE_WAVE_CREST_LATTICE[3] = { int2(-1, 1), int2(-2, 1), int2(0, 4) };
#define SHADE_WAVE_EXTRA 7
constant int3 SHADE_WAVE_EXTRA_LATTICE[SHADE_WAVE_EXTRA] = {
	int3(5, -1, 330), int3(-3, 8, 260), int3(10, -10, 180), int3(4, 13, 150), int3(19, 11, 110), int3(-20, 25, 75), int3(45, 20, 50),
};

static float2 shadeWavesTiled(float2 x, float t, float speed, float phase, thread float &lap, thread float &height, bool extras = true) {
	float2 g = 0.0;
	lap = 0.0;
	height = 0.0;
	const float base = 6.2831853 / SHADE_WAVE_TILE;
#ifdef SHADE_WATER_SMOOTH
	float2 warp = float2(sin(dot(float2(2.0, 1.0) * base, x) + t * shadeWaterOmega(0.05 * speed) + phase),
	                     sin(dot(float2(-1.0, 3.0) * base, x) - t * shadeWaterOmega(0.04 * speed) + 1.3 + phase)) * 1.7
	             + float2(sin(dot(float2(5.0, -3.0) * base, x) + t * shadeWaterOmega(0.09 * speed) + 2.1),
	                      sin(dot(float2(4.0, 6.0) * base, x) - t * shadeWaterOmega(0.07 * speed) + 0.4)) * 0.6;
	x += warp;
#endif
	for (int i = 0; i < SHADE_WAVE_COUNT; i++) {
		float2 kv = float2(SHADE_WAVE_LATTICE[i]) * base;
		float k = length(kv);
		float2 dir = kv / k;
		float a = SHADE_WAVES[i].w * (i == 0 ? 0.75 : i == 1 ? 0.8 : 1.0);
		if (i < 3) a *= 0.55 + 0.45 * sin(dot(float2(SHADE_WAVE_CREST_LATTICE[i]) * base, x) + SHADE_WAVE_PHASE[i] * 2.3 + phase + t * shadeWaterOmega(0.13 * speed));
		float omega = shadeWaterOmega(sqrt(9.81 * k) * 0.62 * speed);
		float th = dot(kv, x) + omega * t + SHADE_WAVE_PHASE[i] + phase;
		float c;
		float s = sincos(th, c);
		float e = exp(s - 1.0);
		g += dir * (a * k * c * e);
		lap += a * k * k * (c * c - s) * e;
		height += a * e;
		x -= dir * (c * e * a * 1.6);
	}
	for (int i = 0; i < (extras ? SHADE_WAVE_EXTRA : 0); i++) {
		int3 l = SHADE_WAVE_EXTRA_LATTICE[i];
		float2 kv = float2(l.xy) * base;
		float k = length(kv);
		float a = float(l.z) * 1e-4;
		float th = dot(kv, x) + shadeWaterOmega(sqrt(9.81 * k) * 0.62 * speed) * t + float(i) * 2.17 + phase;
		float c;
		float s = sincos(th, c);
		float e = exp(s - 1.0);
		g += kv * (a * c * e);
		lap += a * k * k * (c * c - s) * e;
		height += a * e;
	}
	return g;
}

#ifndef SHADE_WAVE_BAKE_SIZE
#define SHADE_WAVE_BAKE_SIZE 1024
#endif
fragment half4 shade_water_bake_fs(float4 pos [[position]], constant ShadeFrame &f [[buffer(16)]]) {
	float2 x = pos.xy * (SHADE_WAVE_TILE / float(SHADE_WAVE_BAKE_SIZE));
	float lap, height;
	float2 g = shadeWavesTiled(x, shadeWaterTime(f), 1.0, 0.0, lap, height);
	return half4(half2(g), half(height), half(lap));
}
#define SHADE_WATER_DETAIL_SPEED 1.82
#ifndef SHADE_WATER_DETAIL_SIZE
#define SHADE_WATER_DETAIL_SIZE 256
#endif
fragment half4 shade_water_bake_detail_fs(float4 pos [[position]], constant ShadeFrame &f [[buffer(16)]]) {
	float2 x = pos.xy * (SHADE_WAVE_TILE / float(SHADE_WATER_DETAIL_SIZE));
	float lap, height;
#ifdef SHADE_WATER_DETAIL_EXTRAS
	float2 g = shadeWavesTiled(x, shadeWaterTime(f), SHADE_WATER_DETAIL_SPEED, 2.4, lap, height, true);
#else
	float2 g = shadeWavesTiled(x, shadeWaterTime(f), SHADE_WATER_DETAIL_SPEED, 2.4, lap, height, false);
#endif
	return half4(half2(g), half(height), half(lap));
}
constexpr sampler shadeWaveSampler(filter::linear, mip_filter::linear, address::repeat, max_anisotropy(8));

static float2 shadeWaves(float2 x, float t, float strength, float footprint, thread float &lap, thread float &height) {
	float2 g = 0.0;
	lap = 0.0;
	height = 0.0;
	for (int i = 0; i < SHADE_WAVE_COUNT; i++) {
		float4 w = SHADE_WAVES[i];
		float lod = saturate(w.z / max(footprint * 3.0, 1e-4) - 1.0);
		float k = 6.2831853 / w.z;
		float a = w.w * strength * lod;
		if (a * k < 2e-4) continue;
		if (i < 3) a *= 0.55 + 0.45 * sin(k * 0.21 * dot(float2(-w.y, w.x), x) + SHADE_WAVE_PHASE[i] * 2.3 + t * shadeWaterOmega(0.13));
		float omega = shadeWaterOmega(sqrt(9.81 * k) * 0.62);
		float th = k * dot(w.xy, x) + omega * t + SHADE_WAVE_PHASE[i];
		float c;
		float s = sincos(th, c);
		float e = exp(s - 1.0);
		float dh = a * k * c * e;
		g += w.xy * dh;
		lap += a * k * k * (c * c - s) * e;
		height += a * e;
		x -= w.xy * (c * e * a * 1.6);
	}
	return g;
}

static inline bool shadeSsrSegment(float3 p0, float3 r, float maxDist, constant ShadeFrame &f, thread float3 &s0, thread float3 &ds,
                                   thread bool &screenEnd) {
	float4 c0 = f.viewProj * float4(p0, 1.0);
	float4 dc = f.viewProj * float4(r, 0.0);
	float len = maxDist;
	if (dc.w < 0.0) len = min(len, (c0.w - 0.1) / -dc.w * 0.95);
	if (len <= 0.05 || c0.w <= 0.05) return false;
	float4 c1 = c0 + dc * len;
	s0 = c0.xyz / c0.w;
	float3 s1 = c1.xyz / c1.w;
	float2 d = s1.xy - s0.xy;
#ifdef SHADE_SSR_EDGE
	const float side = 2.0;
#else
	const float side = 1.0;
#endif
	float tx = d.x > 1e-6 ? (side - s0.x) / d.x : d.x < -1e-6 ? (-side - s0.x) / d.x : 1e6;
	float ty = d.y > 1e-6 ? (1.0 - s0.y) / d.y : d.y < -1e-6 ? (-1.0 - s0.y) / d.y : 1e6;
	float tEnd = min(1.0, min(tx, ty));
	if (tEnd <= 0.0) return false;
	screenEnd = tEnd < 1.0;
	ds = (s1 - s0) * tEnd;
	return true;
}

#ifdef SHADE_SSR_QUAD
#define SHADE_SSR_N (SHADE_SSR_STEPS / 4 > 2 ? SHADE_SSR_STEPS / 4 : 2)
#else
#define SHADE_SSR_N SHADE_SSR_STEPS
#endif
#if defined(SHADE_SSR_RAWZ) && !defined(SHADE_SSR_NORAWZ) && defined(SHADE_LEAN_ABOVE)
static inline float shadeSsrZ(depth2d<float> depth, float3 s, float2 size) {
	return depth.read(uint2(clamp((s.xy * 0.5 + 0.5) * size, float2(0.0), size - 1.0)));
}
static inline float shadeSsrT(float u) { return u * (0.35 + 0.65 * u); }
static float4 shadeSsrMarch(float3 s0, float3 ds, bool screenEnd, int chunk, int chunks, float jitter, constant ShadeFrame &f,
                            depth2d<float> depth, texture2d<float> hz) {
	float2 size = shadeWaterRenderSize(f, float2(depth.get_width(), depth.get_height()));
	float A = f.proj[2][2], B = f.proj[3][2];
	const int n = SHADE_SSR_N;
	float inv = 1.0 / float(n * chunks);
	float base = float(chunk * n) - jitter;
	float zs[SHADE_SSR_N + 1];
	for (int i = 1; i <= n; i++) {
		float3 s = s0 + ds * shadeSsrT((base + float(i)) * inv);
		zs[i] = hz.read(uint2(clamp((s.xy * 0.5 + 0.5) * size, float2(0.0), size - 1.0)) >> 1).r;
	}
	float thickK = SHADE_SSR_THICK_K;
	bool behind = SHADE_SSR_BEHIND != 0;
	if (SHADE_WDEV_IS(f, SHADE_WDEV_SSR_OLD)) { thickK = 1.0; behind = false; }
	if (SHADE_WDEV_IS(f, SHADE_WDEV_SSR_THICK)) { thickK = 3.0; behind = false; }
	if (SHADE_WDEV_IS(f, SHADE_WDEV_SSR_BEHIND)) { thickK = 1.0; behind = true; }
	if (SHADE_WDEV_IS(f, SHADE_WDEV_SSR_BOTH)) { thickK = 3.0; behind = true; }
#ifdef SHADE_SSR_LEAN2
	float hiBack = -1.0;
#else
	float4 back = float4(0.0, 0.0, 0.0, -1.0);
#endif
	for (int i = 1; i <= n; i++) {
		float hi = shadeSsrT((base + float(i)) * inv);
		if (s0.z + ds.z * hi > zs[i]) continue;
		float lo = shadeSsrT(max(base + float(i - 1), 0.0) * inv);
		float tPrev = lo;
#ifdef SHADE_SSR_LEAN2
		float zHi = -1.0;
		for (int j = 0; j < 2; j++) {
			float m1 = 0.5 * (lo + hi), ma = 0.5 * (lo + m1), mb = 0.5 * (m1 + hi);
			float3 s1 = s0 + ds * m1, sa = s0 + ds * ma, sb = s0 + ds * mb;
			float z1 = shadeSsrZ(depth, s1, size), za = shadeSsrZ(depth, sa, size), zb = shadeSsrZ(depth, sb, size);
			if (s1.z <= z1) {
				hi = m1;
				zHi = z1;
				if (sa.z <= za) { hi = ma; zHi = za; } else lo = ma;
			} else {
				lo = m1;
				if (sb.z <= zb) { hi = mb; zHi = zb; } else lo = mb;
			}
		}
		float3 sh = s0 + ds * hi;
		float2 uv = sh.xy * 0.5 + 0.5;
		float zh = zHi >= 0.0 ? zHi : shadeSsrZ(depth, sh, size);
#else
		for (int j = 0; j < 4; j++) {
			float m = 0.5 * (lo + hi);
			float3 sm = s0 + ds * m;
			if (sm.z <= shadeSsrZ(depth, sm, size)) hi = m; else lo = m;
		}
		float3 sh = s0 + ds * hi;
		float2 uv = sh.xy * 0.5 + 0.5;
		float zh = shadeSsrZ(depth, sh, size);
#endif
		float wh = zh > 0.0 ? B / max(zh + A, 1e-7) : SHADE_WATER_SKY_W;
		float wr = B / max(sh.z + A, 1e-7);
		float wp = B / max(s0.z + ds.z * tPrev + A, 1e-7);
		float thick = 0.3 + 0.02 * wh + abs(wr - wp) * thickK;
		if (wh < SHADE_WATER_SKY_W && wr - wh < thick) {
#ifdef SHADE_SSR_EDGE
			float off = abs(sh.x) - 1.0;
			float fade = smoothstep(0.0, 0.07, min(uv.y, 1.0 - uv.y)) * (1.0 - 0.5 * smoothstep(0.0, 1.0, off));
			fade *= mix(1.0, saturate((wh / max(wr, 1e-3) - 0.5) * 4.0), saturate(off * 20.0));
			fade *= 1.0 - smoothstep(0.6, 1.0, (wr - wh) / thick);
			uv.x = clamp(uv.x, 0.5 / size.x, 1.0 - 0.5 / size.x);
			if (!screenEnd) fade *= 1.0 - smoothstep(0.85, 1.0, wr / max(B / max(s0.z + ds.z + A, 1e-7), 1e-3));
#else
			float2 edge = smoothstep(0.0, 0.07, min(uv, 1.0 - uv));
			float fade = edge.x * edge.y * (1.0 - smoothstep(0.6, 1.0, (wr - wh) / thick));
			if (!screenEnd) fade *= 1.0 - smoothstep(0.75, 1.0, hi);
#endif
			return float4(uv, fade, hi);
		}
#ifdef SHADE_SSR_LEAN2
		if (behind && hiBack < 0.0 && wh < SHADE_WATER_SKY_W && wh > B / max(s0.z + A, 1e-7)) hiBack = hi;
	}
	if (hiBack < 0.0) return float4(0.0, 0.0, 0.0, -1.0);
	float3 sh = s0 + ds * hiBack;
	float2 uv = sh.xy * 0.5 + 0.5;
	float zh = shadeSsrZ(depth, sh, size);
	float wh = zh > 0.0 ? B / max(zh + A, 1e-7) : SHADE_WATER_SKY_W;
	float wr = B / max(sh.z + A, 1e-7);
	float off = abs(sh.x) - 1.0;
	float bf = smoothstep(0.0, 0.07, min(uv.y, 1.0 - uv.y)) * (1.0 - 0.5 * smoothstep(0.0, 1.0, off));
	bf *= mix(1.0, saturate((wh / max(wr, 1e-3) - 0.5) * 4.0), saturate(off * 20.0));
	if (!screenEnd) bf *= 1.0 - smoothstep(0.85, 1.0, wr / max(B / max(s0.z + ds.z + A, 1e-7), 1e-3));
	return float4(clamp(uv.x, 0.5 / size.x, 1.0 - 0.5 / size.x), uv.y, bf, hiBack + 10.0);
#else
		if (behind && back.w < 0.0 && wh < SHADE_WATER_SKY_W && wh > B / max(s0.z + A, 1e-7)) {
			float off = abs(sh.x) - 1.0;
			float bf = smoothstep(0.0, 0.07, min(uv.y, 1.0 - uv.y)) * (1.0 - 0.5 * smoothstep(0.0, 1.0, off));
			bf *= mix(1.0, saturate((wh / max(wr, 1e-3) - 0.5) * 4.0), saturate(off * 20.0));
			if (!screenEnd) bf *= 1.0 - smoothstep(0.85, 1.0, wr / max(B / max(s0.z + ds.z + A, 1e-7), 1e-3));
			back = float4(clamp(uv.x, 0.5 / size.x, 1.0 - 0.5 / size.x), uv.y, bf, hi + 10.0);
		}
	}
	return back;
#endif
}
#else
static float4 shadeSsrMarch(float3 s0, float3 ds, bool screenEnd, int chunk, int chunks, float jitter, constant ShadeFrame &f,
                            depth2d<float> depth, texture2d<float> hz) {
	float2 size = shadeWaterRenderSize(f, float2(depth.get_width(), depth.get_height()));
	float A = f.proj[2][2], B = f.proj[3][2];
	const int n = SHADE_SSR_N;
	float inv = 1.0 / float(n * chunks);
	float u0 = max(float(chunk * n) - jitter, 0.0) * inv;
	float ts[SHADE_SSR_N + 1], ws[SHADE_SSR_N + 1];
	ts[0] = u0 * (0.35 + 0.65 * u0);
	ws[0] = 0.0;
	for (int i = 1; i <= n; i++) {
		float u = (float(chunk * n + i) - jitter) * inv;
		ts[i] = u * (0.35 + 0.65 * u);
		float3 s = s0 + ds * ts[i];
		ws[i] = shadeWaterCoarseW(hz, uint2(clamp((s.xy * 0.5 + 0.5) * size, float2(0.0), size - 1.0)), f);
	}
	for (int i = 1; i <= n; i++) {
		if (B / max(s0.z + ds.z * ts[i] + A, 1e-7) < ws[i]) continue;
		float lo = ts[i - 1], hi = ts[i];
		for (int j = 0; j < 4; j++) {
			float m = 0.5 * (lo + hi);
			float3 sm = s0 + ds * m;
			float wm = shadeWaterSceneW(depth, uint2(clamp((sm.xy * 0.5 + 0.5) * size, float2(0.0), size - 1.0)), f);
			if (B / max(sm.z + A, 1e-7) >= wm) hi = m; else lo = m;
		}
		float3 sh = s0 + ds * hi;
		float2 uv = sh.xy * 0.5 + 0.5;
		float wh = shadeWaterSceneW(depth, uint2(clamp(uv * size, float2(0.0), size - 1.0)), f);
		float wr = B / max(sh.z + A, 1e-7);
		float wp = B / max(s0.z + ds.z * ts[i - 1] + A, 1e-7);
		float thick = 0.3 + 0.02 * wh + abs(wr - wp);
		if (wh < SHADE_WATER_SKY_W && wr - wh < thick) {
#ifdef SHADE_SSR_EDGE
			float off = abs(sh.x) - 1.0;
			float fade = smoothstep(0.0, 0.07, min(uv.y, 1.0 - uv.y)) * (1.0 - 0.5 * smoothstep(0.0, 1.0, off));
			fade *= mix(1.0, saturate((wh / max(wr, 1e-3) - 0.5) * 4.0), saturate(off * 20.0));
			fade *= 1.0 - smoothstep(0.6, 1.0, (wr - wh) / thick);
			uv.x = clamp(uv.x, 0.5 / size.x, 1.0 - 0.5 / size.x);
			if (!screenEnd) fade *= 1.0 - smoothstep(0.85, 1.0, wr / max(B / max(s0.z + ds.z + A, 1e-7), 1e-3));
#else
			float2 edge = smoothstep(0.0, 0.07, min(uv, 1.0 - uv));
			float fade = edge.x * edge.y * (1.0 - smoothstep(0.6, 1.0, (wr - wh) / thick));
			if (!screenEnd) fade *= 1.0 - smoothstep(0.75, 1.0, hi);
#endif
			return float4(uv, fade, hi);
		}
	}
	return float4(0.0, 0.0, 0.0, -1.0);
}

#endif

static inline float shadeQuadSum(float v) { v += quad_shuffle_xor(v, 1); return v + quad_shuffle_xor(v, 2); }
static inline float3 shadeQuadSum(float3 v) { v += quad_shuffle_xor(v, 1); return v + quad_shuffle_xor(v, 2); }
static inline float shadeQuadMin(float v) { v = min(v, quad_shuffle_xor(v, 1)); return min(v, quad_shuffle_xor(v, 2)); }
static inline float shadeQuadMax(float v) { v = max(v, quad_shuffle_xor(v, 1)); return max(v, quad_shuffle_xor(v, 2)); }

static inline float3 shadeWaterHazed(float3 c, float3 p, float3 o, constant ShadeFrame &f, constant ShadeLook *L) {
	float3 s = p - o;
	if (L == nullptr || !shadeFeature(*L, SHADE_FEATURE_HAZE) || f.flags.x != SHADE_DIM_OVERWORLD || f.fogColor.a >= 0.5 || dot(s, s) <= 4.0) return c;
	float3 T;
	float3 S = shadeHazeInscatterFrom(s, f.cameraBlock.y + f.cameraFract.y + o.y, f, *L, 1.0, T);
	return c * T + S;
}

static float3 shadeWaterHitColor(float2 uv, float3 o, float exposure, float prevExposure, constant ShadeFrame &f, constant ShadeLook *L, texture2d<half> scene,
                                 depth2d<float> depth, texture2d<half> prev) {
	float2 tex = float2(depth.get_width(), depth.get_height());
	float2 size = shadeWaterRenderSize(f, tex);
	if (prevExposure > 0.0) {
		float z = depth.read(uint2(clamp(uv * size, float2(0.0), size - 1.0)));
		float4 wp = f.invViewProj * float4(uv * 2.0 - 1.0, z, 1.0);
		float3 pw = wp.xyz / wp.w;
		float4 pc = (L != nullptr ? L->prevViewProjNoJitter : f.prevViewProj) * float4(pw, 1.0);
		float2 uvp = pc.xy / pc.w * 0.5 + 0.5;
		if (pc.w > 0.0 && all(uvp > 0.0) && all(uvp < 1.0)) {
			float3 cp = float3(prev.sample(shadeWaterLinear, uvp).rgb) / prevExposure;
			return z > 0.0 ? shadeWaterHazed(cp, pw, o, f, L) : cp;
		}
#ifdef SHADE_WATER_EDGE_REFL
		return shadeWaterHazed(shadeWaterSceneRadiance(scene.sample(shadeWaterLinear, uv * size / tex).rgb, exposure), pw, o, f, L);
#endif
	}
	return shadeWaterSceneRadiance(scene.sample(shadeWaterLinear, uv * size / tex).rgb, exposure);
}

static float3 shadeWaterSkyRefl(float3 r, float3 rs, float3 o, float prevExposure, constant ShadeFrame &f, constant ShadeLook *L, constant ShadeResources *R,
                                texture2d<half> lut, sampler lutS, depth2d<float> depth, texture2d<half> prev) {
	float3 sky = shadeSkyLookup(r, lut, lutS, f);
	bool volumetricClouds = L != nullptr && R != nullptr && L->clouds0.w > 0.5;
#ifdef SHADE_WATER_EDGE_REFL
	bool domeSides = volumetricClouds && L->clouds1.x > 0.5;
	float3 rd = rs;
#else
	float3 rd = r;
#endif
	if (volumetricClouds) {
		float4 cd = shadeCloudsDome(rd, *L, R->cloudDome);
		sky = sky * cd.a + cd.rgb;
	}
	if (prevExposure > 0.0) {
		float4 c = f.viewProj * float4(rs, 0.0);
		if (c.w > 1e-4) {
			float2 uv = c.xy / c.w * 0.5 + 0.5;
#ifdef SHADE_SSR_EDGE
			float wgt = saturate(min(uv.y, 1.0 - uv.y) * 12.0) * (1.0 - 0.5 * smoothstep(0.0, 0.5, abs(uv.x - 0.5) - 0.5));
#ifdef SHADE_WATER_EDGE_REFL
			if (domeSides) wgt = saturate(min(uv.x, 1.0 - uv.x) * 8.0) * saturate(min(uv.y, 1.0 - uv.y) * 8.0);
#endif
			uv.x = saturate(uv.x);
#else
			float2 edge = saturate(min(uv, 1.0 - uv) * 12.0);
			float wgt = edge.x * edge.y;
#endif
			if (wgt > 0.0) {
				float2 size = shadeWaterRenderSize(f, float2(depth.get_width(), depth.get_height()));
				float z = depth.read(uint2(clamp(uv * size, float2(0.0), size - 1.0)));
				float3 pw = float3(0.0);
				if (z > 0.0) {
					float4 h = f.invViewProj * float4(uv * 2.0 - 1.0, z, 1.0);
					pw = h.xyz / h.w;
					bool cloud = !volumetricClouds && pw.y + f.cameraBlock.y + f.cameraFract.y > 150.0;
					if (!cloud) wgt *= smoothstep(160.0, 320.0, shadeLinearW(z, f));
				}
				if (wgt > 0.0) {
					float4 pc = (L != nullptr ? L->prevViewProjNoJitter : f.prevViewProj) * float4(rs, 0.0);
					float2 uvp = pc.xy / max(pc.w, 1e-4) * 0.5 + 0.5;
					float3 s = float3(prev.sample(shadeWaterLinear, saturate(uvp)).rgb) / prevExposure;
					if (z > 0.0) {
						s = shadeWaterHazed(s, pw, o, f, L);
					} else if (rs.y > 0.02 && !volumetricClouds) {
						float3 plain = shadeSkyLookup(rs, lut, lutS, f);
						float ls = shadeLuma(plain);
						float cloudy = saturate(abs(shadeLuma(s) - ls) / max(ls, 1e-5) * 4.0);
						if (cloudy > 0.0) {
							float tc = min((192.5 - f.cameraBlock.y - f.cameraFract.y) / rs.y, 640.0);
							if (tc > 2.0) s = mix(s, shadeWaterHazed(s, rs * tc, float3(0.0), f, L), cloudy);
						}
					}
					sky = mix(sky, s, wgt);
				}
			}
		}
	}
	return sky;
}

#ifdef SHADE_WATER_EDGE_REFL
static inline bool shadeSsrBelowStart(float2 uv, float3 p0, float3 r, constant ShadeFrame &f, depth2d<float> depth) {
	if (r.y <= 0.0) return false;
	float2 size = shadeWaterRenderSize(f, float2(depth.get_width(), depth.get_height()));
	float z = depth.read(uint2(clamp(uv * size, float2(0.0), size - 1.0)));
	float4 h = f.invViewProj * float4(uv * 2.0 - 1.0, z, 1.0);
	return h.y / h.w < p0.y - 0.3;
}
#endif

static float4 shadeSsr(float3 p0, float3 r, float2 pixel, float maxDist, float exposure, float prevExposure, constant ShadeFrame &f,
                       constant ShadeLook *L, texture2d<half> scene, depth2d<float> depth, texture2d<half> prev, texture2d<float> hz) {
	float2 tex = float2(depth.get_width(), depth.get_height());
	float2 size = shadeWaterRenderSize(f, tex);
	float3 s0 = 0.0, ds = 0.0;
	bool screenEnd = false;
#ifdef SHADE_SSR_QUAD
#ifdef SHADE_SSR_LANE
	float3 p0Own = p0, rOwn = r;
#endif
	p0 = shadeQuadSum(p0) * 0.25;
	r = normalize(shadeQuadSum(r));
	bool ok = shadeSsrSegment(p0, r, maxDist, f, s0, ds, screenEnd);
	if (!ok) return 0.0;
	uint2 q = uint2(pixel);
	int chunk = int(q.x & 1u) + 2 * int(q.y & 1u);
	float jitter = shadeNoise(floor(pixel * 0.5), SHADE_WATER_NOISE_FRAME(f));
	float4 hit = shadeSsrMarch(s0, ds, screenEnd, chunk, 4, jitter, f, depth, hz);
	float key = hit.w >= 10.0 ? float(chunk) + 4.0 : hit.w >= 0.0 ? float(chunk) : 8.0;
	float best = shadeQuadMin(key);
	if (best > 7.5) return 0.0;
	float3 mine = key == best ? hit.xyz : float3(0.0);
	float3 h = shadeQuadSum(mine);
#ifdef SHADE_WATER_EDGE_REFL
	if (shadeSsrBelowStart(h.xy, p0, r, f, depth)) return 0.0;
#endif
#ifdef SHADE_SSR_LANE
	if (best < 3.5) {
		float2 sz = shadeWaterRenderSize(f, float2(depth.get_width(), depth.get_height()));
		float zh = depth.read(uint2(clamp(h.xy * sz, float2(0.0), sz - 1.0)));
		float4 hp = f.invViewProj * float4(h.xy * 2.0 - 1.0, zh, 1.0);
		float T = dot(hp.xyz / hp.w - p0, r);
		float4 cl = f.viewProj * float4(p0Own + rOwn * max(T, 0.0), 1.0);
		if (cl.w > 1e-4) {
			float2 uvl = cl.xy / cl.w * 0.5 + 0.5;
			if (uvl.y > 0.0 && uvl.y < 1.0) {
				float2 ul = float2(clamp(uvl.x, 0.5 / sz.x, 1.0 - 0.5 / sz.x), uvl.y);
				if (depth.read(uint2(clamp(ul * sz, float2(0.0), sz - 1.0))) > 0.0) h.xy = ul;
			}
		}
	}
#endif
	return float4(shadeWaterHitColor(h.xy, p0, exposure, prevExposure, f, L, scene, depth, prev), h.z);
#else
	if (!shadeSsrSegment(p0, r, maxDist, f, s0, ds, screenEnd)) return 0.0;
	float4 hit = shadeSsrMarch(s0, ds, screenEnd, 0, 1, shadeNoise(pixel, SHADE_WATER_NOISE_FRAME(f)), f, depth, hz);
	if (hit.w < 0.0) return 0.0;
#ifdef SHADE_WATER_EDGE_REFL
	if (shadeSsrBelowStart(hit.xy, p0, r, f, depth)) return 0.0;
#endif
	return float4(shadeWaterHitColor(hit.xy, p0, exposure, prevExposure, f, L, scene, depth, prev), hit.z);
#endif
}

static inline float shadeWaterFresnel(float cosT) {
	float m = 1.0 - saturate(cosT);
	float m2 = m * m;
	return 0.0204 + 0.9796 * m2 * m2 * m;
}

static inline float shadeGgxSpec(float3 n, float3 v, float3 l, float alpha) {
	float3 h = normalize(l - v);
	float nh = saturate(dot(n, h)), nl = saturate(dot(n, l)), nv = max(dot(n, -v), 1e-3);
	float a2 = alpha * alpha;
	float dd = nh * nh * (a2 - 1.0) + 1.0;
	float D = a2 / (3.14159265 * dd * dd);
	float gv = nl * sqrt(nv * nv * (1.0 - a2) + a2), gl = nv * sqrt(nl * nl * (1.0 - a2) + a2);
	float vis = 0.5 / max(gv + gl, 1e-5);
	return 3.14159265 * D * vis * nl * shadeWaterFresnel(dot(h, -v));
}

static inline float3 shadeWaterSigmaALin(float3 tintLinear) {
	float3 t = max(tintLinear, float3(0.004));
	return float3(0.26, 0.07, 0.035) + 0.09 * -log(t);
}
static inline float3 shadeWaterSigmaA(half3 tint) {
	return shadeWaterSigmaALin(float3(shadeToLinear(tint)));
}
#define SHADE_WATER_SIGMA_S 0.022

static inline float shadeWaterHash2(float2 c) {
	uint2 i = uint2(int2(c) + int2(32768));
	uint h = i.x * 0x8da6b343u ^ i.y * 0xd8163841u;
	h = (h ^ (h >> 15)) * 0x2c1b3c6du;
	h ^= h >> 12;
	return float(h & 0xffffu) * (1.0 / 65535.0);
}
static inline float shadeWaterValueNoise(float2 x) {
	float2 i = floor(x), fr = x - i;
	float2 u = fr * fr * (3.0 - 2.0 * fr);
	float a = shadeWaterHash2(i), b = shadeWaterHash2(i + float2(1, 0)), c = shadeWaterHash2(i + float2(0, 1)), d = shadeWaterHash2(i + float2(1, 1));
	return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}
static inline float3 shadeWaterViewPoint(depth2d<float> depth, uint2 q, float2 size, constant ShadeFrame &f) {
	float z = depth.read(q);
	float4 h = f.invViewProj * float4((float2(q) + 0.5) / size * 2.0 - 1.0, max(z, 1e-7), 1.0);
	return h.xyz / h.w;
}

#ifdef SHADE_WATER_DETAIL
#define SHADE_WATER_DETAIL_SCALE 3.3
#define SHADE_WATER_DETAIL_AMP 0.30
#define SHADE_WATER_LONG 0.6
#define SHADE_WATER_PIXEL 0.7
static inline float2 shadeWaterRot(float2 v) { return float2(0.6 * v.x - 0.8 * v.y, 0.8 * v.x + 0.6 * v.y); }
static inline float2 shadeWaterRotT(float2 v) { return float2(0.6 * v.x + 0.8 * v.y, -0.8 * v.x + 0.6 * v.y); }
#ifndef SHADE_WATER_PATTERN_FAR
#define SHADE_WATER_PATTERN_FAR 0.075
#endif
#ifndef SHADE_WATER_DETAIL_FAR
#define SHADE_WATER_DETAIL_FAR 96.0
#endif
static inline float2 shadeWaterDetailXZ(float2 x) {
	return shadeWaterRot(x) * SHADE_WATER_DETAIL_SCALE + float2(23.7, 41.3);
}
#endif

// (FT2.11, mcopt.shade.water.falls = ShadeWaterIn.cave.w, Gael 2026-10-11: water "cayendo o en cascada se ve muy lisa por afuera y muy
// cuadrada") Moving water, after Photon (MIT snapshot 26c2906e: flowing water's waves run along the current and are ~2-5x stronger
// than still water's) and Complementary / BSL (their waves are laid on every face of the water, the sides too; technique only):
// All of it only in the translucent terrain's variant drawn while moving water is within ~96 blocks (WaterFlowScan; SHADE_LEAN_NOFALLS
// = none near: FT2.10's code exactly, the water shader being bound by registers), faded out from SHADE_WATER_FALL_RANGE x 2/3 to
// SHADE_WATER_FALL_RANGE blocks (inside the scanned box: the switch of variant never shows).
//  - a current (flowing water's top faces, which already carry their waves downstream) roughens them by 1 + SHADE_WATER_FLOW_ROUGH k
//    (the calm of a lake isn't a stream's). (Drawing them out along the flow into streaks was tried and dropped: +0.11 ms on every
//    water pixel, the water shader being bound by registers; Photon doesn't either: its current only aims and speeds its waves.)
//  - falling water's sides get the wave field too, finer and drawn out downward, sliding down, and white water (shadeWaterFallNormal,
//    shadeWaterFallFoam: by the terrain fragment around shadeWaterSurface).
// k: ShadeWaterIn.cave.w (1 = this look, 0 = FT2.10's).
#define SHADE_WATER_FLOW_ROUGH 1.4
#define SHADE_WATER_FALL_RANGE 96.0
static inline float shadeWaterMoving(float k, float3 p) {
	return k * (1.0 - smoothstep(SHADE_WATER_FALL_RANGE * (2.0 / 3.0), SHADE_WATER_FALL_RANGE, length(p)));
}

struct ShadeWaterIn {
	float3 p;
	float3 nFace;
	float footprint;
	float2 flowDir;
	float2 pixel;
	float depth;
	half3 tint;
	half sky;
	half shadow;
	half4 cave;
	float3 lit;
	float4 dxz;
	float exposure;
	float prevExposure;
	constant ShadeLook *look;
	constant ShadeResources *res;
	float3 fog;
	float4 detail;
};

#ifdef SHADE_WATER_DETAIL
constexpr sampler shadeWaveDetailSampler(filter::linear, mip_filter::linear, address::repeat);
static inline float4 shadeWaterDetailIn(ShadeWaterIn w, constant ShadeFrame &f, texture2d<half> waves) {
	const float s = SHADE_WATER_DETAIL_SCALE;
	float far = 1.0 - smoothstep(0.6 * SHADE_WATER_DETAIL_FAR, SHADE_WATER_DETAIL_FAR, length(w.p));
	if (far <= 0.0) return float4(0.0);
	float t = shadeWaterTime(f);
	float2 xa = shadeWaterXZ(w.p.xz, f);
	float2 x = xa - w.flowDir * (t * SHADE_WATER_FLOW_SPEED);
	float2 cell = (floor(xa * 16.0) + 0.5) * (1.0 / 16.0) - w.flowDir * (t * SHADE_WATER_FLOW_SPEED);
#ifdef SHADE_WATER_SMOOTH
	float q = 0.0, nearP = 0.0;
#else
	float near = 1.0 - smoothstep(0.03, 0.065, w.footprint);
	float q = near * SHADE_WATER_PIXEL;
	float nearP = 1.0 - smoothstep(0.6 * SHADE_WATER_PATTERN_FAR, SHADE_WATER_PATTERN_FAR, w.footprint);
#endif
	float g = (1.0 - q) * (s / SHADE_WAVE_TILE);
	half4 d = waves.sample(shadeWaveDetailSampler, shadeWaterDetailXZ(mix(x, cell, q)) * (1.0 / SHADE_WAVE_TILE),
	                       gradient2d(shadeWaterRot(w.dxz.xy) * g, shadeWaterRot(w.dxz.zw) * g));
	return float4(shadeWaterRotT(float2(d.xy)) * (s * far), float(d.z) * far, -float(d.w) * (nearP / 0.19));
}
#endif

#ifdef SHADE_WATER_SMOOTH
#ifndef SHADE_WATER_CALM
#define SHADE_WATER_CALM 0.55
#endif
#ifndef SHADE_WATER_RIPPLE
#define SHADE_WATER_RIPPLE 2.0
#endif
static inline float2 shadeWaterCalm(float3 p, constant ShadeFrame &f, constant ShadeWaterParams &wp) {
	float t = shadeWaterTime(f);
	float2 x = shadeWaterXZ(p.xz, f);
	float patch = shadeWaterValueNoise(x * (1.0 / 190.0) + float2(t * 0.011, t * 0.007));
	float3 v = normalize(p - shadeEye(f.invViewProj));
	float graze = 0.55 + 0.45 * smoothstep(0.0, 0.1, abs(v.y));
	float rain = saturate(wp.state.z);
	return float2(mix(SHADE_WATER_CALM, 1.0, rain) * (0.6 + 0.8 * patch), mix(SHADE_WATER_RIPPLE, 1.3, rain) * (0.75 + 0.5 * patch)) * graze;
}
#endif

static float3 shadeWaterNormal(ShadeWaterIn w, constant ShadeFrame &f, constant ShadeWaterParams &wp, texture2d<half> waves, thread float &height,
                               thread float &pattern, float detail = 1.0) {
	pattern = 0.0;
	float t = shadeWaterTime(f);
	float2 x = shadeWaterXZ(w.p.xz, f) - w.flowDir * (t * SHADE_WATER_FLOW_SPEED);
#ifdef SHADE_LEAN_NOFALLS
	const float mv = 0.0;
#else
	float mv = shadeWaterMoving(float(w.cave.w), w.p);
#endif
#ifdef SHADE_WATER_SMOOTH
	const float fade = 1.0 / 170.0;
#else
	const float fade = 1.0 / 90.0;
#endif
	float strength = wp.tune.x * (1.0 + 1.6 * wp.state.z) * (1.0 + (0.6 + SHADE_WATER_FLOW_ROUGH * mv) * length(w.flowDir)) * exp(-length(w.p) * fade);
#ifdef SHADE_WAVE_BAKE
	if (wp.state.w > 0.5) {
		half4 b = waves.sample(shadeWaveSampler, x * (1.0 / SHADE_WAVE_TILE), gradient2d(w.dxz.xy * (1.0 / SHADE_WAVE_TILE), w.dxz.zw * (1.0 / SHADE_WAVE_TILE)));
		height = float(b.z) * strength;
		float2 gb = float2(b.xy) * strength;
#ifdef SHADE_WATER_DETAIL
		if (detail > 0.0) {
			pattern = w.detail.w;
#ifdef SHADE_WATER_SMOOTH
			float2 calm = shadeWaterCalm(w.p, f, wp);
			height = (float(b.z) * (SHADE_WATER_LONG * calm.x) + w.detail.z * (SHADE_WATER_DETAIL_AMP * detail * calm.y)) * strength;
			gb = (float2(b.xy) * (SHADE_WATER_LONG * calm.x) + w.detail.xy * (SHADE_WATER_DETAIL_AMP * detail * calm.y)) * strength;
#else
			height = (float(b.z) * SHADE_WATER_LONG + w.detail.z * (SHADE_WATER_DETAIL_AMP * detail)) * strength;
			gb = (float2(b.xy) * SHADE_WATER_LONG + w.detail.xy * (SHADE_WATER_DETAIL_AMP * detail)) * strength;
#endif
		}
#ifdef SHADE_WATER_SMOOTH
		else {
			float c = shadeWaterCalm(w.p, f, wp).x;
			gb *= c;
			height *= c;
		}
#endif
#endif
		return normalize(float3(-gb.x, 1.0, -gb.y));
	}
#endif
	float lap;
	float2 g = shadeWaves(x, t, strength, w.footprint, lap, height);
#if defined(SHADE_WAVE_BAKE) && defined(SHADE_WATER_SMOOTH)
	{
		float k = shadeWaterCalm(w.p, f, wp).x;
#ifdef SHADE_WATER_DETAIL
		if (detail > 0.0) k *= SHADE_WATER_LONG;
#endif
		g *= k;
		height *= k;
	}
#endif
	return normalize(float3(-g.x, 1.0, -g.y));
}

#ifdef SHADE_WAVE_BAKE
#define SHADE_WATER_FALL_SCALE 3.0
#define SHADE_WATER_FALL_STRETCH 0.3
#define SHADE_WATER_FALL_SLOPE 0.9
#define SHADE_WATER_FALL_SCROLL (192.0 / SHADE_WATER_CLOCK_WRAP)
#define SHADE_WATER_FALL_FOAM 0.32
#define SHADE_WATER_FALL_SHEET 0.07
#define SHADE_WATER_FOAM_SCROLL (8960.0 / SHADE_WATER_CLOCK_WRAP)
static inline bool shadeWaterIsFall(float ny, float mv, constant ShadeWaterParams &wp) {
	return mv > 0.0 && abs(ny) < 0.5 && wp.state.w > 0.5;
}
static float3 shadeWaterFallNormal(ShadeWaterIn w, constant ShadeFrame &f, texture2d<half> waves) {
	float3 nf = w.nFace;
	float3 v = normalize(w.p - shadeEye(f.invViewProj));
	float3 tu = normalize(float3(-nf.z, 0.0, nf.x));
	float2 xa = shadeWaterXZ(w.p.xz, f);
	float ya = w.p.y + f.cameraFract.y + fmod(f.cameraBlock.y, 2048.0);
	float2 c = float2(dot(xa, tu.xz), ya * SHADE_WATER_FALL_STRETCH) * (SHADE_WATER_FALL_SCALE / SHADE_WAVE_TILE);
	c.y += shadeWaterTime(f) * SHADE_WATER_FALL_SCROLL;
	float lod = max(log2(max(w.footprint * (SHADE_WATER_FALL_SCALE * float(SHADE_WAVE_BAKE_SIZE) / SHADE_WAVE_TILE), 1e-4)), 0.0);
	half4 b = waves.sample(shadeWaveSampler, c, level(lod));
	float k = shadeWaterMoving(float(w.cave.w), w.p) * SHADE_WATER_FALL_SLOPE * exp(-length(w.p) * (1.0 / 120.0)) * smoothstep(0.0, 0.3, abs(dot(nf, v)));
	float2 s = float2(b.xy) * (float2(SHADE_WATER_FALL_SCALE, SHADE_WATER_FALL_SCALE * SHADE_WATER_FALL_STRETCH) * k);
	return normalize(nf - s.x * tu - s.y * float3(0.0, 1.0, 0.0));
}
static float shadeWaterFallFoam(float3 p, float3 nf, float footprint, float mv, constant ShadeFrame &f) {
	float3 tu = normalize(float3(-nf.z, 0.0, nf.x));
	float2 xa = shadeWaterXZ(p.xz, f);
	float ya = p.y + f.cameraFract.y + fmod(f.cameraBlock.y, 2048.0);
	float2 q = float2(dot(xa, tu.xz) * 7.0, ya * 0.35 + shadeWaterTime(f) * SHADE_WATER_FOAM_SCROLL);
	float nz = 0.5 * shadeWaterValueNoise(q) + 0.3 * shadeWaterValueNoise(q * 2.0 + float2(17.3, 5.1)) + 0.2 * shadeWaterValueNoise(q * 4.0 + float2(3.7, 41.9));
	float near = 1.0 - smoothstep(0.03, 0.08, footprint);
	return (SHADE_WATER_FALL_SHEET + SHADE_WATER_FALL_FOAM * mix(0.3, smoothstep(0.45, 0.8, nz), near)) * (min(shadeWaterMoving(mv, p), 1.5) * exp(-length(p) * (1.0 / 120.0)));
}
#endif

#define SHADE_CAUSTIC_COUNT 6
constant float4 SHADE_CAUSTIC_WAVES[SHADE_CAUSTIC_COUNT] = {
	float4( 0.940,  0.342, 2.60, 0.018),
	float4(-0.276,  0.961, 2.10, 0.015),
	float4( 0.454, -0.891, 1.70, 0.012),
	float4(-0.866, -0.500, 1.37, 0.0095),
	float4( 0.643,  0.766, 1.11, 0.0075),
	float4(-0.985,  0.174, 0.89, 0.006),
};

static float shadeWaterCaustics(float3 pf, float dy, constant ShadeFrame &f, constant ShadeWaterParams &wp, float3 l) {
	float2 x = shadeWaterXZ(pf.xz + l.xz / max(l.y, 0.25) * dy, f);
	float t = shadeWaterTime(f);
	float3 hs = 0.0;
	for (int i = 0; i < SHADE_CAUSTIC_COUNT; i++) {
		float4 w = SHADE_CAUSTIC_WAVES[i];
		float k = 6.2831853 / w.z;
		float th = k * dot(w.xy, x) + shadeWaterOmega(sqrt(9.81 * k) * 0.55) * t + float(i) * 1.9;
		float c;
		float s = sincos(th, c);
		float e = exp(s - 1.0);
		float q = w.w * k * k * (c * c - s) * e;
		hs += q * float3(w.x * w.x, w.y * w.y, w.x * w.y);
		x -= w.xy * (c * e * w.w * 14.0);
	}
	float kk = min(dy, 5.0) * 0.25 * 3.2 * wp.tune.x * (1.0 + 0.8 * wp.state.z);
	float det = (1.0 + kk * hs.x) * (1.0 + kk * hs.y) - kk * kk * hs.z * hs.z;
	return min(1.0 / max(abs(det), 0.18), 4.0);
}

static float3 shadeWaterAbove(ShadeWaterIn w, constant ShadeFrame &f, constant ShadeWaterParams &wp, texture2d<half> lut, sampler lutS,
                              texture2d<half> scene, depth2d<float> depth, texture2d<half> prev, texture2d<half> waves, texture2d<float> hz) {
	float3 pe = w.p - shadeEye(f.invViewProj);
	float dist = length(pe);
	float3 v = pe / max(dist, 1e-4);
	bool top = w.nFace.y > 0.5;
	float height = 0.0, pattern = 0.0;
	float3 n = w.nFace;
	if (top) {
		n = shadeWaterNormal(w, f, wp, waves, height, pattern);
#if defined(SHADE_WEATHER) && defined(SHADE_RIPPLES)
		if (wp.state.z > 0.02 && w.sky > 0.95h) {
			float rf = 1.0 - smoothstep(0.03, 0.06, w.footprint);
			if (rf > 0.0) {
				float2 rs = shadeWxRipples(shadeWxXZ(w.p.xz, f), f.sunPath.w, min(wp.state.z * 1.1, 1.0), w.footprint) * (rf * 1.4);
				n = normalize(n + float3(-rs.x, 0.0, -rs.y));
			}
		}
#endif
		float graze = 1.0 - saturate(-v.y);
		n = normalize(mix(n, float3(0.0, 1.0, 0.0), 0.8 * graze * graze * graze * graze));
	}
	float cosV = max(dot(-v, n), 0.02);
	float fres = shadeWaterFresnel(cosV) * wp.tune.z;
#ifdef SHADE_LEAN_ABOVE
	float3 rr = reflect(v, normalize(mix(n, w.nFace, 0.55 - 0.4 * float(w.cave.y))));
	rr = normalize(float3(rr.x, max(rr.y, 0.01), rr.z));
#endif
#if defined(SHADE_SSR) && defined(SHADE_LEAN_ABOVE)
	float4 ssrHit = float4(0.0);
	float ssrW = 0.0;
	float fq = shadeQuadMax(shadeWaterFresnel(cosV));
	if (fq > 0.022 && wp.tune.y > 0.0 && !SHADE_WDEV_IS(f, SHADE_WDEV_NO_SSR)) {
		ssrHit = shadeSsr(w.p + n * 0.02, rr, w.pixel, wp.tune.y, w.exposure, w.prevExposure, f, w.look, scene, depth, prev, hz);
		ssrW = ssrHit.a * smoothstep(0.022, 0.03, fq);
	}
#endif

	float2 tex = float2(scene.get_width(), scene.get_height());
	float2 size = shadeWaterRenderSize(f, tex);
	float2 uv = w.pixel / size;
	float wSurf = shadeLinearW(w.depth, f);
	float w0 = shadeWaterSceneW(depth, uint2(min(w.pixel, size - 1.0)), f);
	float dw0 = w0 < SHADE_WATER_SKY_W ? w0 - wSurf : 64.0;
	float3 tilt = (f.view * float4(n - w.nFace, 0.0)).xyz;
#ifdef SHADE_WATER_DETAIL
	float2 shift = tilt.xy * (0.16 * min(max(dw0, 0.0), 4.0));
#else
	float2 shift = tilt.xy * (0.4 * min(max(dw0, 0.0), 6.0));
#endif
	if (w.look != nullptr) shift *= w.look->user1.w;
	float2 uvR = uv + float2(f.proj[0][0] * shift.x, f.proj[1][1] * shift.y) * (0.5 / max(wSurf, 0.5));
	uvR = clamp(uvR, 0.5 / size, 1.0 - 0.5 / size);
	float wR = shadeWaterSceneW(depth, uint2(uvR * size), f);
	if (wR < wSurf) {
		uvR = uv;
		wR = w0;
	}
	bool hasFloor = wR < SHADE_WATER_SKY_W;
	float lView = hasFloor ? max(wR - wSurf, 0.0) * dist / max(wSurf, 1e-3) : 1e4;
	float dy = top ? lView * saturate(-v.y) : lView * 0.5;
	float3 sigmaA = shadeWaterSigmaA(w.tint);
	if (w.look != nullptr) {
		float4 uw = w.look->vl;
		if (uw.w > 0.0) sigmaA = mix(sigmaA, shadeWaterSigmaALin(uw.rgb), uw.w);
		float clarity = w.look->grade0.w;
		if (clarity > 0.0) sigmaA /= clarity;
	}
	float3 sigmaT = sigmaA + SHADE_WATER_SIGMA_S;
	float3 bottom = shadeWaterSceneRadiance(scene.sample(shadeWaterLinear, uvR * size / tex).rgb, w.exposure);
#ifdef SHADE_CAUSTICS
	if (top && wp.tune.w > 0.0 && dy > 0.05 && dy < 18.0) {
		float sunL = f.lightDirWorld.w > 0.5 ? smoothstep(0.1, 0.8, float(w.shadow)) * saturate(f.lightDirWorld.y * 4.0) * smoothstep(0.55, 0.9, float(w.sky)) : 0.0;
		float torchL = float(w.cave.x) * (1.0 - sunL);
		float light = sunL + torchL;
		float fade = light * exp(-dy * 0.2) * saturate(dy * 3.0) * (1.0 - smoothstep(0.04, 0.12, w.footprint));
		if (fade > 0.02) {
			float3 cl = sunL >= torchL ? f.lightDirWorld.xyz : float3(0.0, 1.0, 0.0);
			float c = shadeWaterCaustics(w.p + v * lView, dy, f, wp, cl);
			bottom *= mix(1.0, c * 0.8, min(0.75 * fade * wp.tune.w, 1.0));
		}
	}
#endif
	float3 tBottom = exp(-sigmaT * (lView + dy));
	float3 tView = exp(-sigmaT * min(lView, 512.0));
	float3 inScatter = w.lit * exp(-sigmaT * 2.0) * (SHADE_WATER_SIGMA_S / sigmaT) * 0.8;
	float3 body = bottom * tBottom + inScatter * (1.0 - tView);
#ifdef SHADE_WATER_DETAIL
	body *= 1.0 + 0.13 * clamp(pattern, -1.5, 2.5) + 0.35 * smoothstep(0.9, 2.0, pattern);
#endif

	float open = float(shadeSkyCurve(w.sky));
	float3 r = reflect(v, n);
	float3 rs = float3(r.x, abs(r.y), r.z);
#ifndef SHADE_LEAN_ABOVE
	float3 rr = reflect(v, normalize(mix(n, w.nFace, 0.55 - 0.4 * float(w.cave.y))));
	rr = normalize(float3(rr.x, max(rr.y, 0.01), rr.z));
#endif
	float3 rl = normalize(mix(rs, rr, 0.6));
	float3 refl = shadeWaterSkyRefl(rl, rr, w.p, w.prevExposure, f, w.look, w.res, lut, lutS, depth, prev) * open;
	if (w.cave.z > 0.0h) {
		float sk = shadeLuma(shadeSkyLookup(float3(rl.x, max(rl.y, 0.0), rl.z), lut, lutS, f));
		float3 env = w.lit * (0.5 * sk / max(shadeLuma(f.ambientSky.rgb), 1e-6));
		refl = mix(refl, env, float(w.cave.z));
	}
	float hitWeight = 0.0;
#ifdef SHADE_SSR
#ifdef SHADE_LEAN_ABOVE
	hitWeight = ssrW;
	refl = mix(refl, ssrHit.rgb, hitWeight);
#else
	float fq = shadeQuadMax(shadeWaterFresnel(cosV));
	if (fq > 0.022 && wp.tune.y > 0.0 && !SHADE_WDEV_IS(f, SHADE_WDEV_NO_SSR)) {
		float4 hit = shadeSsr(w.p + n * 0.02, rr, w.pixel, wp.tune.y, w.exposure, w.prevExposure, f, w.look, scene, depth, prev, hz);
		hitWeight = hit.a * smoothstep(0.022, 0.03, fq);
		refl = mix(refl, hit.rgb, hitWeight);
	}
#endif
#endif
#ifdef SHADE_WATER_GLINT_GRAZE
	float far = saturate(dist / 110.0) * (1.0 - smoothstep(0.1, 0.4, -v.y));
#else
	float far = saturate(dist / 110.0);
#endif
	float alpha = 0.035 + 0.10 * far + 0.06 * wp.state.z;
	float spec = shadeGgxSpec(n, v, f.lightDirWorld.xyz, alpha);
	float soft = smoothstep(0.15, 0.85, float(w.shadow));
	float3 glint = f.sunColor.rgb * min(spec, 60.0) * soft * open * (1.0 - hitWeight);

	float3 color = mix(body, refl, fres) + glint * (wp.tune.z * (w.look != nullptr ? w.look->user2.x : 1.0));
	if (f.flags.w == 5) return float3(hitWeight, fres, 0.0) / max(w.exposure, 1e-3);
	if (f.flags.w == 6) return float3(saturate(lView / 16.0), saturate(dy / 8.0), hasFloor ? 0.0 : 1.0) / max(w.exposure, 1e-3);

#ifdef SHADE_FOAM
	if (top && hasFloor && dist < 28.0) {
		float shore = min(dy, lView * 0.7);
		float edge = 0.10 + 0.16 * saturate(height * 9.0);
		float foam = (1.0 - smoothstep(0.0, edge, shore)) * smoothstep(0.0, 0.03, shore + 0.02);
		if (foam > 0.01) {
			float2 x = shadeWaterXZ(w.p.xz, f);
			float t = shadeWaterTime(f);
			float nz = 0.65 * shadeWaterValueNoise(x * 1.7 + float2(t * 0.11, t * 0.07)) + 0.35 * shadeWaterValueNoise(x * 4.3 - float2(t * 0.17, -t * 0.13));
			foam *= smoothstep(0.42, 0.72, nz) * saturate(height * 12.0 + 0.3);
			uint2 q = uint2(min(uvR * size, size - 2.0));
			float3 f0 = shadeWaterViewPoint(depth, q, size, f), fx = shadeWaterViewPoint(depth, q + uint2(1, 0), size, f),
			       fz = shadeWaterViewPoint(depth, q + uint2(0, 1), size, f);
			float3 fn = cross(fx - f0, fz - f0);
			float upness = abs(fn.y) / max(length(fn), 1e-6);
			foam *= smoothstep(0.55, 0.85, upness) * (1.0 - smoothstep(16.0, 28.0, dist));
			color = mix(color, w.lit * 0.5, foam * (w.look != nullptr ? 0.3 * w.look->user1.z : 0.3));
		}
	}
#endif
	return color;
}

static float3 shadeWaterBelow(ShadeWaterIn w, constant ShadeFrame &f, constant ShadeWaterParams &wp, texture2d<half> lut, sampler lutS,
                              texture2d<half> scene, depth2d<float> depth, texture2d<half> prev, texture2d<half> waves, texture2d<float> hz) {
	float3 pe = w.p - shadeEye(f.invViewProj);
	float dist = length(pe);
	float3 v = pe / max(dist, 1e-4);
	float height;
	float pattern;
	float3 up = shadeWaterNormal(w, f, wp, waves, height, pattern, 0.6);
	up = normalize(float3(up.x * 1.8, up.y, up.z * 1.8));
	float3 n = -up;
	float3 t = refract(v, n, 1.333);
	bool tir = dot(t, t) < 1e-6;
	float fres = tir ? 1.0 : shadeWaterFresnel(dot(t, up));
	float2 tex = float2(scene.get_width(), scene.get_height());
	float2 size = shadeWaterRenderSize(f, tex);
	float2 uv = w.pixel / size;
	float3 above = 0.0;
	if (!tir) {
		float3 tilt = (f.view * float4(up - float3(0.0, 1.0, 0.0), 0.0)).xyz;
		float wSurf = shadeLinearW(w.depth, f);
		float2 uvR = clamp(uv + float2(f.proj[0][0] * tilt.x, f.proj[1][1] * tilt.y) * (1.2 / max(wSurf, 0.5)), 0.5 / size, 1.0 - 0.5 / size);
		float wR = shadeWaterSceneW(depth, uint2(uvR * size), f);
		float3 sky = shadeSkyLookup(t, lut, lutS, f);
		float ct = dot(t, f.lightDirWorld.xyz);
		if (f.lightDirWorld.w > 0.5) sky += f.sunColor.rgb * (exp((ct - 1.0) * 6000.0) * 30.0 + exp((ct - 1.0) * 400.0) * 0.35) * float(w.shadow);
		above = wR < SHADE_WATER_SKY_W ? shadeWaterSceneRadiance(scene.sample(shadeWaterLinear, uvR * size / tex).rgb, w.exposure) : sky;
		above *= float(shadeSkyCurve(w.sky)) * 0.6 + 0.4;
	}
	float3 below = w.fog * (0.65 + 0.5 * saturate(height * 7.0 + 0.3));
#ifdef SHADE_SSR
	if (shadeQuadMax(fres) > 0.05 && wp.tune.y > 0.0) {
		float4 hit = shadeSsr(w.p + n * 0.02, reflect(v, n), w.pixel, min(wp.tune.y, 48.0), w.exposure, 0.0, f, w.look, scene, depth, prev, hz);
		below = mix(below, hit.rgb, hit.a);
	}
#endif
	return mix(above, below, fres);
}

static float3 shadeWaterSurface(ShadeWaterIn w, constant ShadeFrame &f, constant ShadeWaterParams &wp, texture2d<half> lut, sampler lutS,
                                texture2d<half> scene, depth2d<float> depth, texture2d<half> prev, texture2d<half> waves, texture2d<float> hz) {
	if (SHADE_CAMERA_UNDERWATER(f) && w.nFace.y < -0.5) return shadeWaterBelow(w, f, wp, lut, lutS, scene, depth, prev, waves, hz);
	return shadeWaterAbove(w, f, wp, lut, lutS, scene, depth, prev, waves, hz);
}

static float4 shadeGlossy(float3 radiance, float alpha, float3 p, float3 n, float2 pixel, half sky, half shadow, float exposure,
                          float prevExposure, constant ShadeFrame &f, constant ShadeLook *L, constant ShadeResources *R, constant ShadeWaterParams &wp,
                          texture2d<half> lut, sampler lutS, texture2d<half> scene, depth2d<float> depth, texture2d<half> prev, texture2d<float> hz) {
	float3 pe = p - shadeEye(f.invViewProj);
	float dist = length(pe);
	float3 v = pe / max(dist, 1e-4);
	float cosV = saturate(dot(-v, n));
	float m = 1.0 - cosV;
	float m2 = m * m;
	float fres = (0.04 + 0.96 * m2 * m2 * m) * wp.tune.z;
	float open = float(shadeSkyCurve(sky));
	float3 r = reflect(v, n);
	float3 refl = shadeWaterSkyRefl(float3(r.x, abs(r.y), r.z), float3(r.x, abs(r.y), r.z), p, prevExposure, f, L, R, lut, lutS, depth, prev) * open;
	float hitWeight = 0.0;
#ifdef SHADE_SSR
	if (wp.state.x > 0.5 && wp.tune.y > 0.0 && shadeQuadMax(fres) > 0.03) {
		float4 hit = shadeSsr(p + n * 0.02, r, pixel, min(wp.tune.y, 128.0), exposure, prevExposure, f, L, scene, depth, prev, hz);
		hitWeight = hit.a;
		refl = mix(refl, hit.rgb, hit.a);
	}
#endif
	float3 glint = f.sunColor.rgb * min(shadeGgxSpec(n, v, f.lightDirWorld.xyz, 0.03), 40.0) * smoothstep(0.15, 0.85, float(shadow)) * open
		* (1.0 - hitWeight) * (f.lightDirWorld.w > 0.5 ? 1.0 : 0.3);
	float3 front = radiance * alpha * (1.0 - fres) + refl * fres + glint * 2.0 * wp.tune.z;
	float outA = alpha + (1.0 - alpha) * fres;
	return float4(front / max(outA, 1e-3), outA);
}

static inline float2 shadeWaterFlowDir(float3 dpx, float3 dpy, float2 duvx, float2 duvy) {
	float2 a = dpx.xz, b = dpy.xz;
	float det = a.x * b.y - a.y * b.x;
	if (abs(det) < 1e-12) return 0.0;
	float2 g = float2(duvx.y * b.y - duvy.y * a.y, -duvx.y * b.x + duvy.y * a.x) / det;
	float l = length(g);
	return l > 1e-6 ? g / l : float2(0.0);
}

#endif
