// Copyright 2026 mcopt-shade contributors
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
// License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
// law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
// OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
// limitations under the License.
//
// M3 volumetric clouds (E05): what the rest of shading reads of them, part of ShadeMsl.prelude() (after shade_common.metal,
// before shade_m2.metal: its air fog blends distant terrain into the sky with the clouds).
// The clouds module (clouds.metal, native/mcclouds.m, mcopt.metal.shade.clouds) renders, before the sky pass each frame:
//   ShadeResources.cloudShadow (12)  R16F transmittance of the cumulus toward the light, ShadeLook.cloudShadowVP maps a
//                                    camera-relative point onto it (an oblique projection along the light onto the layer)
//   ShadeResources.cloudDome (13)    RGBA16F scattered light (before exposure) + transmittance by direction (upper hemisphere)
//   ShadeResources.cloudScreen (14)  the same for this frame's view (by direction through ShadeLook.viewProjNoJitter)
// Everything is gated on ShadeLook.clouds0.w (clouds drawn this frame) / clouds0.z (shadow strength): with the module off
// or in the Nether/End these return "no cloud" without touching the textures.
#ifndef MCOPT_SHADE_CLOUDS
#define MCOPT_SHADE_CLOUDS

constexpr sampler shadeCloudSampler(filter::linear, address::clamp_to_edge);

#define SHADE_CLOUD_DEV_NO_SKY 4
#define SHADE_CLOUD_DEV_NO_FOG 8
#define SHADE_CLOUD_DEV_NO_VL 32
#define SHADE_CLOUD_DEV_OLD_FOG 256
#define SHADE_CLOUD_DEV_OLD_VL 512
#define SHADE_CLOUD_DEV_EARLY_FOG 2097152
#define SHADE_CLOUD_DEV_NO_AP 4194304
#define SHADE_CLOUD_DEV_AP_SUN 8388608
static inline bool shadeCloudDev(constant ShadeLook &L, int bit) { return (int(L.clouds1.z) & bit) != 0; }

static inline float shadeCloudShadow(float3 p, constant ShadeLook &L, texture2d<half> map) {
	if (L.clouds0.z <= 0.0) return 1.0;
	float2 uv = (L.cloudShadowVP * float4(p, 1.0)).xy;
	float2 e = abs(uv - 0.5);
	float edge = saturate((0.5 - max(e.x, e.y)) * 12.0);
	float above = 1.0 - smoothstep(L.clouds0.x - 16.0, L.clouds0.x + 40.0, p.y);
	float w = L.clouds0.z * edge * above;
	if (w <= 0.0) return 1.0;
	float t = float(map.sample(shadeCloudSampler, uv, level(0.0)).r);
	return mix(1.0, t, w);
}

struct ShadeCloudRay {
	float2 uv0, duv;
	float dy;
};

static inline ShadeCloudRay shadeCloudRay(float3 dir, constant ShadeLook &L) {
	ShadeCloudRay r;
	r.uv0 = (L.cloudShadowVP * float4(0.0, 0.0, 0.0, 1.0)).xy;
	r.duv = (L.cloudShadowVP * float4(dir, 0.0)).xy;
	r.dy = dir.y;
	return r;
}

static inline float shadeCloudShadowRay(ShadeCloudRay r, float t, constant ShadeLook &L, texture2d<half> map) {
	if (L.clouds0.z <= 0.0) return 1.0;
	float2 uv = r.uv0 + r.duv * t;
	float2 e = abs(uv - 0.5);
	float edge = saturate((0.5 - max(e.x, e.y)) * 12.0);
	float above = 1.0 - smoothstep(L.clouds0.x - 16.0, L.clouds0.x + 40.0, r.dy * t);
	float s = float(map.sample(shadeCloudSampler, uv, level(0.0)).r);
	return mix(1.0, s, L.clouds0.z * edge * above);
}

static inline float4 shadeCloudsScreen(float3 dir, constant ShadeLook &L, texture2d<half> tex) {
	if (L.clouds0.w < 0.5) return float4(0.0, 0.0, 0.0, 1.0);
	float4 c = L.viewProjNoJitter * float4(dir, 0.0);
	if (c.w <= 1e-6) return float4(0.0, 0.0, 0.0, 1.0);
	float2 uv = c.xy / c.w * 0.5 + 0.5;
	if (L.clouds1.w < 0.5) return float4(tex.sample(shadeCloudSampler, uv, level(0.0)));
	float2 sz = float2(tex.get_width(), tex.get_height());
	float2 t = uv * sz - 0.5, i = floor(t), f = t - i, f2 = f * f, f3 = f2 * f;
	float2 w0 = (1.0 - 3.0 * f + 3.0 * f2 - f3) / 6.0, w1 = (4.0 - 6.0 * f2 + 3.0 * f3) / 6.0, w2 = (1.0 + 3.0 * f + 3.0 * f2 - 3.0 * f3) / 6.0, w3 = f3 / 6.0;
	float2 g0 = w0 + w1, g1 = w2 + w3;
	float2 h0 = (i - 0.5 + w1 / g0) / sz, h1 = (i + 1.5 + w3 / g1) / sz;
	float4 a = float4(tex.sample(shadeCloudSampler, float2(h0.x, h0.y), level(0.0))), b = float4(tex.sample(shadeCloudSampler, float2(h1.x, h0.y), level(0.0)));
	float4 cc = float4(tex.sample(shadeCloudSampler, float2(h0.x, h1.y), level(0.0))), d = float4(tex.sample(shadeCloudSampler, float2(h1.x, h1.y), level(0.0)));
	return g0.y * (g0.x * a + g1.x * b) + g1.y * (g0.x * cc + g1.x * d);
}

static inline float4 shadeCloudsDome(float3 dir, constant ShadeLook &L, texture2d<half> dome) {
	if (L.clouds0.w < 0.5 || L.clouds1.x < 0.5 || dir.y <= 0.0) return float4(0.0, 0.0, 0.0, 1.0);
	float az = atan2(dir.z, dir.x) * 0.15915494;
	float2 uv = float2(az < 0.0 ? az + 1.0 : az, sqrt(saturate(dir.y)));
	constexpr sampler wrapU(filter::linear, s_address::repeat, t_address::clamp_to_edge);
	float4 c = float4(dome.sample(wrapU, uv, level(0.0)));
	float k = smoothstep(0.0, 0.02, dir.y);
	return float4(c.rgb * k, mix(1.0, c.a, k));
}

#endif
