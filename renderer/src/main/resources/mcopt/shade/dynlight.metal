// Copyright 2026 mcopt-shade contributors
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
// License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
// law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
// OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
// limitations under the License.
//
// Dynamic lights (M3, mcopt.shade.dynLights; Java side mcopt.metal.shade.light.DynLights, native side mcdynlight.m): light
// from things that move — the torch or lantern in either of the player's hands, other players and mobs holding luminous
// items, dropped luminous items, burning mobs, blazes, magma cubes, glow squid, fire and spectral arrows, fireballs, lit TNT,
// furnace minecarts, fireworks, lightning. Gathered on the CPU each frame (up to SHADE_DL_MAX within ~48 blocks), faded in
// and out, fire-like ones flicker gently.
//
// Bindings (vertex and fragment stages of every encoder native shading binds, mcdynlight.m mc_dl_bind):
//   buffer(24)   constant ShadeDynLights&  this frame's lights + a coarse light grid around the camera (ring of shared buffers)
//   texture(23)  texture3d<half>           visibility volumes: per occluded light a 32^3 R8 block around it, 1 where the
//                                          light's centre sees the voxel's centre through air (computed every frame from the
//                                          CPU's block occupancy, dynlight_vis.metal); slab s = z slices [32 s, 32 s + 32)
// Both slots are free in every pipeline that binds the shade globals (vanilla's own use buffers 0-7, textures 0-7; Sodium
// 20-22, 26, 27-30; water 18-22, 23).
//
// Model: a light of level l (vanilla's block light emission: torch 14, lantern 15, soul torch 10, ...) reaches l blocks like
// a placed one (euclidean distance instead of vanilla's taxicab flood); at distance d it acts as block light level l - d
// through the same curve the terrain uses for placed torches (shadeLit2: F(b) = (2.2 b^7 + 0.55 b^2.4) / 2.75), times the
// art layer's block-light colour (ShadeFrame.ambientBlock) and the light's own tint (1 for torch-coloured light, cyan for
// soul fire, red for redstone, ...), times a wrapped N.L (so faces turned toward the light catch it and the far side of a
// pillar falls off), times the visibility volume (no light through walls). Lights whose cell list doesn't name a pixel cost
// nothing there: the grid (8-block cells, 64-bit light masks) keeps the per-pixel loop to the few lights that can reach it.
// (FT2.9, mcopt.shade.dynLights.playerLight) The local player's own light (what either hand holds, the brighter one) sits at the
// eye and is omnidirectional (shape.y: no N.L) with a wider flat core (shape.x): like carrying a torch that lights every block
// around evenly, as Photon, Complementary and BSL do (their held light is centred on the player, without a normal term or
// shadows), instead of a point at the hand that lit the walls on its side in a bright disc and left the others dark (Gael).
// (FT2.11, Gael 2026-10-11: "respeta demasiado si la luz alcanza a llegar a un bloque", hard shadows and a sudden step from shadow to
// light) mcopt.shade.dynLights.playerShadows=false (default): the player's own light has no visibility volume (it sits at the eye,
// so whatever the camera sees it reaches anyway; the voxel lookups in front of the surfaces only cut block-sized shadows into
// corners and alcoves). mcopt.shade.dynLights.soft=true (default): the other lights' volumes hold how far light has to go around
// what stands in its way (dynlight_vis.metal shade_dl_flood), not a yes/no per ray: it bends around corners and fades behind them
// like a placed torch's (shape.z = 1, shadeDlReach).
#ifndef MCOPT_SHADE_DYNLIGHT_MODULE
#define MCOPT_SHADE_DYNLIGHT_MODULE
#include <metal_stdlib>
using namespace metal;

#define SHADE_DL_MAX 64
#define SHADE_DL_VOL 32
#define SHADE_DL_CUT 2.0
#define SHADE_DL_GRID_X 16
#define SHADE_DL_GRID_Y 12
#define SHADE_DL_GRID_Z 16
#define SHADE_DL_IRR_X 96
#define SHADE_DL_IRR_Y 64
#define SHADE_DL_IRR_Z 96
#define SHADE_DL_IRR_FADE 8.0
#define SHADE_DL_WARM float3(2.4, 1.2, 0.48)

struct ShadeDynLight {
	float4 pos;
	float4 color;
	float4 vol;
	float4 shape;
};

struct ShadeDynLights {
	uint4 info;
	float4 grid;
	float4 params;
	float4 irr;
	ShadeDynLight lights[SHADE_DL_MAX];
	uint2 cells[SHADE_DL_GRID_X * SHADE_DL_GRID_Y * SHADE_DL_GRID_Z];
};

static inline float shadeDlCurve(float b) {
	float b7 = b * b * b; b7 = b7 * b7 * b;
	return (2.2 * b7 + 0.55 * pow(max(b, 1e-3), 2.4)) * (1.0 / 2.75);
}

static inline uint2 shadeDlMask(constant ShadeDynLights &D, float3 p) {
	if (D.info.x == 0u) return uint2(0u);
	int3 c = int3(floor((p - D.grid.xyz) * D.grid.w));
	if (any(c < 0) || any(c >= int3(SHADE_DL_GRID_X, SHADE_DL_GRID_Y, SHADE_DL_GRID_Z))) return uint2(0u);
	return D.cells[(c.y * SHADE_DL_GRID_Z + c.z) * SHADE_DL_GRID_X + c.x];
}

#define SHADE_DL_DETOUR (255.0 / 16.0)
static inline float2 shadeDlReach(constant ShadeDynLights &D, constant ShadeDynLight &L, float3 q, texture3d<half> vis) {
	if (L.vol.w < 0.0) return float2(1.0, 0.0);
	constexpr sampler s(filter::linear, address::clamp_to_edge, coord::normalized);
	float3 v = clamp(q - L.vol.xyz, 0.5, float(SHADE_DL_VOL) - 0.5);
	float3 uvw = float3(v.xy * (1.0 / SHADE_DL_VOL), (L.vol.w * SHADE_DL_VOL + v.z) / (D.params.x * SHADE_DL_VOL));
	float r = float(vis.sample(s, uvw, level(0.0)).r);
	return L.shape.z > 0.5 ? float2(1.0, r * SHADE_DL_DETOUR) : float2(r, 0.0);
}

static inline float3 shadeDlOne(constant ShadeDynLights &D, constant ShadeDynLight &L, float3 p, float3 n, bool thin, texture3d<half> vis,
                                thread float3 &l, thread float &d) {
	float3 v = L.pos.xyz - p;
	float d2 = dot(v, v), reach = L.pos.w, cut = reach - SHADE_DL_CUT;
	if (d2 >= cut * cut) return float3(0.0);
	d = sqrt(d2);
	l = v / max(d, 1e-3);
	float nl = dot(n, l);
	float w = L.shape.y > 0.5 ? 1.0 : thin ? 0.55 + 0.45 * abs(nl) : saturate((nl + 0.3) * (1.0 / 1.3));
	if (w <= 0.0) return float3(0.0);
	float b = saturate((reach - max(d, L.shape.x)) * (1.0 / 15.0));
	float e = shadeDlCurve(b) * w * saturate(cut - d);
	if (e <= 1e-4) return float3(0.0);
	float2 vr = shadeDlReach(D, L, p + n * 0.5, vis);
	if (vr.y > 0.0) {
		float de = d + vr.y;
		e = shadeDlCurve(saturate((reach - max(de, L.shape.x)) * (1.0 / 15.0))) * w * saturate(cut - de);
	}
	return L.color.rgb * (e * vr.x);
}

static float3 shadeDynamicLights(constant ShadeDynLights &D, texture3d<half> vis, float3 p, float3 n, bool thin, half block) {
	uint2 m = shadeDlMask(D, p);
	if ((m.x | m.y) == 0u) return float3(0.0);
	float3 sum = float3(0.0), l;
	float d;
	while (m.x != 0u) {
		uint i = ctz(m.x);
		m.x &= m.x - 1u;
		sum += shadeDlOne(D, D.lights[i], p, n, thin, vis, l, d);
	}
	while (m.y != 0u) {
		uint i = ctz(m.y);
		m.y &= m.y - 1u;
		sum += shadeDlOne(D, D.lights[32u + i], p, n, thin, vis, l, d);
	}
	return sum * (D.params.y * (1.0 - 0.5 * shadeDlCurve(float(block))));
}

static float3 shadeDynamicLightsVol(constant ShadeDynLights &D, texture3d<half> irr, texture3d<half> dirs, float3 p, float3 n, bool thin, half block) {
	if (D.info.x == 0u || D.irr.w <= 0.0) return float3(0.0);
	const float3 size = float3(SHADE_DL_IRR_X, SHADE_DL_IRR_Y, SHADE_DL_IRR_Z);
	float3 q = p + n * 0.5 - D.irr.xyz;
	float3 edge = min(q, size - q);
	float fade = saturate((min(min(edge.x, edge.y), edge.z) - 0.5) * (1.0 / (SHADE_DL_IRR_FADE - 0.5)));
	if (fade <= 0.0) return float3(0.0);
	float3 v = q / size;
	constexpr sampler s(filter::linear, address::clamp_to_edge, coord::normalized);
	float3 e = float3(irr.sample(s, v, level(0.0)).rgb) * fade;
	if (e.x + e.y + e.z < 1e-5) return float3(0.0);
	float4 dd = float4(dirs.sample(s, v, level(0.0)));
	float3 dir = dd.xyz;
	float r = length(dir);
	float c = dot(n, dir) / max(r, 1e-3);
	float w = thin ? 0.55 + 0.45 * abs(c) : mix(0.6, saturate((c + 0.3) * (1.0 / 1.3)), saturate(r));
	w = mix(w, 1.0, saturate(dd.w));
	return e * (w * D.params.y * (1.0 - 0.5 * shadeDlCurve(float(block))));
}

#ifdef SHADE_DL_KERNELS
kernel void shade_dl_irr(uint3 t0 [[thread_position_in_grid]], constant ShadeDynLights &D [[buffer(0)]], constant uint4 &box [[buffer(1)]],
                         texture3d<half> vis [[texture(0)]], texture3d<half, access::write> irr [[texture(1)]],
                         texture3d<half, access::write> dirs [[texture(2)]]) {
	uint3 t = t0 + box.xyz;
	if (any(t >= uint3(SHADE_DL_IRR_X, SHADE_DL_IRR_Y, SHADE_DL_IRR_Z))) return;
	float3 c = D.irr.xyz + float3(t) + 0.5;
	uint2 m = shadeDlMask(D, c);
	float3 e = float3(0.0), ds = float3(0.0);
	float ls = 0.0, lo = 0.0;
	for (uint k = 0u; k < 2u; k++) {
		uint bits = k == 0u ? m.x : m.y;
		while (bits != 0u) {
			uint i = ctz(bits) + 32u * k;
			bits &= bits - 1u;
			constant ShadeDynLight &L = D.lights[i];
			float3 v = L.pos.xyz - c;
			float d2 = dot(v, v), reach = L.pos.w, cut = reach - SHADE_DL_CUT;
			if (d2 >= cut * cut) continue;
			float d = sqrt(d2), de = d, k = 1.0;
			if (L.vol.w >= 0.0) {
				int3 q = int3(floor(c - L.vol.xyz));
				if (any(q < 0) || any(q >= SHADE_DL_VOL)) continue;
				float r = float(vis.read(uint3(uint(q.x), uint(q.y), uint(L.vol.w) * SHADE_DL_VOL + uint(q.z))).r);
				if (L.shape.z > 0.5) de += r * SHADE_DL_DETOUR;
				else k = r;
			}
			float b = saturate((reach - max(de, L.shape.x)) * (1.0 / 15.0));
			float f = shadeDlCurve(b) * saturate(cut - de) * k;
			if (f <= 1e-5) continue;
			float3 ci = L.color.rgb * f;
			float w = dot(ci, float3(0.2126, 0.7152, 0.0722));
			e += ci;
			if (L.shape.y > 0.5) {
				lo += w;
			} else {
				ds += v * (w / max(d, 1e-3));
				ls += w;
			}
		}
	}
	irr.write(half4(half3(min(e, 60000.0)), 0.0h), t);
	dirs.write(half4(half3(ls > 0.0 ? ds / ls : float3(0.0)), half(lo > 0.0 ? lo / (lo + ls) : 0.0)), t);
}
#endif

static float3 shadeDynamicLightsSpec(constant ShadeDynLights &D, texture3d<half> vis, float3 p, float3 n, half block, float3 v, float shininess,
                                     thread float3 &specOut) {
	uint2 m = shadeDlMask(D, p);
	specOut = float3(0.0);
	if ((m.x | m.y) == 0u) return float3(0.0);
	float3 sum = float3(0.0), l;
	float d;
	float norm = (shininess + 8.0) * (1.0 / 8.0);
	for (uint k = 0u; k < 2u; k++) {
		uint bits = k == 0u ? m.x : m.y;
		while (bits != 0u) {
			uint i = ctz(bits) + 32u * k;
			bits &= bits - 1u;
			float3 c = shadeDlOne(D, D.lights[i], p, n, false, vis, l, d);
			if (all(c == 0.0)) continue;
			sum += c;
			float3 h = normalize(l + v);
			specOut += c * (D.lights[i].color.w * norm * pow(saturate(dot(n, h)), shininess));
		}
	}
	float k = D.params.y * (1.0 - 0.5 * shadeDlCurve(float(block)));
	specOut *= k * D.params.z;
	return sum * k;
}

static float4 shadeDynLightmap(float4 lm, int2 uv2, float3 p, float3 n, texture2d<float> lightMap, sampler lightSampler, constant ShadeDynLights &D,
                               texture3d<half> vis) {
	uint2 m = shadeDlMask(D, p);
	if ((m.x | m.y) == 0u) return lm;
	bool hasN = dot(n, n) > 0.01;
	float3 nn = hasN ? normalize(n) : float3(0.0, 1.0, 0.0);
	float3 best = float3(0.0), sum = float3(0.0), tints = float3(0.0);
	float weight = 0.0;
	float sky = clamp(float(uv2.y) / 256.0 + 0.03125, 0.03125, 0.96875);
	for (uint k = 0u; k < 2u; k++) {
		uint bits = k == 0u ? m.x : m.y;
		while (bits != 0u) {
			uint i = ctz(bits) + 32u * k;
			bits &= bits - 1u;
			constant ShadeDynLight &L = D.lights[i];
			float3 v = L.pos.xyz - p;
			float d2 = dot(v, v), reach = L.pos.w, cut = reach - SHADE_DL_CUT;
			if (d2 >= cut * cut) continue;
			float d = sqrt(d2);
			float w = hasN && L.shape.y <= 0.5 ? 0.45 + 0.55 * saturate(dot(nn, v / max(d, 1e-3))) : 1.0;
			float2 vr = shadeDlReach(D, L, p + nn * 0.3, vis);
			float de = d + vr.y;
			float lv = max(reach - max(de, L.shape.x), 0.0);
			float3 dyn = lightMap.sample(lightSampler, float2(clamp(lv * (16.0 / 256.0) + 0.03125, 0.03125, 0.96875), sky), level(0.0)).rgb;
			float a = dot(L.color.rgb, float3(0.2126, 0.7152, 0.0722));
			float3 ex = max(dyn - lm.rgb, 0.0) * (min(a, 1.0) * w * saturate(cut - de) * vr.x);
			float y = dot(ex, float3(0.2126, 0.7152, 0.0722));
			if (y <= 1e-5) continue;
			best = max(best, ex);
			sum += ex;
			float3 c = L.color.rgb * SHADE_DL_WARM;
			float hi = max(max(L.color.r, L.color.g), L.color.b), lo = max(min(min(L.color.r, L.color.g), L.color.b), hi * 0.05);
			float sat = mix(0.5, 0.9, saturate((hi / lo - 1.0) * (1.0 / 3.0)));
			tints += mix(float3(1.0), c / max(max(c.r, c.g), max(c.b, 1e-4)), sat) * y;
			weight += y;
		}
	}
	if (weight <= 1e-5) return lm;
	float3 top = lightMap.sample(lightSampler, float2(0.96875, sky), level(0.0)).rgb;
	float3 add = min(best + 0.25 * (sum - best), max(top - lm.rgb, 0.0)) * (tints / weight) * D.params.w;
	return float4(min(lm.rgb + add, max(top, lm.rgb)), lm.a);
}

#endif
