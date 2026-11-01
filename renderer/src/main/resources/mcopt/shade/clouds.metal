// Copyright 2026 mcopt-shade contributors
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
// License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
// law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
// OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
// limitations under the License.
//
// Portions adapted from Photon Shaders by Benjamin Stott ("sixthsurge"), MIT License, commit 26c2906e
// (include/sky/clouds/common.glsl, cumulus.glsl, cirrus.glsl; program/d2_clouds_upscaling.fsh): the cumulus altitude
// shaping (flat bases, egg-shaped tops), the "powder" brightening of dense cores, the cloud phase function as a mix of a
// strong forward lobe and a backward one, the octave loop of the multiple-scattering approximation, the aerial perspective
// blend toward the clear sky by the clouds' apparent distance, and the checkerboard + reprojected-history upscaling.
// History reprojection with Catmull-Rom sampling after Matt Pettineo (MJP), "Bicubic texture filtering with the Catmull-Rom
// spline in 9 / 5 taps" (gist TheRealMJP/c83b8c0f46b63f3a88a5986f4fa982b1), MIT License (5-tap variant, as taa.metal).
// Everything else (units in blocks, the density model, the slab on a curved world, the weather map, the cirrus layer, the
// cloud shadow map, the reflection dome, the bakes) is original; techniques after Schneider & Vos (Horizon Zero Dawn
// volumetric cloudscapes, SIGGRAPH 2015; Nubis 2017/2022) and Wrenninge et al. (multiple-scattering octaves).
//
// E05 volumetric clouds (mcopt.metal.shade.clouds.ShadeClouds, native/mcclouds.m). Loaded after ShadeMsl.prelude().
// Passes (all fragment passes into private textures; the bakes are compute, once):
//   cloud_bake_noise   (compute, once)  64^3 RGBA8 tileable 3D noise: r Perlin-Worley (billows), g/b/a Worley fBm at 3 scales
//   cloud_bake_weather (compute, once)  512^2 RGBA8 tileable 2D noise: r regional coverage, g cumulus cells, b cirrus, a detail
//   cloud_shadow       (256^2 R16F, every 4th frame) transmittance of the cumulus layer toward the light, on the layer's plane (consumers:
//                                       terrain, water, volumetric light: shadeCloudShadow)
//   cloud_march        (1/N x 1/N of the history: this frame's slot of the N x N checkerboard; N = 3 at potato/balanced,
//                                       2 above) cumulus + cirrus: scattered
//                                       light (rgb, before exposure, after aerial perspective) + transmittance, and the
//                                       clouds' apparent distance (MRT)
//   cloud_resolve      (history size)   reprojection (camera motion + wind) of last frame's history, this frame's slot blended
//                                       in, the other slots from the history (or the march upsampled where it is invalid)
//   cloud_dome         (upper hemisphere, 128x64; every 4th frame) a cheap march by direction, temporally blended: the clouds for
//                                       reflections of directions off screen (water) and future environment lighting
// The sky pass (sky.fs.metal) composites the history behind everything: sky * T + S (shade_clouds.metal).

#ifndef MCOPT_SHADE_CLOUDS_PASSES
#define MCOPT_SHADE_CLOUDS_PASSES

struct CloudParams {
	float4x4 invViewProj;
	float4x4 prevViewProj;
	float4 size;
	float4 frame;
	float4 layer;
	float4 shape;
	float4 origin;
	float4 wind;
	float4 light;
	float4 lightDir;
	float4 ambTop;
	float4 ambBottom;
	float4 march;
	float4 cirrus;
	float4 shadow;
	float4 look;
	float4 dome;
	float4 misc;
	float4 windDir;
	float4 shadowDir;
	float4 art;
	float4 shear;
};
static_assert(sizeof(CloudParams) == 448, "CloudParams layout (CloudParams.java)");

#define CLOUD_DEV_OLD_RESOLVE 1024
#define CLOUD_DEV_OLD_MARCH 2048
#define CLOUD_DEV_OLD_MIP 8192
#define CLOUD_DEV_OLD_SHADOW 1048576
constant int CLOUD_FC_DEV [[function_constant(0)]];
constant int CLOUD_FC_MODE [[function_constant(1)]];
constant float CLOUD_FC_STYLE [[function_constant(2)]];
constant bool cloudFc = is_function_constant_defined(CLOUD_FC_DEV) && is_function_constant_defined(CLOUD_FC_MODE) && is_function_constant_defined(CLOUD_FC_STYLE);
constant int cloudFcDev = cloudFc ? CLOUD_FC_DEV : 0;
constant int cloudFcMode = cloudFc ? CLOUD_FC_MODE : 0;
constant float cloudFcStyle = cloudFc ? CLOUD_FC_STYLE : 0.0;
static inline bool cloudDev(constant CloudParams &c, int bit) { return ((cloudFc ? cloudFcDev : int(c.lightDir.w)) & bit) != 0; }
static inline float cloudStyle(constant CloudParams &c) { return cloudFc ? cloudFcStyle : c.art.w; }

#define CLOUD_MODE_STABLE 1
#define CLOUD_MODE_LIGHT2 2
#define CLOUD_MODE_BATCH 4
#define CLOUD_MODE_PAIR 8
#define CLOUD_MODE_EDGE 16
#define CLOUD_MODE_ZOOM 64
#define CLOUD_MODE_DRIFT 32
static inline bool cloudMode(constant CloudParams &c, int bit) { return ((cloudFc ? cloudFcMode : int(c.shadowDir.w)) & bit) != 0; }

constexpr sampler cloudRep(filter::linear, address::repeat);
constexpr sampler cloudRepMip(filter::linear, mip_filter::nearest, address::repeat);
constexpr sampler cloudClamp(filter::linear, address::clamp_to_edge);

static inline float3 cloudGrad3(int3 c, int period, uint salt) {
	c = ((c % period) + period) % period;
	uint h = shadeHash3(c, salt);
	float3 g = float3(shadeHash01(h), shadeHash01(h ^ 0x68e31da4u), shadeHash01(h ^ 0xb5297a4du)) * 2.0 - 1.0;
	return g * rsqrt(max(dot(g, g), 1e-6));
}

static float cloudPerlin3(float3 p, int period, uint salt) {
	int3 i = int3(floor(p));
	float3 f = p - floor(p);
	float3 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
	float n000 = dot(cloudGrad3(i + int3(0, 0, 0), period, salt), f - float3(0, 0, 0));
	float n100 = dot(cloudGrad3(i + int3(1, 0, 0), period, salt), f - float3(1, 0, 0));
	float n010 = dot(cloudGrad3(i + int3(0, 1, 0), period, salt), f - float3(0, 1, 0));
	float n110 = dot(cloudGrad3(i + int3(1, 1, 0), period, salt), f - float3(1, 1, 0));
	float n001 = dot(cloudGrad3(i + int3(0, 0, 1), period, salt), f - float3(0, 0, 1));
	float n101 = dot(cloudGrad3(i + int3(1, 0, 1), period, salt), f - float3(1, 0, 1));
	float n011 = dot(cloudGrad3(i + int3(0, 1, 1), period, salt), f - float3(0, 1, 1));
	float n111 = dot(cloudGrad3(i + int3(1, 1, 1), period, salt), f - float3(1, 1, 1));
	float x0 = mix(mix(n000, n100, u.x), mix(n010, n110, u.x), u.y);
	float x1 = mix(mix(n001, n101, u.x), mix(n011, n111, u.x), u.y);
	return mix(x0, x1, u.z) * 1.15;
}

static float cloudWorley3(float3 p, int period, uint salt) {
	int3 i = int3(floor(p));
	float3 f = p - floor(p);
	float md = 9.0;
	for (int z = -1; z <= 1; z++)
		for (int y = -1; y <= 1; y++)
			for (int x = -1; x <= 1; x++) {
				int3 c = i + int3(x, y, z);
				int3 w = ((c % period) + period) % period;
				uint h = shadeHash3(w, salt);
				float3 o = float3(shadeHash01(h), shadeHash01(h ^ 0x1b56c4e9u), shadeHash01(h ^ 0x7f4a7c15u));
				float3 r = float3(x, y, z) + o - f;
				md = min(md, dot(r, r));
			}
	return sqrt(md);
}

static inline float cloudRemap(float v, float lo, float hi, float a, float b) {
	return a + (b - a) * saturate((v - lo) / max(hi - lo, 1e-5));
}

static inline float cloudUniform(float v, float mu, float sigma) {
	return 1.0 / (1.0 + exp(-1.702 * (v - mu) / sigma));
}

kernel void cloud_bake_noise(texture3d<half, access::write> out [[texture(0)]], uint3 id [[thread_position_in_grid]]) {
	const float N = 64.0;
	if (any(float3(id) >= N)) return;
	float3 p = (float3(id) + 0.5) / N;
	float pf = cloudPerlin3(p * 4.0, 4, 11u) * 0.5 + cloudPerlin3(p * 8.0, 8, 12u) * 0.3 + cloudPerlin3(p * 16.0, 16, 13u) * 0.2;
	pf = saturate(pf * 0.6 + 0.5);
	float w4 = 1.0 - saturate(cloudWorley3(p * 4.0, 4, 21u));
	float w8 = 1.0 - saturate(cloudWorley3(p * 8.0, 8, 22u));
	float w16 = 1.0 - saturate(cloudWorley3(p * 16.0, 16, 23u));
	float w32 = 1.0 - saturate(cloudWorley3(p * 32.0, 32, 24u));
	float wf0 = w4 * 0.625 + w8 * 0.25 + w16 * 0.125;
	float pw = cloudRemap(pf, wf0 - 1.0, 1.0, 0.0, 1.0);
	float g = w4 * 0.625 + w8 * 0.25 + w16 * 0.125;
	float b = w8 * 0.625 + w16 * 0.25 + w32 * 0.125;
	float a = w16 * 0.75 + w32 * 0.25;
	out.write(half4(half(cloudUniform(pw, 0.67, 0.059)), half(cloudUniform(g, 0.48, 0.118)), half(cloudUniform(b, 0.48, 0.118)),
		half(cloudUniform(a, 0.48, 0.14))), id);
}

static inline float2 cloudGrad2(int2 c, int period, uint salt) {
	c = ((c % period) + period) % period;
	uint h = shadeHash3(int3(c, 0), salt);
	float a = shadeHash01(h) * 6.2831853;
	return float2(cos(a), sin(a));
}

static float cloudPerlin2(float2 p, int period, uint salt) {
	int2 i = int2(floor(p));
	float2 f = p - floor(p);
	float2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
	float n00 = dot(cloudGrad2(i, period, salt), f);
	float n10 = dot(cloudGrad2(i + int2(1, 0), period, salt), f - float2(1, 0));
	float n01 = dot(cloudGrad2(i + int2(0, 1), period, salt), f - float2(0, 1));
	float n11 = dot(cloudGrad2(i + int2(1, 1), period, salt), f - float2(1, 1));
	return mix(mix(n00, n10, u.x), mix(n01, n11, u.x), u.y) * 1.4;
}

static float cloudFbm2(float2 p, int period, int octaves, uint salt) {
	float s = 0.0, a = 0.5, n = 0.0;
	for (int i = 0; i < octaves; i++) {
		s += cloudPerlin2(p, period, salt + uint(i)) * a;
		n += a;
		p *= 2.0;
		period *= 2;
		a *= 0.5;
	}
	return s / n;
}

static float cloudWorley2(float2 p, int period, uint salt) {
	int2 i = int2(floor(p));
	float2 f = p - floor(p);
	float md = 9.0;
	for (int y = -1; y <= 1; y++)
		for (int x = -1; x <= 1; x++) {
			int2 c = i + int2(x, y);
			int2 w = ((c % period) + period) % period;
			uint h = shadeHash3(int3(w, 1), salt);
			float2 o = float2(shadeHash01(h), shadeHash01(h ^ 0x1b56c4e9u));
			float2 r = float2(x, y) + o - f;
			md = min(md, dot(r, r));
		}
	return sqrt(md);
}

kernel void cloud_bake_weather(texture2d<half, access::write> out [[texture(0)]], uint2 id [[thread_position_in_grid]]) {
	const float N = 512.0;
	if (any(float2(id) >= N)) return;
	float2 p = (float2(id) + 0.5) / N;
	float r = cloudFbm2(p * 3.0, 3, 4, 101u);
	float pf = cloudFbm2(p * 12.0, 12, 4, 201u);
	float w = 1.0 - saturate(cloudWorley2(p * 16.0, 16, 301u));
	float w2 = 1.0 - saturate(cloudWorley2(p * 32.0, 32, 302u));
	float cells = pf + (w * 0.75 + w2 * 0.25) * 0.45;
	float b = cloudFbm2(p * 6.0, 6, 5, 401u);
	float a = cloudFbm2(p * 24.0, 24, 3, 501u);
	out.write(half4(half(cloudUniform(r * 0.5 + 0.5, 0.49, 0.104)), half(cloudUniform(cells * 0.5 + 0.25, 0.38, 0.1035)),
		half(cloudUniform(b * 0.5 + 0.5, 0.50, 0.089)), half(cloudUniform(a * 0.5 + 0.5, 0.50, 0.104))), id);
}

#define CLOUD_WEATHER_TILE 4096.0
#define CLOUD_NOISE_SCALE (1.0 / 120.0)
#define CLOUD_DETAIL_SCALE (1.0 / 60.0)
#define CLOUD_NOISE_TEXEL (120.0 / 64.0)

struct CloudRay {
	float a, b, y0;
};

static inline CloudRay cloudRay(float3 dir, constant CloudParams &c) {
	CloudRay r;
	r.a = max(c.layer.w * (1.0 - dir.y * dir.y), 1e-9);
	r.b = dir.y;
	r.y0 = c.layer.z;
	return r;
}

static inline float cloudAltitude(CloudRay r, float t) { return r.y0 + t * (r.b + t * r.a); }

static inline bool cloudRoots(float a, float b, float cc, thread float &r1, thread float &r2) {
	float disc = b * b - 4.0 * a * cc;
	if (disc < 0.0) return false;
	float q = -0.5 * (b + (b >= 0.0 ? 1.0 : -1.0) * sqrt(disc));
	float x1 = q / a, x2 = abs(q) > 1e-20 ? cc / q : x1;
	r1 = min(x1, x2);
	r2 = max(x1, x2);
	return true;
}

static bool cloudSegment(CloudRay r, float yb, float yt, float ground, float maxT, thread float &t0, thread float &t1) {
	float t1a, t2a;
	if (!cloudRoots(r.a, r.b, r.y0 - yt, t1a, t2a)) return false;
	float lo = max(t1a, 0.0), hi = t2a;
	float r1, r2;
	if (cloudRoots(r.a, r.b, r.y0 - yb, r1, r2)) {
		float s0 = lo, s1 = min(hi, r1);
		if (s1 <= s0) {
			s0 = max(lo, r2);
			s1 = hi;
		}
		lo = s0;
		hi = s1;
	}
	float g1, g2;
	if (r.y0 > ground && cloudRoots(r.a, r.b, r.y0 - ground, g1, g2) && g1 > 0.0) hi = min(hi, g1);
	hi = min(hi, maxT);
	t0 = lo;
	t1 = hi;
	return hi > lo;
}

#define CLOUD_BLOCK 12.0

static inline bool cloudBlockFilled(float2 cell, constant CloudParams &c, texture2d<half> weather) {
	if (cloudStyle(c) > 1.5) {
		float2 size = float2(weather.get_width(), weather.get_height());
		return weather.sample(cloudRep, (cell + 0.5) / size, level(0.0)).r > 0.5h;
	}
	half4 w = weather.sample(cloudRep, (cell + 0.5) * (CLOUD_BLOCK / CLOUD_WEATHER_TILE), level(0.0));
	float cov = saturate(c.shape.x + (float(w.r) - 0.5) * 0.55);
	return float(w.g) > 1.0 - cov;
}
static inline bool cloudBlockCell(float2 xz, constant CloudParams &c, texture2d<half> weather) {
	return cloudBlockFilled(floor(xz / CLOUD_BLOCK), c, weather);
}

static inline float cloudSpread(float2 rel, constant CloudParams &c) {
	return c.dome.y > 0.0 ? c.dome.y * smoothstep(400.0, 1500.0, length(rel)) : 0.0;
}
static inline float cloudBaseH(float h, half4 w, float spread) {
	float off = spread * float(w.a);
	return off > 0.0 ? (h - off) / (1.0 - off) : h;
}

static float cloudDensity(float3 p, float y, int q, constant CloudParams &c, texture2d<half> weather, texture3d<half> noise,
                          float detail = 1.0, float lod = 0.0) {
	if (cloudStyle(c) > 0.5) return y >= c.layer.x && y <= c.layer.y && cloudBlockCell(c.origin.xy + p.xz, c, weather) ? 1.0 : 0.0;
	float h = (y - c.layer.x) / (c.layer.y - c.layer.x);
	if (h <= 0.0 || h >= 1.0) return 0.0;
	float2 xz = c.origin.xy + p.xz;
	half4 w = weather.sample(cloudRep, (xz - c.shear.xy * h) * (1.0 / CLOUD_WEATHER_TILE), level(0.0));
	float spread = cloudSpread(p.xz, c);
	h = cloudBaseH(h, w, spread);
	if (h <= 0.0) return 0.0;
	float cov = saturate(c.shape.x + (float(w.r) - 0.5) * 0.55);
	float cell = float(w.g);
	float core = saturate((cell - (1.0 - cov)) / max(cov, 0.08));
	float topH = mix(0.38, 1.0, c.shape.y) * (0.45 + 0.55 * core);
	float x = h / max(topH, 0.05);
	float d = core - x * x * mix(0.9, 0.6, c.shape.y);
	d *= smoothstep(0.0, 0.04 + 0.3 * spread, h);
	if (d <= 0.0) return 0.0;
	if (q == 0) return saturate(d * 0.85);
	float3 n3 = float3(xz.x + c.origin.z - c.origin.x, y, xz.y + c.origin.w - c.origin.y);
	half4 n = cloudDev(c, CLOUD_DEV_OLD_MIP) ? noise.sample(cloudRep, n3 * CLOUD_NOISE_SCALE, level(lod))
		: noise.sample(cloudRepMip, n3 * CLOUD_NOISE_SCALE, level(lod));
	float wd = 1.0 - (float(n.g) * 0.75 + float(n.r) * 0.25);
	float thin = saturate(1.0 - d);
	thin = thin * (2.0 - thin);
	d -= c.shape.w * 0.55 * wd * wd * thin;
	if (q >= 2 && d > 0.0) {
		half4 m = cloudDev(c, CLOUD_DEV_OLD_MIP) ? noise.sample(cloudRep, n3 * CLOUD_DETAIL_SCALE, level(lod + 1.0))
			: noise.sample(cloudRepMip, n3 * CLOUD_DETAIL_SCALE, level(lod + 1.0));
		float wd2 = 1.0 - (float(m.g) * 0.6 + float(m.b) * 0.4);
		float fade = 0.6 + 0.2 * smoothstep(0.85, 1.0, 1.0 - h) - 0.35 * smoothstep(0.05, 0.5, h);
		d -= 0.55 * c.misc.w * detail * wd2 * wd2 * thin * fade;
	}
	d *= 0.6 + 0.8 * float(n.r);
	d = saturate(d);
	d = 1.0 - pow(1.0 - d, mix(3.0, 7.0, h));
	return d * (0.5 + 0.5 * smoothstep(0.0, 0.2, h));
}

static inline float cloudDensityMath(half4 w, half4 n, half4 m, float h, int q, constant CloudParams &c, float detail, float2 rel) {
	if (h <= 0.0 || h >= 1.0) return 0.0;
	float spread = cloudSpread(rel, c);
	h = cloudBaseH(h, w, spread);
	if (h <= 0.0) return 0.0;
	float cov = saturate(c.shape.x + (float(w.r) - 0.5) * 0.55);
	float cell = float(w.g);
	float core = saturate((cell - (1.0 - cov)) / max(cov, 0.08));
	float topH = mix(0.38, 1.0, c.shape.y) * (0.45 + 0.55 * core);
	float x = h / max(topH, 0.05);
	float d = core - x * x * mix(0.9, 0.6, c.shape.y);
	d *= smoothstep(0.0, 0.04 + 0.3 * spread, h);
	if (d <= 0.0) return 0.0;
	if (q == 0) return saturate(d * 0.85);
	float wd = 1.0 - (float(n.g) * 0.75 + float(n.r) * 0.25);
	float thin = saturate(1.0 - d);
	thin = thin * (2.0 - thin);
	d -= c.shape.w * 0.55 * wd * wd * thin;
	if (q >= 2 && d > 0.0) {
		float wd2 = 1.0 - (float(m.g) * 0.6 + float(m.b) * 0.4);
		float fade = 0.6 + 0.2 * smoothstep(0.85, 1.0, 1.0 - h) - 0.35 * smoothstep(0.05, 0.5, h);
		d -= 0.55 * c.misc.w * detail * wd2 * wd2 * thin * fade;
	}
	d *= 0.6 + 0.8 * float(n.r);
	d = saturate(d);
	d = 1.0 - pow(1.0 - d, mix(3.0, 7.0, h));
	return d * (0.5 + 0.5 * smoothstep(0.0, 0.2, h));
}

static inline half4 cloudWeatherAt(float2 xz, float h, constant CloudParams &c, texture2d<half> weather) {
	return weather.sample(cloudRep, (xz - c.shear.xy * h) * (1.0 / CLOUD_WEATHER_TILE), level(0.0));
}

static inline float3 cloudNoisePos(float2 xz, float y, constant CloudParams &c) {
	return float3(xz.x + c.origin.z - c.origin.x, y, xz.y + c.origin.w - c.origin.y);
}

static inline float cloudDensityB(float3 p, float y, int q, constant CloudParams &c, texture2d<half> weather, texture3d<half> noise,
                                  float detail, float lod) {
	float h = (y - c.layer.x) / (c.layer.y - c.layer.x);
	float2 xz = c.origin.xy + p.xz;
	float3 n3 = cloudNoisePos(xz, y, c);
	half4 w = cloudWeatherAt(xz, h, c, weather);
	half4 n = noise.sample(cloudRepMip, n3 * CLOUD_NOISE_SCALE, level(lod));
	half4 m = q >= 2 ? noise.sample(cloudRepMip, n3 * CLOUD_DETAIL_SCALE, level(lod + 1.0)) : half4(0.0h);
	return cloudDensityMath(w, n, m, h, q, c, detail, p.xz);
}

static float cloudLightDepthB(float3 p, float y, float jitter, int steps, constant CloudParams &c, texture2d<half> weather,
                              texture3d<half> noise, float lod, float3 pUp, float yUp, thread float &dUp) {
	float3 L = c.lightDir.xyz;
	float inv = 1.0 / (c.layer.y - c.layer.x);
	float s = 0.0, stp = c.misc.y > 0.0 ? c.misc.y : 4.0;
	float grow = steps <= 3 ? 3.5 : steps == 4 ? 2.6 : 2.0;
	float hs[4], len[4];
	float2 xz[4], rl[4];
	float yq[4];
	for (int i = 0; i < 4; i++) {
		float m = s + stp * (0.5 + 0.5 * (jitter - 0.5));
		float3 q = p + L * m;
		yq[i] = y + L.y * m;
		hs[i] = (yq[i] - c.layer.x) * inv;
		rl[i] = q.xz;
		xz[i] = c.origin.xy + q.xz;
		len[i] = stp;
		s += stp;
		stp *= grow;
	}
	float hUp = (yUp - c.layer.x) * inv;
	float2 xzUp = c.origin.xy + pUp.xz;
	half4 w0 = cloudWeatherAt(xz[0], hs[0], c, weather), w1 = cloudWeatherAt(xz[1], hs[1], c, weather);
	half4 w2 = steps > 2 ? cloudWeatherAt(xz[2], hs[2], c, weather) : half4(0.0h);
	half4 w3 = steps > 3 ? cloudWeatherAt(xz[3], hs[3], c, weather) : half4(0.0h);
	half4 wUp = cloudWeatherAt(xzUp, hUp, c, weather);
	half4 n0 = noise.sample(cloudRepMip, cloudNoisePos(xz[0], yq[0], c) * CLOUD_NOISE_SCALE, level(lod));
	half4 n1 = noise.sample(cloudRepMip, cloudNoisePos(xz[1], yq[1], c) * CLOUD_NOISE_SCALE, level(lod + 1.0));
	float tau = cloudDensityMath(w0, n0, n0, hs[0], 1, c, 1.0, rl[0]) * len[0];
	if (steps > 1) tau += cloudDensityMath(w1, n1, n1, hs[1], 1, c, 1.0, rl[1]) * len[1];
	if (steps > 2) tau += cloudDensityMath(w2, w2, w2, hs[2], 0, c, 1.0, rl[2]) * len[2];
	if (steps > 3) tau += cloudDensityMath(w3, w3, w3, hs[3], 0, c, 1.0, rl[3]) * len[3];
	dUp = cloudDensityMath(wUp, wUp, wUp, hUp, 0, c, 1.0, pUp.xz);
	return tau * c.shape.z;
}

static float cloudLightDepth(float3 p, float y, float jitter, int steps, constant CloudParams &c, texture2d<half> weather, texture3d<half> noise,
                             float lod) {
	float3 L = c.lightDir.xyz;
	float tau = 0.0, s = 0.0, step = c.misc.y > 0.0 ? c.misc.y : 4.0;
	float grow = steps <= 3 ? 3.5 : steps == 4 ? 2.6 : 2.0;
	for (int i = 0; i < steps; i++) {
		float m = s + step * (0.5 + 0.5 * (jitter - 0.5));
		float3 q = p + L * m;
		tau += cloudDensity(q, y + L.y * m, i < 2 ? 1 : 0, c, weather, noise, 1.0, lod + float(i)) * step;
		s += step;
		step *= grow;
	}
	return tau * c.shape.z;
}

static inline float cloudHG(float mu, float g) {
	float g2 = g * g;
	float x = max(1.0 + g2 - 2.0 * g * mu, 1e-4);
	return 0.0795775 * (1.0 - g2) / (x * sqrt(x));
}

static inline float cloudPhase(float mu, float k, constant CloudParams &c) {
	float g = c.look.z * k;
	return 0.7 * cloudHG(mu, g) + c.look.y * k * cloudHG(mu, 0.97) + 0.3 * cloudHG(mu, -0.25 * k);
}

struct CloudResult {
	float3 S;
	float T;
	float dist;
};

static inline float3 cloudSampleLight(float tauL, float dUp, float d, float st, float h, float y, float mu, float pw, float ph0, float ph1,
                                      float ph2, float ph3, constant CloudParams &c) {
	float sigma = c.shape.z;
	float above = c.layer.y - y;
	float tauUp = (dUp * 0.7 + d * 0.3) * sigma * above * c.art.z;
	float tauDown = d * sigma * (y - c.layer.x) * c.art.z * 0.75;
	float a1 = c.art.x, b1 = c.art.y;
	float ms = exp(-tauL) * ph0 + a1 * exp(-b1 * tauL) * ph1 + a1 * a1 * exp(-b1 * b1 * tauL) * ph2
		+ a1 * a1 * a1 * exp(-b1 * b1 * b1 * tauL) * ph3;
	float powder = 1.0 - pw * exp(-st * 28.0);
	ms *= mix(powder, 1.0, saturate(mu * 1.5));
	float3 direct = c.light.rgb * ms;
	float3 amb = (c.ambTop.rgb * exp(-tauUp) * (0.55 + 0.45 * h) + c.ambBottom.rgb * exp(-tauDown)) * c.ambBottom.w;
	return direct + amb;
}

static CloudResult cloudMarch(float3 dir, int steps, int lsteps, int q, float jitter, constant CloudParams &c, texture2d<half> weather,
                              texture3d<half> noise, float foot) {
	CloudResult o;
	o.S = 0.0;
	o.T = 1.0;
	o.dist = -1.0;
	CloudRay r = cloudRay(dir, c);
	float t0, t1;
	if (!cloudSegment(r, c.layer.x, c.layer.y, c.misc.z, c.march.z, t0, t1)) return o;
	bool inside = t0 < 1.0;
	if (inside) t1 = min(t1, 1500.0);
	float len = t1 - t0;
	int n = clamp(int(max(float(steps) * saturate(len / 600.0), len / 48.0) + 0.999), max(steps / 2, 4), 3 * steps);
	float dt = len / float(n);
	float detail = saturate((26.0 - dt) / 14.0);
	if (detail <= 0.0) q = min(q, 1);
	float sigma = c.shape.z;
	float albedo = c.ambTop.w;
	float3 L = c.lightDir.xyz;
	float mu = dot(dir, L);
	float pw = c.look.x;
	float ph0 = cloudPhase(mu, 1.0, c), ph1 = cloudPhase(mu, 0.5, c), ph2 = cloudPhase(mu, 0.25, c), ph3 = cloudPhase(mu, 0.125, c);
	float3 Sacc = 0.0;
	float T = 1.0, dsum = 0.0, wsum = 0.0;
	float thick = c.layer.y - c.layer.x;
	bool fine = false;
	int miss = 0;
	bool light2 = cloudMode(c, CLOUD_MODE_LIGHT2), haveL = false, odd = false;
	bool batch = cloudMode(c, CLOUD_MODE_BATCH) && !cloudDev(c, CLOUD_DEV_OLD_MIP) && lsteps <= 4;
	float lastTauL = 0.0, lastUp = 0.0, pw0 = 0.0, pd = 0.0, pst = 0.0, ph = 0.0, py = 0.0;
	float dtl = inside ? dt * 0.08 : dt;
	float t = t0 + jitter * dtl;
	int budget = 3 * n + 4 + (inside ? 24 : 0);
	bool pair = cloudMode(c, CLOUD_MODE_PAIR) && !inside;
	for (int i = 0; i < budget && t < t1; i++) {
		if (inside) dtl = dt * clamp((t + 24.0) / 400.0, 0.08, 1.0);
		float3 p = dir * t;
		float y = cloudAltitude(r, t);
		if (!fine && pair) {
			float tB = t + 2.0 * dtl;
			float3 pB = dir * tB;
			float yB = cloudAltitude(r, tB);
			float inv = 1.0 / (c.layer.y - c.layer.x);
			float hA = (y - c.layer.x) * inv, hB = (yB - c.layer.x) * inv;
			half4 wA = cloudWeatherAt(c.origin.xy + p.xz, hA, c, weather), wB = cloudWeatherAt(c.origin.xy + pB.xz, hB, c, weather);
			bool useB = i + 1 < budget && tB < t1;
			if (cloudDensityMath(wA, wA, wA, hA, 0, c, 1.0, p.xz) > 0.0) {
				fine = true;
				miss = 0;
				t = max(t - dtl, t0 + jitter * dtl);
				continue;
			}
			t = tB;
			if (!useB) continue;
			i++;
			if (cloudDensityMath(wB, wB, wB, hB, 0, c, 1.0, pB.xz) > 0.0) {
				fine = true;
				miss = 0;
				t = max(tB - dtl, t0 + jitter * dtl);
				continue;
			}
			t = tB + 2.0 * dtl;
			continue;
		}
		if (!fine) {
			if (cloudDensity(p, y, 0, c, weather, noise) <= 0.0) {
				t += 2.0 * dtl;
				continue;
			}
			fine = true;
			miss = 0;
			t = max(t - dtl, t0 + jitter * dtl);
			continue;
		}
		float lod = cloudDev(c, CLOUD_DEV_OLD_MIP) ? max(log2(max(t * foot, 1e-3) / CLOUD_NOISE_TEXEL), 0.0)
			: max(log2(max(t * foot, 1e-3) / CLOUD_NOISE_TEXEL), 0.0) + fract(jitter * 3.17 + float(i) * 0.7548777) - 0.5;
		float above = c.layer.y - y;
		bool needL = !(light2 && haveL && !odd);
		float d, tauL = 0.0, dUp = 0.0;
		if (batch && needL) {
			d = cloudDensityB(p, y, T > 0.25 ? q : 1, c, weather, noise, detail, lod);
			tauL = cloudLightDepthB(p, y, fract(jitter + float(i) * 0.618034), lsteps, c, weather, noise, lod, p + float3(0.0, above * 0.4, 0.0),
				y + above * 0.4, dUp);
		} else if (batch) {
			d = cloudDensityB(p, y, T > 0.25 ? q : 1, c, weather, noise, detail, lod);
		} else {
			d = cloudDensity(p, y, T > 0.25 ? q : 1, c, weather, noise, detail, lod);
		}
		if (d <= 0.002) {
			if (++miss >= 4) fine = false;
			t += dtl;
			continue;
		}
		miss = 0;
		float st = d * sigma;
		float ds = clamp(c.shear.z / max(st, 1e-4), dtl * c.shear.w, dtl);
		float stepT = exp(-st * ds);
		float h = saturate((y - c.layer.x) / thick);
		float wseg = T * (1.0 - stepT);
		dsum += t * wseg;
		wsum += wseg;
		T *= stepT;
		if (!needL) {
			odd = true;
			pw0 = wseg;
			pd = d;
			pst = st;
			ph = h;
			py = y;
			if (T < 0.02) break;
			t += ds;
			continue;
		}
		if (!batch) {
			tauL = cloudLightDepth(p, y, fract(jitter + float(i) * 0.618034), lsteps, c, weather, noise, lod);
			dUp = cloudDensity(p + float3(0.0, above * 0.4, 0.0), y + above * 0.4, 0, c, weather, noise);
		}
		if (odd) {
			Sacc += (albedo * pst * cloudSampleLight(0.5 * (lastTauL + tauL), 0.5 * (lastUp + dUp), pd, pst, ph, py, mu, pw, ph0, ph1, ph2, ph3, c))
				* (pw0 / max(pst, 1e-6));
			odd = false;
		}
		lastTauL = tauL;
		lastUp = dUp;
		haveL = true;
		float3 Sstep = albedo * st * cloudSampleLight(tauL, dUp, d, st, h, y, mu, pw, ph0, ph1, ph2, ph3, c);
		Sacc += Sstep * (wseg / max(st, 1e-6));
		if (T < 0.02) break;
		t += ds;
	}
	if (odd) Sacc += (albedo * pst * cloudSampleLight(lastTauL, lastUp, pd, pst, ph, py, mu, pw, ph0, ph1, ph2, ph3, c)) * (pw0 / max(pst, 1e-6));
	o.S = Sacc;
	o.T = saturate((T - 0.02) / 0.98);
	o.dist = wsum > 1e-5 ? dsum / wsum : t0;
	return o;
}

static CloudResult cloudCirrus(float3 dir, constant CloudParams &c, texture2d<half> weather) {
	CloudResult o;
	o.S = 0.0;
	o.T = 1.0;
	o.dist = -1.0;
	if (c.wind.z <= 0.001) return o;
	CloudRay r = cloudRay(dir, c);
	float r1, r2;
	if (!cloudRoots(r.a, r.b, r.y0 - c.cirrus.x, r1, r2)) return o;
	float t = r1 > 0.0 ? r1 : r2;
	if (t <= 0.0 || t > c.march.z * 1.6) return o;
	float2 rel = dir.xz * t;
	float2 wd = c.windDir.xy, wn = float2(-wd.y, wd.x);
	float2 s = c.cirrus.zw + float2(dot(rel, wd), dot(rel, wn));
	half4 w0 = weather.sample(cloudRep, s * float2(1.0 / 16384.0, 1.0 / 4096.0), level(0.0));
	float2 warp = (float2(w0.a, w0.r) - 0.5) * 0.06;
	half4 w1 = weather.sample(cloudRep, s * float2(1.0 / 8192.0, 1.0 / 2048.0) + warp, level(0.0));
	half4 w2 = weather.sample(cloudRep, s * float2(1.0 / 4096.0, 1.0 / 1024.0) + warp * 1.5, level(0.0));
	float n = float(w0.b) * 0.45 + float(w1.b) * 0.4 + float(w2.a) * 0.15;
	float d = cloudRemap(n, 1.0 - c.wind.z * 0.6, 1.0, 0.0, 1.0);
	d *= 0.55 + 0.45 * float(w2.b);
	d = d * d * (3.0 - 2.0 * d);
	if (d <= 0.0) return o;
	float tau = d * c.cirrus.y;
	float T = exp(-tau);
	float mu = dot(dir, c.lightDir.xyz);
	float phase = 0.6 * cloudHG(mu, 0.75) + 0.4 * cloudHG(mu, 0.2);
	float3 S = (c.light.rgb * phase * 3.0 + c.ambTop.rgb * 0.9) * (1.0 - T) * 0.9;
	o.S = S;
	o.T = T;
	o.dist = t;
	return o;
}

struct CloudCirrusPre {
	float t;
	float2 s;
	half4 w0;
};

static inline CloudCirrusPre cloudCirrusBegin(float3 dir, constant CloudParams &c, texture2d<half> weather) {
	CloudCirrusPre o;
	o.t = 0.0;
	o.s = 0.0;
	o.w0 = half4(0.0h);
	if (c.wind.z <= 0.001) return o;
	CloudRay r = cloudRay(dir, c);
	float r1, r2;
	if (!cloudRoots(r.a, r.b, r.y0 - c.cirrus.x, r1, r2)) return o;
	float t = r1 > 0.0 ? r1 : r2;
	if (t <= 0.0 || t > c.march.z * 1.6) return o;
	float2 rel = dir.xz * t;
	float2 wd = c.windDir.xy, wn = float2(-wd.y, wd.x);
	o.s = c.cirrus.zw + float2(dot(rel, wd), dot(rel, wn));
	o.w0 = weather.sample(cloudRep, o.s * float2(1.0 / 16384.0, 1.0 / 4096.0), level(0.0));
	o.t = t;
	return o;
}

static inline CloudResult cloudCirrusEnd(CloudCirrusPre pre, float3 dir, constant CloudParams &c, texture2d<half> weather) {
	CloudResult o;
	o.S = 0.0;
	o.T = 1.0;
	o.dist = -1.0;
	if (pre.t <= 0.0) return o;
	float2 s = pre.s;
	half4 w0 = pre.w0;
	float2 warp = (float2(w0.a, w0.r) - 0.5) * 0.06;
	half4 w1 = weather.sample(cloudRep, s * float2(1.0 / 8192.0, 1.0 / 2048.0) + warp, level(0.0));
	half4 w2 = weather.sample(cloudRep, s * float2(1.0 / 4096.0, 1.0 / 1024.0) + warp * 1.5, level(0.0));
	float n = float(w0.b) * 0.45 + float(w1.b) * 0.4 + float(w2.a) * 0.15;
	float d = cloudRemap(n, 1.0 - c.wind.z * 0.6, 1.0, 0.0, 1.0);
	d *= 0.55 + 0.45 * float(w2.b);
	d = d * d * (3.0 - 2.0 * d);
	if (d <= 0.0) return o;
	float tau = d * c.cirrus.y;
	float T = exp(-tau);
	float mu = dot(dir, c.lightDir.xyz);
	float phase = 0.6 * cloudHG(mu, 0.75) + 0.4 * cloudHG(mu, 0.2);
	float3 S = (c.light.rgb * phase * 3.0 + c.ambTop.rgb * 0.9) * (1.0 - T) * 0.9;
	o.S = S;
	o.T = T;
	o.dist = pre.t;
	return o;
}

static CloudResult cloudBlocky(float3 dir, constant CloudParams &c, texture2d<half> weather) {
	CloudResult o;
	o.S = 0.0;
	o.T = 1.0;
	o.dist = -1.0;
	float y0 = c.layer.z, yb = c.layer.x, yt = c.layer.y;
	float maxT = min(c.march.z, 4000.0);
	float t0, t1;
	if (abs(dir.y) < 1e-5) {
		if (y0 < yb || y0 > yt) return o;
		t0 = 0.0;
		t1 = maxT;
	} else {
		float ta = (yb - y0) / dir.y, tb = (yt - y0) / dir.y;
		t0 = max(min(ta, tb), 0.0);
		t1 = min(max(ta, tb), maxT);
	}
	if (t1 <= t0) return o;
	float2 q0 = c.origin.xy + dir.xz * t0;
	float2 d = dir.xz;
	float2 cell = floor(q0 / CLOUD_BLOCK);
	float2 st = float2(d.x >= 0.0 ? 1.0 : -1.0, d.y >= 0.0 ? 1.0 : -1.0);
	float2 ad = max(abs(d), float2(1e-6));
	float2 tDelta = CLOUD_BLOCK / ad;
	float2 next = ((cell + max(st, 0.0)) * CLOUD_BLOCK - q0) / (st * ad);
	float t = t0;
	int axis = -1;
	bool hit = false;
	for (int i = 0; i < 160; i++) {
		if (cloudBlockFilled(cell, c, weather)) {
			hit = true;
			break;
		}
		if (next.x < next.y) {
			t = t0 + next.x;
			next.x += tDelta.x;
			cell.x += st.x;
			axis = 0;
		} else {
			t = t0 + next.y;
			next.y += tDelta.y;
			cell.y += st.y;
			axis = 1;
		}
		if (t >= t1) break;
	}
	if (!hit) return o;
	float3 n = axis == 0 ? float3(-st.x, 0.0, 0.0) : axis == 1 ? float3(0.0, 0.0, -st.y)
		: (t0 > 0.0 ? float3(0.0, dir.y > 0.0 ? -1.0 : 1.0, 0.0) : -dir);
	float3 L = c.lightDir.xyz;
	float3 ph = dir * t;
	float yh = y0 + dir.y * t;
	float vis = 1.0;
	for (int k = 1; k <= 3; k++) {
		float3 pk = ph + n * 0.5 + L * (float(k) * 7.0);
		float yk = yh + n.y * 0.5 + L.y * float(k) * 7.0;
		if (yk >= yb && yk <= yt && cloudBlockCell(c.origin.xy + pk.xz, c, weather)) vis *= 0.45;
	}
	float ndl = dot(n, L);
	float wrap = saturate((ndl + 0.35) / 1.35);
	float mu = dot(dir, L);
	float rim = (1.0 - abs(dot(n, dir))) * cloudHG(mu, 0.75) * 0.9;
	float3 amb = mix(c.ambBottom.rgb * 1.4 + c.ambTop.rgb * 0.5, c.ambTop.rgb * 1.25, n.y * 0.5 + 0.5) + c.light.rgb * 0.035;
	float albedo = c.ambTop.w;
	o.S = albedo * (c.light.rgb * (wrap * vis * 0.3183 + rim * vis) + amb) * 0.92;
	o.T = 0.08;
	o.dist = t;
	return o;
}

static inline CloudResult cloudAerial(CloudResult r, float3 sky, float fade, constant CloudParams &c) {
	if (r.dist < 0.0) return r;
	float Tair = exp(-r.dist / c.march.w) * fade * (1.0 - smoothstep(0.55 * c.march.z, c.march.z, r.dist));
	r.S = mix((1.0 - r.T) * sky, r.S, Tair);
	return r;
}

static inline float cloudHorizonFade(float3 dir, constant CloudParams &c) {
	float clear = c.layer.z - c.misc.z;
	if (dir.y >= 0.0 || clear <= 1.0) return 1.0;
	CloudRay r = cloudRay(dir, c);
	float k = saturate(1.0 - r.b * r.b / (4.0 * r.a * clear));
	return mix(smoothstep(0.0, 0.9, k), 1.0, smoothstep(c.layer.x - 24.0, c.layer.x, c.layer.z));
}

static CloudResult cloudSky(float3 dir, int steps, int lsteps, int q, float jitter, constant ShadeFrame &f, constant CloudParams &c,
                            texture2d<half> weather, texture3d<half> noise, texture2d<half> lut, sampler lutS, float foot) {
	CloudResult ci, cu;
	bool batch = cloudMode(c, CLOUD_MODE_BATCH);
	float3 skyPre = 0.0;
	if (batch) {
		CloudCirrusPre pre = cloudCirrusBegin(dir, c, weather);
		CloudRay rs = cloudRay(dir, c);
		float s0, s1;
		if (cloudStyle(c) > 0.5 || pre.t > 0.0 || cloudDev(c, CLOUD_DEV_OLD_MARCH) || cloudSegment(rs, c.layer.x, c.layer.y, c.misc.z, c.march.z, s0, s1))
			skyPre = shadeSkyLookup(dir, lut, lutS, f);
		cu = cloudStyle(c) > 0.5 ? cloudBlocky(dir, c, weather) : cloudMarch(dir, steps, lsteps, q, jitter, c, weather, noise, foot);
		ci = cloudCirrusEnd(pre, dir, c, weather);
	} else {
		cu = cloudStyle(c) > 0.5 ? cloudBlocky(dir, c, weather) : cloudMarch(dir, steps, lsteps, q, jitter, c, weather, noise, foot);
		ci = cloudCirrus(dir, c, weather);
	}
	if (cu.dist >= 0.0 || ci.dist >= 0.0 || cloudDev(c, CLOUD_DEV_OLD_MARCH)) {
		float3 sky = batch ? skyPre : shadeSkyLookup(dir, lut, lutS, f);
		float hf = cloudHorizonFade(dir, c);
		cu = cloudAerial(cu, sky, hf, c);
		ci = cloudAerial(ci, sky, hf, c);
	}
	CloudResult o;
	o.S = cu.S + cu.T * ci.S;
	o.T = cu.T * ci.T;
	o.dist = cu.dist >= 0.0 ? cu.dist : ci.dist;
	float fade = c.look.w;
	o.S *= fade;
	o.T = mix(1.0, o.T, fade);
	return o;
}

static inline float3 cloudDirection(float2 uv, constant CloudParams &c) {
	return normalize(shadeViewRay(uv * 2.0 - 1.0, c.invViewProj));
}

struct CloudVsOut { float4 position [[position]]; };

vertex CloudVsOut cloud_vs(uint vid [[vertex_id]]) {
	CloudVsOut o;
	o.position = float4(vid == 1 ? 3.0 : -1.0, vid == 2 ? 3.0 : -1.0, 0.0, 1.0);
	return o;
}

struct CloudMarchOut {
	half4 color [[color(0)]];
	float dist [[color(1)]];
};

fragment CloudMarchOut cloud_march(CloudVsOut in [[stage_in]], constant ShadeFrame &f [[buffer(16)]], constant CloudParams &c [[buffer(0)]],
                                   texture2d<half> weather [[texture(0)]], texture3d<half> noise [[texture(1)]],
                                   texture2d<half> lut [[texture(17)]], sampler lutS [[sampler(13)]]) {
	float n = c.windDir.z;
	float2 hp = min(floor(in.position.xy) * n + c.frame.xy, c.size.xy - 1.0);
	float2 uv = (hp + 0.5) / c.size.xy;
	float3 dir = cloudDirection(uv, c);
	float visit = floor(c.misc.x / (n * n));
	float seed = cloudMode(c, CLOUD_MODE_STABLE) ? shadeHash01(shadeHash3(int3(int2(hp), 0), 0x2c1b3c6du)) : shadeNoise(hp, 0);
	float jitter = fract(seed + visit * 0.6180339887);
	CloudResult r = cloudSky(dir, int(c.march.x), int(c.march.y), 2, jitter, f, c, weather, noise, lut, lutS, c.windDir.w);
	CloudMarchOut o;
	o.color = half4(half3(min(r.S, 60000.0)), half(r.T));
	float dist = r.dist;
	if (dist < 0.0) {
		CloudRay ray = cloudRay(dir, c);
		float t0, t1;
		dist = cloudSegment(ray, c.layer.x, c.layer.y, c.misc.z, c.march.z, t0, t1) ? 0.5 * (t0 + t1) : c.march.z;
	}
	o.dist = dist;
	return o;
}

static inline half4 cloudCatmullRom(texture2d<half> tex, float2 uv, float2 size) {
	float2 pos = uv * size;
	float2 t1 = floor(pos - 0.5) + 0.5;
	float2 f = pos - t1;
	float2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
	float2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
	float2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
	float2 w3 = f * f * (-0.5 + 0.5 * f);
	float2 w12 = w1 + w2;
	float2 inv = 1.0 / size;
	float2 t0 = (t1 - 1.0) * inv, t3 = (t1 + 2.0) * inv, t12 = (t1 + w2 / w12) * inv;
	float4 c = float4(tex.sample(cloudClamp, float2(t12.x, t0.y))) * (w12.x * w0.y)
		+ float4(tex.sample(cloudClamp, float2(t0.x, t12.y))) * (w0.x * w12.y)
		+ float4(tex.sample(cloudClamp, t12)) * (w12.x * w12.y)
		+ float4(tex.sample(cloudClamp, float2(t3.x, t12.y))) * (w3.x * w12.y)
		+ float4(tex.sample(cloudClamp, float2(t12.x, t3.y))) * (w12.x * w3.y);
	float wsum = w12.x * w0.y + w0.x * w12.y + w12.x * w12.y + w3.x * w12.y + w12.x * w3.y;
	c /= wsum;
	return half4(half3(max(c.rgb, 0.0)), half(saturate(c.a)));
}

struct CloudResolveOut {
	half4 color [[color(0)]];
	half age [[color(1)]];
};

fragment CloudResolveOut cloud_resolve(CloudVsOut in [[stage_in]], constant CloudParams &c [[buffer(0)]],
                                       texture2d<half> cur [[texture(0)]], texture2d<float> curDist [[texture(1)]],
                                       texture2d<half> hist [[texture(2)]], texture2d<half> histAge [[texture(3)]]) {
	uint n = uint(c.windDir.z);
	uint2 px = uint2(in.position.xy);
	uint2 slot = uint2(c.frame.xy);
	uint2 mp = min(px / n, uint2(c.size.zw) - 1u);
	bool mine = all(px % n == slot);
	float2 uv = (float2(px) + 0.5) / c.size.xy;
	float3 dir = cloudDirection(uv, c);
	half4 now = cur.read(mp);
	bool oldTaps = cloudDev(c, CLOUD_DEV_OLD_RESOLVE);
	constexpr sampler gatherS(filter::nearest, address::clamp_to_edge);
	float2 msz = c.size.zw;
	float4 g0 = oldTaps ? float4(0.0) : curDist.gather(gatherS, float2(mp) / msz);
	float dNow = oldTaps ? curDist.read(mp).r : g0.y;
	float3 wp = shadeEye(c.invViewProj) + dir * dNow + float3(c.wind.x, 0.0, c.wind.y);
	float4 pc = c.prevViewProj * float4(wp, 1.0);
	float2 puv = pc.xy / max(pc.w, 1e-6) * 0.5 + 0.5;
	bool valid = c.frame.z < 0.5 && pc.w > 1e-4 && all(puv >= 0.0) && all(puv <= 1.0);
	half4 h = half4(0.0h, 0.0h, 0.0h, 1.0h);
	float age = 0.0;
	if (valid) {
		h = cloudCatmullRom(hist, puv, c.size.xy);
		age = float(histAge.sample(cloudClamp, puv).r) * 64.0;
		int2 mi = int2(mp), ms = int2(c.size.zw) - 1;
		int2 ax = int2(min(mi.x + 1, ms.x), mi.y), bx = int2(max(mi.x - 1, 0), mi.y);
		int2 ay = int2(mi.x, min(mi.y + 1, ms.y)), by = int2(mi.x, max(mi.y - 1, 0));
		float d1, d2, d3, d4;
		if (oldTaps) {
			d1 = curDist.read(uint2(ax)).r, d2 = curDist.read(uint2(bx)).r, d3 = curDist.read(uint2(ay)).r, d4 = curDist.read(uint2(by)).r;
		} else {
			float4 g1 = curDist.gather(gatherS, (float2(mp) + 1.0) / msz);
			d1 = g1.z;
			d2 = g0.x;
			d3 = g1.x;
			d4 = g0.z;
		}
		float dMin = min(min(min(d1, d2), min(d3, d4)), dNow), dMax = max(max(max(d1, d2), max(d3, d4)), dNow);
		if (dMax > dMin * 1.3 + 4.0) {
			half4 c1 = cur.read(uint2(ax)), c2 = cur.read(uint2(bx)), c3 = cur.read(uint2(ay)), c4 = cur.read(uint2(by));
			half4 lo = min(min(min(c1, c2), min(c3, c4)), now), hi = max(max(max(c1, c2), max(c3, c4)), now);
			half4 pad = (hi - lo) * 0.15h;
			h = clamp(h, lo - pad, hi + pad);
		}
	}
	CloudResolveOut o;
	if (mine) {
		float w = valid ? 1.0 / min(age + 1.0, c.frame.w) : 1.0;
		o.color = valid ? mix(h, now, half(w)) : now;
		o.age = half(min(age + 1.0, c.frame.w) / 64.0);
	} else if (valid && age > 0.5) {
		o.color = h;
		o.age = half(age / 64.0);
	} else {
		float2 mpos = (float2(px) + 0.5 - float2(slot) - 0.5) / float(n) + 0.5;
		o.color = cur.sample(cloudClamp, mpos / c.size.zw);
		o.age = 0.0h;
	}
	if (shadeBad(o.color)) o.color = half4(0.0h, 0.0h, 0.0h, 1.0h);
	return o;
}

#define CLOUD_DIST_NONE 4095u
static inline uint cloudDistCode(float d) { return uint(clamp(log2(max(d, 1.0)) * 256.0 + 0.5, 0.0, 4094.0)); }
static inline float cloudDistDecode(uint q) { return exp2(float(q) * (1.0 / 256.0)); }

struct CloudResolveStOut {
	half4 color [[color(0)]];
	ushort data [[color(1)]];
};

fragment CloudResolveStOut cloud_resolve_st(CloudVsOut in [[stage_in]], constant CloudParams &c [[buffer(0)]],
                                            texture2d<half> cur [[texture(0)]], texture2d<float> curDist [[texture(1)]],
                                            texture2d<half> hist [[texture(2)]], texture2d<ushort> histData [[texture(3)]]) {
	uint n = uint(c.windDir.z);
	uint2 px = uint2(in.position.xy);
	uint2 slot = uint2(c.frame.xy);
	uint2 mp = min(px / n, uint2(c.size.zw) - 1u);
	bool mine = all(px % n == slot);
	float2 uv = (float2(px) + 0.5) / c.size.xy;
	float3 dir = cloudDirection(uv, c);
	half4 now = cur.read(mp);
	constexpr sampler gatherS(filter::nearest, address::clamp_to_edge);
	float2 msz = c.size.zw;
	float4 g0 = curDist.gather(gatherS, float2(mp) / msz);
	uint4 qu = uint4(histData.gather(gatherS, uv)) & 0xfffu;
	uint qMin = min(min(qu.x, qu.y), min(qu.z, qu.w));
	float dNow = max(g0.y, 1.0);
	float3 wnd = float3(c.wind.x, 0.0, c.wind.y);
	float dRep = qMin != CLOUD_DIST_NONE ? cloudDistDecode(qMin) : dNow;
	float4 pc = c.prevViewProj * float4(shadeEye(c.invViewProj) + dir * dRep + wnd, 1.0);
	float2 puv = pc.xy / max(pc.w, 1e-6) * 0.5 + 0.5;
	bool valid = c.frame.z < 0.5 && pc.w > 1e-4 && all(puv >= 0.0) && all(puv <= 1.0);
	half4 h = half4(0.0h, 0.0h, 0.0h, 1.0h);
	float age = 0.0, dOwn = dNow;
	uint qOwn = CLOUD_DIST_NONE;
	bool ownCloud = false;
	float drift = 0.0;
	if (valid && cloudMode(c, CLOUD_MODE_DRIFT)) {
		float4 pd = c.prevViewProj * float4(dir, 0.0);
		float2 sep = (puv - (pd.xy / max(pd.w, 1e-6) * 0.5 + 0.5)) * c.size.xy;
		float nearW = 1.0 - smoothstep(32.0, 128.0, dRep);
		float glowW = smoothstep(0.85, 0.97, dot(dir, c.lightDir.xyz));
		drift = smoothstep(0.5, 1.5, length(sep)) * max(nearW, glowW);
	}
	if (valid) {
		uint4 q = uint4(histData.gather(gatherS, puv));
		float2 f = fract(puv * c.size.xy - 0.5);
		float4 ages = float4(q >> 12u);
		age = mix(mix(ages.w, ages.z, f.x), mix(ages.x, ages.y, f.x), f.y);
		if (cloudMode(c, CLOUD_MODE_ZOOM)) {
			float3 dirR = cloudDirection(uv + float2(1.0 / c.size.x, 0.0), c);
			float4 a0 = c.prevViewProj * float4(dir, 0.0), a1 = c.prevViewProj * float4(dirR, 0.0);
			float sz = a0.w > 1e-4 && a1.w > 1e-4 ? length((a1.xy / a1.w - a0.xy / a0.w) * 0.5 * c.size.xy) : 1.0;
			age *= min(1.0, sz * sz);
		}
		uint4 qd = q & 0xfffu;
		qOwn = f.y < 0.5 ? (f.x < 0.5 ? qd.w : qd.z) : (f.x < 0.5 ? qd.x : qd.y);
		ownCloud = qOwn != CLOUD_DIST_NONE;
		if (ownCloud) dOwn = cloudDistDecode(qOwn);
		h = cloudCatmullRom(hist, puv, c.size.xy);
		int2 mi = int2(mp), ms = int2(c.size.zw) - 1;
		int2 ax = int2(min(mi.x + 1, ms.x), mi.y), bx = int2(max(mi.x - 1, 0), mi.y);
		int2 ay = int2(mi.x, min(mi.y + 1, ms.y)), by = int2(mi.x, max(mi.y - 1, 0));
		float4 g1 = curDist.gather(gatherS, (float2(mp) + 1.0) / msz);
		float d1 = g1.z, d2 = g0.x, d3 = g1.x, d4 = g0.z;
		float dMin = min(min(min(d1, d2), min(d3, d4)), g0.y), dMax = max(max(max(d1, d2), max(d3, d4)), g0.y);
		bool jump = dMax > dMin * 1.3 + 4.0;
		if (jump && cloudMode(c, CLOUD_MODE_EDGE)) {
			float4 A = c.prevViewProj * float4(dir, 0.0), B = pc - dRep * A;
			float4 pa = dMin * A + B, pb = dMax * A + B;
			float2 sep = (pa.xy / max(pa.w, 1e-6) - pb.xy / max(pb.w, 1e-6)) * 0.5 * c.size.xy;
			jump = dot(sep, sep) > 0.25;
		}
		if (jump || drift > 0.0) {
			half4 c1 = cur.read(uint2(ax)), c2 = cur.read(uint2(bx)), c3 = cur.read(uint2(ay)), c4 = cur.read(uint2(by));
			half4 lo = min(min(min(c1, c2), min(c3, c4)), now), hi = max(max(max(c1, c2), max(c3, c4)), now);
			half4 pad = (hi - lo) * 0.15h;
			h = jump ? clamp(h, lo - pad, hi + pad) : mix(h, clamp(h, lo - pad, hi + pad), half(drift));
		}
		age = min(age, mix(min(c.frame.w, 15.0), 1.0, drift));
	}
	const half clear = 0.995h;
	bool histCloud = valid && age > 0.5 && ownCloud && h.a < clear;
	CloudResolveStOut o;
	uint ageOut, qOut;
	float cap = mix(min(c.frame.w, 15.0), 2.0, drift);
	if (mine) {
		float w = valid ? 1.0 / min(age + 1.0, cap) : 1.0;
		o.color = valid ? mix(h, now, half(w)) : now;
		ageOut = uint(min(age + 1.0, cap) + 0.5);
		bool nowCloud = now.a < clear;
		qOut = nowCloud ? cloudDistCode(histCloud ? 1.0 / mix(1.0 / dOwn, 1.0 / dNow, w) : dNow) : histCloud ? qOwn : CLOUD_DIST_NONE;
	} else if (valid && age > 0.5) {
		o.color = h;
		if (age < 2.5) {
			float2 mpos = (float2(px) + 0.5 - float2(slot) - 0.5) / float(n) + 0.5;
			o.color = mix(h, cur.sample(cloudClamp, mpos / c.size.zw), half(0.5 / (age + 1.0)));
		}
		ageOut = uint(age + 0.5);
		qOut = histCloud ? qOwn : CLOUD_DIST_NONE;
	} else {
		float2 mpos = (float2(px) + 0.5 - float2(slot) - 0.5) / float(n) + 0.5;
		o.color = cur.sample(cloudClamp, mpos / c.size.zw);
		ageOut = 0u;
		qOut = o.color.a < clear ? cloudDistCode(dNow) : CLOUD_DIST_NONE;
	}
	if (shadeBad(o.color)) {
		o.color = half4(0.0h, 0.0h, 0.0h, 1.0h);
		qOut = CLOUD_DIST_NONE;
	}
	o.data = ushort(min(ageOut, 15u) << 12u | qOut);
	return o;
}

fragment half4 cloud_over(CloudVsOut in [[stage_in]], constant ShadeFrame &f [[buffer(16)]], constant ShadeLook &L [[buffer(17)]],
                          constant ShadeGpu &g [[buffer(18)]], depth2d<float> depth [[texture(0)]], texture2d<float> cdist [[texture(1)]],
                          texture2d<half> hist [[texture(2)]], texture2d<ushort> hdata [[texture(3)]], constant uint &mode [[buffer(0)]]) {
	float d = depth.read(uint2(in.position.xy));
	if (d <= 0.0 && (mode & 1u) == 0u) discard_fragment();
	float kEmpty = d > 0.0 ? 1.0 : 1.0 - smoothstep(0.0, 24.0, L.clouds0.x);
	if (kEmpty <= 0.001) discard_fragment();
	float2 ndc = in.position.xy * f.screen.zw * 2.0 - 1.0;
	float4 w = f.invViewProj * float4(ndc, d > 0.0 ? d : 0.5, 1.0);
	float3 p = w.xyz / w.w - shadeEye(f.invViewProj);
	float dist = length(p);
	float3 dir = p / max(dist, 1e-4);
	if (d <= 0.0) {
		if (dir.y >= 0.0) discard_fragment();
		dist = 1e9;
	}
	float4 cp = L.viewProjNoJitter * float4(dir, 0.0);
	if (cp.w <= 1e-6) discard_fragment();
	float2 uv = cp.xy / cp.w * 0.5 + 0.5;
	float cd;
	if ((mode & 2u) != 0u) {
		constexpr sampler gatherS(filter::nearest, address::clamp_to_edge);
		uint4 q = uint4(hdata.gather(gatherS, uv)) & 0xfffu;
		float2 fh = fract(uv * float2(hdata.get_width(), hdata.get_height()) - 0.5);
		float4 wq = float4((1.0 - fh.x) * fh.y, fh.x * fh.y, fh.x * (1.0 - fh.y), (1.0 - fh.x) * (1.0 - fh.y));
		wq = select(float4(0.0), wq, q != CLOUD_DIST_NONE);
		float wsum = wq.x + wq.y + wq.z + wq.w;
		if (wsum <= 1e-4) discard_fragment();
		cd = dot(wq, exp2(float4(q) * (1.0 / 256.0))) / wsum;
	} else {
		float2 dsz = float2(cdist.get_width(), cdist.get_height());
		float2 dp = clamp(uv * dsz - 0.5, float2(0.0), dsz - 1.0);
		uint2 d0 = uint2(dp), d1 = min(d0 + 1u, uint2(dsz) - 1u);
		float2 fw = dp - float2(d0);
		cd = mix(mix(cdist.read(d0).r, cdist.read(uint2(d1.x, d0.y)).r, fw.x), mix(cdist.read(uint2(d0.x, d1.y)).r, cdist.read(d1).r, fw.x), fw.y);
	}
	float k = smoothstep(cd * 0.8, cd * 1.05, dist) * kEmpty;
	if (k <= 0.001) discard_fragment();
	half4 cl = hist.sample(cloudClamp, uv);
	float T = mix(1.0, float(cl.a), k);
	return half4(half3(min(float3(cl.rgb) * k * g.exposure.x, 60000.0)), half(1.0 - T));
}

static inline float3 cloudDomeDirection(float2 uv) {
	float az = uv.x * 6.2831853, y = uv.y * uv.y, c = sqrt(max(1.0 - y * y, 0.0));
	return float3(cos(az) * c, y, sin(az) * c);
}

fragment half4 cloud_dome(CloudVsOut in [[stage_in]], constant ShadeFrame &f [[buffer(16)]], constant CloudParams &c [[buffer(0)]],
                          texture2d<half> weather [[texture(0)]], texture3d<half> noise [[texture(1)]], texture2d<half> prev [[texture(2)]],
                          texture2d<half> lut [[texture(17)]], sampler lutS [[sampler(13)]]) {
	float2 uv = in.position.xy / float2(prev.get_width(), prev.get_height());
	float3 dir = cloudDomeDirection(uv);
	float jitter = fract(shadeNoise(in.position.xy, 0) + c.dome.x * 0.6180339887);
	float2 dsz = float2(prev.get_width(), prev.get_height());
	float foot = sqrt((6.2831853 / dsz.x) * (1.5707963 / dsz.y));
	CloudResult r = cloudSky(dir, int(c.dome.w), 2, 1, jitter, f, c, weather, noise, lut, lutS, foot);
	half4 now = half4(half3(min(r.S, 60000.0)), half(r.T));
	if (c.frame.z > 0.5) return now;
	return mix(prev.read(uint2(in.position.xy)), now, half(c.dome.z));
}

fragment half cloud_shadow(CloudVsOut in [[stage_in]], constant CloudParams &c [[buffer(0)]], texture2d<half> weather [[texture(0)]],
                           texture3d<half> noise [[texture(1)]]) {
	float2 uv = in.position.xy / 256.0;
	float2 rel = c.shadow.zw + (uv - 0.5) * c.shadow.x;
	float3 L = c.shadowDir.xyz;
	float ly = L.y;
	float yRef = c.shadow.y;
	float sa = (c.layer.x - yRef) / ly, sb = (c.layer.y - yRef) / ly;
	int N = cloudDev(c, CLOUD_DEV_OLD_SHADOW) ? 6 : clamp(int(ceil((sb - sa) / 36.0)), 6, 24);
	float ds = (sb - sa) / float(N);
	float tau = 0.0;
	float3 base = float3(rel.x, yRef - c.layer.z, rel.y);
	for (int i = 0; i < N; i++) {
		float s = sa + (float(i) + 0.5) * ds;
		float3 p = base + float3(L.x, ly, L.z) * s;
		tau += cloudDensity(p, yRef + ly * s, 1, c, weather, noise, 1.0, cloudDev(c, CLOUD_DEV_OLD_MIP) ? 1.1 : 0.0) * ds;
	}
	return half(exp(-tau * c.shape.z * 0.8));
}

#endif
