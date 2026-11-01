// Copyright 2026 mcopt-shade contributors
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
// License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
// law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
// OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
// limitations under the License.
//
// Volumetric light ("god rays", shader bible E07), compiled natively by mcvl.m after shade_common.metal. Original code from
// public techniques: single scattering in a participating medium (Hillaire 2015, "Physically based and unified volumetric
// rendering in Frostbite": energy-conserving per-segment integration), Henyey-Greenstein phase, interleaved gradient noise
// (Jimenez 2014) with an R1 temporal offset, neighbourhood-clamped temporal reprojection (Karis 2014), and the screen-space
// radial occlusion march for crepuscular rays (Mitchell, GPU Gems 3 ch. 13).
// Portions adapted from Photon Shaders by Benjamin Stott ("sixthsurge"), MIT License, commit 26c2906e
// (include/fog/air_fog_vl.glsl; full text: LICENSES/MIT-Photon.txt): the air mode's approach (one march of the whole air, haze and
// mist by height, from the camera to the scene with the sun's shadow at every step, the per-step energy-conserving integral
// carrying the transmittance) and the forest air's phase function: a forward and a backward Henyey-Greenstein lobe (0.7 / 0.3,
// g 0.5 / -0.2) summed over four octaves of a multiple-scattering approximation (each half the light, g x 0.7). Everything else
// is original (Apache-2.0).
//
// Passes, all fragment passes into private RenderTarget|ShaderRead targets (compressible: apple-gpu.md section 0.2):
//   vl_canopy   (compute, 1 threadgroup of 64) how much of the air around the camera is in shadow -> the "canopy factor"
//               (forests, overhangs, cave mouths -> strong shafts; open plains -> subtle), smoothed over seconds, in a
//               16-byte state buffer read by the march.
//   vl_march    (VL resolution, e.g. 1/2 of the target) per pixel: ray-march the local mist from the camera to the scene
//               depth through the sun's shadow map (near cascade where it covers, else far) -> in-scattered light; plus a
//               short screen-space march toward the sun on the depth buffer (crepuscular streaks beyond the shadow range)
//               and, under water, caustic-modulated shafts. rgb = in-scattered radiance (linear, pre-exposure), a = the
//               scene distance this pixel marched to (blocks; 1e4 = sky), used by the temporal and upsample passes.
//   vl_temporal (VL resolution) 3x3 depth-aware filter of the noisy march + reprojected history (ShadeFrame.prevViewProj),
//               clamped to the 3x3 neighbourhood's range and rejected on distance mismatch. Writes the new history.
//   vl_composite (full resolution) depth-aware bilateral upsample of the history; then
//               - vl_composite (M1 / mcopt.shade.hdr=false): adds the light *before* M1's output curve: the target's sRGB value is mapped
//                 back to exposed radiance through the inverse of shadeOutput's curve, the light (x exposure) is added, and
//                 the difference of the two forward curves is added to the pixel (so no light = the exact same pixel);
//                 programmable blending ([[color(0)]]) on the game's RGBA8 main target, blue-noise-ish dither against banding.
//               - vl_composite_hdr (M2 with mcopt.shade.vl.fold=false or a debug view): returns the light x exposure and
//                 the canopy mist's transmittance T for blending (One, SourceAlpha: scene * T + light) into the
//                 pre-exposed HDR world target (RG11B10F / RGBA16F), before bloom and the tone map.
//   M2 default ("fold"): no composite pass; vl_temporal_fold also writes lightT (light + T at VL resolution) and the post
//   chain's final pass (shade_post.metal shadeVlUpsample, ShadePost.setVolumetric) upsamples and blends it. VL_HDR
//   (function constant 0) = built for M2: the canopy kernel also writes ShadeGpu.scene.y. See VL.md section 5.
// Bindings: ShadeFrame at buffer(16), shadow map texture(16) + compare sampler(12) (mc_shade_bind_*), VlParams at buffer(0),
// the canopy state at buffer(1); pass inputs at texture(0..1); M2: ShadeGpu at buffer(18) (HDR composite, canopy kernel).

#ifndef MCOPT_SHADE_VL
#define MCOPT_SHADE_VL

constant bool VL_HDR [[function_constant(0)]];

struct VlParams {
	float4 size;
	float4 shape;
	float4 medium;
	float4 haze;
	float4 light;
	float4 crep;
	float4 sunPx;
	float4 water;
	float4 water2;
	float4 temporal;
	float4 camDelta;
	float4 gate;
	float4 extra;
	float4 mist;
	float4 mistAmb;
	float4 carve;
	float4 art;
	float4 tune;
	float4 tune2;
	float4 hp;
	float4 radial;
	float4 look2;
	float4 air;
	float4 air2;
	float4 grid;
	float4 grid2;
	float4 air3;
	float4 air4;
	float4 air5;
	float4 air6;
	float4 air7;
};
#define VL_GRID_X 64
#define VL_GRID_Y 32
#define VL_GRID_Z 64
#define VL_GRID_TAPS 24
#define VL_GRID_TAPS_UP 12
#define VL_GRID_ROUNDS 4
#define VL_FAR_STEPS 8

struct VlState { float4 canopy; };

struct VlOut { float4 position [[position]]; };

vertex VlOut vl_vs(uint vid [[vertex_id]]) {
	VlOut o;
	o.position = float4(vid == 1 ? 3.0 : -1.0, vid == 2 ? 3.0 : -1.0, 0.0, 1.0);
	return o;
}

static inline float vlHG(float mu, float g) {
	float g2 = g * g;
	float x = max(1.0 + g2 - 2.0 * g * mu, 1e-4);
	return 0.0795775 * (1.0 - g2) * rsqrt(x * x * x);
}

static inline float vlPhase(float mu, constant VlParams &p) {
	float iso = p.haze.z, back = p.haze.w;
	return (1.0 - iso - back) * vlHG(mu, p.shape.w) + back * vlHG(mu, -0.25) + iso * 0.0795775;
}

static inline float3 vlDirection(float2 ndc, constant ShadeFrame &f) {
	return normalize(shadeViewRay(ndc, f.invViewProj));
}
static inline float vlDistance(float2 ndc, float depth, constant ShadeFrame &f) {
	if (depth <= 0.0) return 1e4;
	float4 h = f.invViewProj * float4(ndc, depth, 1.0);
	return min(length(h.xyz / h.w), 1e4);
}

static inline float vlShadow(depth2d_array<float> map, sampler cmp, float3 c0, float3 c1, bool farOnly = false) {
	if (!farOnly && max(abs(c0.x), abs(c0.y)) < 0.97 && c0.z > 0.0 && c0.z < 1.0) return map.sample_compare(cmp, shadeShadowUV(c0), 0u, c0.z);
	if (max(abs(c1.x), abs(c1.y)) >= 0.995) return 1.0;
	if (c1.z >= 1.0) return 1.0;
	if (c1.z <= 0.0) return 0.0;
	return map.sample_compare(cmp, shadeShadowUV(c1), 1u, c1.z);
}

static inline half vlCausticWeb(half2 p, half4 tp, half tp4) {
	p += 0.55h * half2(sin(p.y * 1.31h + tp.x), sin(p.x * 1.17h - tp.y));
	half a = sin(p.x * 1.9h + tp.z) + sin(p.y * 2.1h - tp.w) + sin((p.x - p.y) * 1.45h + tp4);
	half r = saturate(1.0h - abs(a) * 0.55h);
	r *= r;
	return r * r;
}
static inline void vlCausticPhases(float t, thread half4 &a, thread half &a4, thread half4 &b, thread half &b4) {
	const float TAU = 6.2831853;
	float s = t * (1.0 / 4096.0);
	a = half4(TAU * fract(s * float4(541.0, 463.0, 587.0, 684.0)));
	a4 = half(TAU * fract(s * 391.0));
	b = half4(TAU * fract(s * float4(703.0, 602.0, 763.0, 890.0)));
	b4 = half(TAU * fract(s * 508.0));
}
static inline half vlCaustic(float2 q, half4 ta, half ta4, half4 tb, half tb4) {
	half broad = 0.25h + 3.6h * vlCausticWeb(half2(q * 0.22), ta, ta4);
	half fine = 0.5h + 2.4h * vlCausticWeb(half2(q * 0.55 + 37.0), tb, tb4);
	return broad * fine;
}

static inline float vlNoise(float2 px, float frame) {
	float n = fract(52.9829189 * fract(dot(px, float2(0.06711056, 0.00583715))));
	return fract(n + frame * 0.6180339887);
}

kernel void vl_canopy(uint tid [[thread_index_in_threadgroup]], uint lane [[thread_index_in_simdgroup]],
                      uint sg [[simdgroup_index_in_threadgroup]],
                      constant ShadeFrame &f [[buffer(16)]], depth2d_array<float> map [[texture(16)]],
                      constant VlParams &p [[buffer(0)]], device VlState *state [[buffer(1)]],
                      device ShadeGpu *gpu [[buffer(18), function_constant(VL_HDR)]]) {
	threadgroup float partial[2];
	uint ringIdx = tid >> 4u;
	float ring = 3.0 + 4.0 * float(ringIdx);
	float h = (tid & 1u) != 0u ? 5.0 : 1.5;
	float a = float(tid & 15u) * (6.2831853 / 16.0) + float(ringIdx) * 0.39;
	float3 pt = float3(cos(a) * ring, h, sin(a) * ring);
	float shadowed = 0.0;
	if (f.flags.z != 0) {
		float4 c = f.shadowViewProj[1] * float4(pt, 1.0);
		if (max(abs(c.x), abs(c.y)) < 0.995 && c.z < 1.0) {
			if (c.z <= 0.0) {
				shadowed = 1.0;
			} else {
				float2 uv = shadeShadowUV(c.xyz);
				uint2 texel = uint2(clamp(uv * f.shadowParams.y, 0.0, f.shadowParams.y - 1.0));
				shadowed = map.read(texel, 1u) > c.z + 1e-5 ? 1.0 : 0.0;
			}
		}
	}
	float s = simd_sum(shadowed);
	if (lane == 0u) partial[sg] = s;
	threadgroup_barrier(mem_flags::mem_threadgroup);
	if (tid == 0u) {
		float frac = (partial[0] + partial[1]) / 64.0;
		float target = smoothstep(0.12, 0.5, frac);
		float old = state->canopy.x;
		float k = p.extra.x > 0.5 ? 1.0 : (target > old ? p.camDelta.w : p.extra.z);
		float canopy = mix(old, target, k);
		state->canopy = float4(canopy, frac, 0.0, 0.0);
		if (VL_HDR) gpu->scene.y = canopy;
	}
}

static inline float vlGridTap(float3 q, constant ShadeFrame &f, depth2d_array<float> map) {
	constexpr sampler cmpLin(coord::normalized, filter::linear, address::clamp_to_edge, compare_func::greater_equal);
	float4 h = f.shadowViewProj[1] * float4(q, 1.0);
	if (max(abs(h.x), abs(h.y)) >= 0.995 || h.z >= 1.0) return 1.0;
	return h.z > 0.0 ? map.sample_compare(cmpLin, shadeShadowUV(h.xyz), 1u, h.z) : 0.0;
}

static inline float vlGridTapNear(float3 q, constant ShadeFrame &f, depth2d_array<float> map, float zB, float dmax) {
	constexpr sampler pt(filter::nearest, address::clamp_to_edge);
	float4 h = f.shadowViewProj[1] * float4(q, 1.0);
	if (max(abs(h.x), abs(h.y)) >= 0.995 || h.z >= 1.0) return 1.0;
	if (h.z <= 0.0) return 0.0;
	float4 d = map.gather(pt, shadeShadowUV(h.xyz), 1u);
	float4 near = select(float4(0.0), float4(1.0), d > h.z && (d - h.z) * zB < dmax);
	return 1.0 - dot(near, float4(0.25));
}

kernel void vl_shade_grid(uint3 tid [[thread_position_in_grid]], constant ShadeFrame &f [[buffer(16)]], constant VlParams &p [[buffer(0)]],
                          depth2d_array<float> map [[texture(16)]], texture3d<half, access::write> grid [[texture(0)]]) {
	uint3 gid = p.air4.w < 0.0 ? tid : uint3(tid.x, tid.y, tid.z * VL_GRID_ROUNDS + uint(p.air4.w));
	if (gid.x >= VL_GRID_X || gid.y >= VL_GRID_Y || gid.z >= VL_GRID_Z) return;
	float3 c = p.grid.xyz + (float3(gid) + 0.5) * p.grid.w;
	float r = p.grid2.w;
	float lit = 0.0;
	if (f.flags.z == 0) {
		lit = float(VL_GRID_TAPS);
	} else {
		for (int k = 0; k < VL_GRID_TAPS; k++) {
			float3 u = fract(0.5 + float(k) * float3(0.8191725134, 0.6710436067, 0.5497004779));
			lit += vlGridTap(c + float3((u.x * 2.0 - 1.0) * r, 0.5 + u.y * (r - 0.5), (u.z * 2.0 - 1.0) * r), f, map);
		}
	}
	float share = lit / float(VL_GRID_TAPS);
	float up = p.look2.y, rUp = p.air5.w > 0.0 ? p.air5.w : r;
	if (up > r && f.flags.z != 0 && share < 0.999) {
		float open = 0.0;
		int cols = min(int(p.air6.x), 12);
		if (cols > 0) {
			int taps = clamp(int(p.air6.y), 2, 6);
			float rho = cols == 1 ? 0.0 : p.air6.z > 0.0 ? p.air6.z : 0.75 * rUp, lo = p.air7.x > 0.0 ? p.air7.x : r;
			float3 m2 = float3(f.shadowViewProj[1][0][2], f.shadowViewProj[1][1][2], f.shadowViewProj[1][2][2]);
			float zB = 1.0 / max(length(m2), 1e-6), dmax = p.air7.y;
			for (int i = 0; i < cols; i++) {
				float a = (float(i) + 0.5) * (6.2831853 / float(cols));
				float2 o = float2(cos(a), sin(a)) * rho;
				float col = 1.0;
				for (int j = 0; j < taps && col > 0.02; j++) {
					float s = float(j) / float(taps - 1);
					float3 q = c + float3(o.x, lo + s * s * (up - lo), o.y);
					col = min(col, dmax > 0.0 ? vlGridTapNear(q, f, map, zB, dmax) : vlGridTap(q, f, map));
				}
				open += col;
			}
			open /= float(cols);
			if (p.air7.z > 0.0) {
				float box = 0.0;
				for (int k = 0; k < VL_GRID_TAPS_UP; k++) {
					float3 u = fract(0.5 + (float(k) + 0.37) * float3(0.8191725134, 0.6710436067, 0.5497004779));
					box += vlGridTap(c + float3((u.x * 2.0 - 1.0) * rUp, r + u.y * (up - r), (u.z * 2.0 - 1.0) * rUp), f, map);
				}
				open = min(open, box / float(VL_GRID_TAPS_UP));
			}
		} else {
			for (int k = 0; k < VL_GRID_TAPS_UP; k++) {
				float3 u = fract(0.5 + (float(k) + 0.37) * float3(0.8191725134, 0.6710436067, 0.5497004779));
				open += vlGridTap(c + float3((u.x * 2.0 - 1.0) * rUp, r + u.y * (up - r), (u.z * 2.0 - 1.0) * rUp), f, map);
			}
			open /= float(VL_GRID_TAPS_UP);
		}
		share = max(share, smoothstep(p.look2.z, p.look2.w, open));
	}
	grid.write(half4(half(share)), (gid + uint3(p.grid2.xyz)) % uint3(VL_GRID_X, VL_GRID_Y, VL_GRID_Z));
}

static inline float vlMistDensity(float t, float y, constant VlParams &p) {
	return exp(-t / p.mist.x - max(y - p.mist.y, 0.0) / p.extra.y);
}
static inline float vlMistDepth(float3 dir, float D, constant VlParams &p) {
	float R = p.mist.x, H = p.extra.y;
	float t1 = dir.y > 1e-4 ? min(p.mist.y / dir.y, D) : D;
	float od = R * (1.0 - exp(-t1 / R));
	if (D > t1) {
		float k = 1.0 / R + dir.y / H;
		od += exp(-t1 / R) * (1.0 - exp(-(D - t1) * k)) / k;
	}
	return od;
}

struct VlMarchOut {
	half4 light [[color(0)]];
	half sky [[color(1)]];
};

fragment VlMarchOut vl_march(VlOut in [[stage_in]], constant ShadeFrame &f [[buffer(16)]], constant VlParams &p [[buffer(0)]],
                        constant VlState &st [[buffer(1)]], depth2d<float> depth [[texture(0)]], texture2d<half> prevSky [[texture(1)]],
                        depth2d_array<float> map [[texture(16)]], sampler cmp [[sampler(12)]],
                        constant ShadeLook &sl [[buffer(17)]], constant ShadeResources &sr [[buffer(19)]],
                        texture3d<half> shadeGrid [[texture(2)]], texture2d<half> skyLut [[texture(17)]], sampler lutS [[sampler(13)]]) {
	float2 scale = p.size.zw / p.size.xy;
	uint2 fpx = uint2(min(floor(in.position.xy * scale), p.size.zw - 1.0));
	float2 fpos = float2(fpx) + 0.5;
	float2 ndc = fpos / p.size.zw * 2.0 - 1.0;
	float d = depth.read(fpx);
	float3 dir = vlDirection(ndc, f);
	float dist = vlDistance(ndc, d, f);
	float xi = fract(p.temporal.z * 0.6180339887 + 0.25 * vlNoise(in.position.xy, 0.0));
	if ((int(p.sunPx.w) & 32) != 0) xi = vlNoise(in.position.xy + 3.0, p.temporal.z);
	float3 L = f.lightDirWorld.xyz;
	float mu = dot(dir, L);
	float canopy = p.gate.z >= 0.0 ? p.gate.z : max(st.canopy.x, p.tune.x);
	float3 scatter = float3(0.0);
	bool underwater = p.water2.y > 0.5;
	int n = int(p.shape.z);
	float3 c0o = (f.shadowViewProj[0] * float4(0.0, 0.0, 0.0, 1.0)).xyz, c0d = (f.shadowViewProj[0] * float4(dir, 0.0)).xyz;
	float3 c1o = (f.shadowViewProj[1] * float4(0.0, 0.0, 0.0, 1.0)).xyz, c1d = (f.shadowViewProj[1] * float4(dir, 0.0)).xyz;
	bool shadows = f.flags.z != 0;
	bool farOnly = (int(p.sunPx.w) & 64) != 0;
	float softR = p.tune2.z;
	float2 o0a = 0.0, o0b = 0.0, o1a = 0.0, o1b = 0.0;
	float softRot = 0.0;
	if (softR > 0.0) {
		float3 e1 = normalize(abs(L.y) < 0.95 ? cross(L, float3(0.0, 1.0, 0.0)) : cross(L, float3(1.0, 0.0, 0.0)));
		float3 e2 = cross(L, e1);
		o0a = (f.shadowViewProj[0] * float4(e1 * softR, 0.0)).xy;
		o0b = (f.shadowViewProj[0] * float4(e2 * softR, 0.0)).xy;
		o1a = (f.shadowViewProj[1] * float4(e1 * softR, 0.0)).xy;
		o1b = (f.shadowViewProj[1] * float4(e2 * softR, 0.0)).xy;
		softRot = 6.2831853 * vlNoise(in.position.xy + 29.0, p.temporal.z);
	}
	ShadeCloudRay cloudRay = {};
	bool vlClouds = VL_HDR && !shadeCloudDev(sl, SHADE_CLOUD_DEV_NO_VL);
	int cloudEvery = VL_HDR && shadeCloudDev(sl, SHADE_CLOUD_DEV_OLD_VL) ? 1 : 2;
	int cloudAt = -8;
	float cloudHeld = 1.0;
	if (VL_HDR) cloudRay = shadeCloudRay(dir, sl);

	if (VL_HDR && !underwater && p.air.x > 0.5) {
		float yc = f.cameraBlock.y + f.cameraFract.y;
		float3 sigR = sl.fogR.rgb;
		float hR = sl.fogR.w, y0R = sl.fogMisc.x;
		float sigM = sl.fogM.x, hM = sl.fogM.y, y0M = sl.fogM.z, alM = sl.fogM.w;
		float local = p.light.w;
		bool skyPx = d <= 0.0;
		constexpr sampler gridLin(filter::linear, address::repeat);
		float3 gridSpan = p.grid.w * float3(VL_GRID_X, VL_GRID_Y, VL_GRID_Z);
		float3 gridWrap = p.grid2.xyz / float3(VL_GRID_X, VL_GRID_Y, VL_GRID_Z);
		float litK = 1.0 / max(p.air3.y - p.air3.x, 1e-3);
		float D = min(dist, p.air2.x);
		int na = clamp(int(p.air.y + p.air.z * D), 2, int(max(p.air.w, 2.0)));
		float invN = 1.0 / float(na);
		float trans = 1.0, mistAll = 0.0, mistLit = 0.0, forestLit = 0.0, forestAll = 0.0;
		float hazeAll = 0.0, hazeLit = 0.0;
		float apAll = 0.0, apLit = 0.0;
		float lumR = dot(sigR, float3(0.2126, 0.7152, 0.0722));
		float prevB = 0.0;
		float tStop = D;
		if (skyPx && p.air6.w > 0.0 && p.air2.y == 0.0 && p.air4.x <= 0.0) {
			float3 ex = select(-p.grid.xyz, p.grid.xyz + gridSpan, dir > 0.0) / max(abs(dir), 1e-6);
			tStop = min(min(ex.x, min(ex.y, ex.z)) + 1.0, D);
			if (p.air5.y > p.air5.x) tStop = min(tStop, p.air5.y);
			if (local <= 0.0) tStop = 0.0;
		}
		for (int i = 0; i < na; i++) {
			float u1 = float(i + 1) * invN, u = (float(i) + xi) * invN;
			float b1 = D * u1 * u1, t = D * u * u, dt = b1 - prevB;
			if (t > tStop) break;
			prevB = b1;
			float y = yc + dir.y * t;
			float fR = exp(-max(y - y0R, 0.0) / hR);
			float dM = sigM * exp(-max(y - y0M, 0.0) / hM);
			float dF = 0.0;
			if (local > 0.0) {
				float3 g = (dir * t - p.grid.xyz) / gridSpan;
				float3 edge = min(g, 1.0 - g) * gridSpan;
				float inGrid = saturate(min(edge.x, min(edge.y, edge.z)) * 0.125);
				float shade = p.air4.x;
				if (inGrid > 0.0) shade = mix(p.air4.x, max(saturate((p.air3.y - float(shadeGrid.sample(gridLin, g + gridWrap).r)) * litK), p.air4.x), inGrid);
				dF = local * shade * (p.air3.z > 0.0 ? saturate(t / p.air3.z) : 1.0);
				if (p.air5.y > p.air5.x) dF *= 1.0 - smoothstep(p.air5.x, p.air5.y, t);
				dF *= mix(p.air5.z, 1.0, saturate(canopy));
			}
			float ext = lumR * fR + dM + dF * p.mist.w;
			float3 q0 = c0o + c0d * t, q1 = c1o + c1d * t;
			if (softR > 0.0) {
				float a = softRot + 2.3999632 * float(i), r = sqrt(fract((float(i) + 0.5) * 0.618034 + softRot));
				float2 cs = float2(cos(a), sin(a)) * r;
				q0.xy += o0a * cs.x + o0b * cs.y;
				q1.xy += o1a * cs.x + o1b * cs.y;
			}
			float v = shadows ? vlShadow(map, cmp, q0, q1, farOnly) : 1.0;
			if (vlClouds && v > 0.0) {
				if (i - cloudAt >= cloudEvery) {
					cloudHeld = shadeCloudShadowRay(cloudRay, t, sl, sr.cloudShadow);
					cloudAt = i;
				}
				v *= cloudHeld;
			}
			float stepT = exp(-ext * dt);
			float seg = trans * (1.0 - stepT) / max(ext, 1e-9);
			float segR = fR * seg;
			hazeAll += segR;
			hazeLit += v * segR;
			mistAll += dM * seg;
			mistLit += v * dM * seg;
			apAll += dt;
			apLit += v * dt;
			forestLit += v * dF * seg;
			forestAll += dF * seg;
			trans *= stepT;
		}
		float3 hA = hazeAll * sigR, hL = hazeLit * sigR;
		float phR = 0.0596831 * (1.0 + mu * mu);
		float phM = 0.8 * shadeHG(mu, SHADE_HAZE_MIE_G) + 0.2 * shadeHG(mu, -0.2);
		float glow = 1.0 + sl.fogMisc.w;
		float3 E = f.sunColor.rgb * (3.14159265 * shadeAirSun(sl));
		float3 shadowed = E * ((hL - hA) * (phR * 2.0) + (mistLit - mistAll) * (phM * glow * alM / (1.0 - 0.5 * alM)));
		if (!skyPx && apLit < apAll * 0.999) shadowed -= shadeAerialSunGlow(dir * dist, skyLut, lutS, f, sl) * ((apAll - apLit) / apAll);
		if (skyPx) shadowed *= p.air2.y;
		float phF = 0.0, octave = 1.0, aniso = 1.0;
		if (p.air4.y > 0.0) {
			phF = 1.875 * ((1.0 - p.air4.z) * vlHG(mu, p.air4.y) + p.air4.z * 0.0795775);
		} else {
			for (int k = 0; k < 4; k++) {
				phF += octave * (0.7 * vlHG(mu, 0.5 * aniso) + 0.3 * vlHG(mu, -0.2 * aniso));
				octave *= 0.5;
				aniso *= 0.7;
			}
		}
		float3 forest = p.light.rgb * (3.14159265 * phF * glow) * forestLit + f.ambientSky.rgb * (0.9 * p.air3.w * shadeAirSky(sl)) * forestAll;
		scatter = shadowed * p.air2.z + forest * (p.air2.w * p.shape.x);
	} else if (!underwater) {
		float D = min(dist, p.shape.y);
		float mist = p.medium.y;
		float local = p.light.w * canopy;
		int nNear = int(p.extra.w);
		float localMain = nNear > 0 ? 0.0 : local;
		float sumOpen = 0.0, sumOpenLit = 0.0, sumLocal = 0.0, sumLocalAll = 0.0, trans = 1.0, prevB = 0.0;
		float invN = 1.0 / float(n);
		for (int i = 0; i < n; i++) {
			float u1 = float(i + 1) * invN, u = (float(i) + xi) * invN;
			float b1 = D * u1 * u1, t = D * u * u, dt = b1 - prevB;
			prevB = b1;
			float y = dir.y * t;
			float sOpen = p.medium.x * exp(-max(y - p.haze.x, 0.0) / p.haze.y) + mist * exp(-max(y - p.medium.z, 0.0) / p.medium.w);
			float sLocal = localMain * vlMistDensity(t, y, p);
			float sigma = sOpen + sLocal;
			float3 q0 = c0o + c0d * t, q1 = c1o + c1d * t;
			if (softR > 0.0) {
				float a = softRot + 2.3999632 * float(i), r = sqrt(fract((float(i) + 0.5) * 0.618034 + softRot));
				float2 cs = float2(cos(a), sin(a)) * r;
				q0.xy += o0a * cs.x + o0b * cs.y;
				q1.xy += o1a * cs.x + o1b * cs.y;
			}
			float v = shadows ? vlShadow(map, cmp, q0, q1, farOnly) : 1.0;
			if (vlClouds && v > 0.0) {
				if (i - cloudAt >= cloudEvery) {
					cloudHeld = shadeCloudShadowRay(cloudRay, t, sl, sr.cloudShadow);
					cloudAt = i;
				}
				v *= cloudHeld;
			}
			float seg = trans * (1.0 - exp(-sigma * dt)) / max(sigma, 1e-9);
			sumOpen += sOpen * seg;
			sumOpenLit += v * sOpen * seg;
			sumLocal += v * sLocal * seg;
			sumLocalAll += sLocal * seg;
			trans *= exp(-(sOpen + sLocal * p.mist.w) * dt);
		}
		float farOpen = 0.0, farLit = 0.0;
		if (VL_HDR && vlClouds && p.tune.w > D + 1.0 && dist > D + 1.0) {
			float Df = min(dist, p.tune.w), dtf = (Df - D) / float(VL_FAR_STEPS);
			for (int i = 0; i < VL_FAR_STEPS; i++) {
				float t = D + (float(i) + xi) * dtf;
				float y = dir.y * t;
				float sOpen = (p.medium.x * exp(-max(y - p.haze.x, 0.0) / p.haze.y) + mist * exp(-max(y - p.medium.z, 0.0) / p.medium.w)) * p.tune2.x;
				float v = shadeCloudShadowRay(cloudRay, t, sl, sr.cloudShadow);
				float seg = trans * (1.0 - exp(-sOpen * dtf)) / max(sOpen, 1e-9);
				farOpen += sOpen * seg;
				farLit += v * sOpen * seg;
				trans *= exp(-sOpen * dtf);
			}
		}
		if (nNear > 0 && local > 1e-7) {
			float Dn = min(D, 2.5 * p.mist.x), dtn = Dn / float(nNear), tl = 1.0;
			float xn = (int(p.sunPx.w) & 8) != 0 ? vlNoise(in.position.xy + 7.0, p.temporal.z) : fract(xi + 0.5);
			for (int j = 0; j < nNear; j++) {
				float t = (float(j) + xn) * dtn;
				float sLocal = local * vlMistDensity(t, dir.y * t, p);
				float3 q0 = c0o + c0d * t, q1 = c1o + c1d * t;
				if (softR > 0.0) {
					float a = softRot + 2.3999632 * float(j + 7), r = sqrt(fract((float(j) + 0.25) * 0.618034 + softRot * 0.37));
					float2 cs = float2(cos(a), sin(a)) * r;
					q0.xy += o0a * cs.x + o0b * cs.y;
					q1.xy += o1a * cs.x + o1b * cs.y;
				}
				float v = shadows ? vlShadow(map, cmp, q0, q1, farOnly) : 1.0;
				float seg = tl * (1.0 - exp(-sLocal * dtn)) / max(sLocal, 1e-9);
				sumLocal += v * sLocal * seg;
				sumLocalAll += sLocal * seg;
				tl *= exp(-sLocal * p.mist.w * dtn);
			}
		}
		float sunward = smoothstep(0.35, 0.9, mu);
		float openCut = mix(p.tune.y, p.mistAmb.w, sunward);
		float openPart = max(sumOpenLit - openCut * sumOpen, 0.0) + max(farLit - p.tune2.y * farOpen, 0.0);
		float k = mix(p.tune.z, p.carve.x, sunward);
		sumLocal = max(sumLocal - k * sumLocalAll, 0.0) / (1.0 - k);
		scatter = p.light.rgb * (3.14159265 * vlPhase(mu, p) * (openPart * p.gate.x + sumLocal * p.gate.y) * p.shape.x);
	} else {
		float D = min(dist, p.water2.z);
		float3 ext = p.water.rgb;
		float3 sum = float3(0.0), sumAll = float3(0.0);
		int nw = n + 2;
		float prevB = 0.0, invN = 1.0 / float(nw);
		half4 ta, tb;
		half ta4, tb4;
		vlCausticPhases(p.temporal.w, ta, ta4, tb, tb4);
		float ly = max(L.y, 0.12);
		float xw = (int(p.sunPx.w) & 16) != 0 ? vlNoise(in.position.xy + 13.0, p.temporal.z) : xi;
		for (int i = 0; i < nw; i++) {
			float u1 = float(i + 1) * invN, u = (float(i) + xw) * invN;
			float b1 = D * u1 * u1, t = D * u * u, dt = b1 - prevB;
			prevB = b1;
			float3 pt = dir * t;
			float below = max(p.water2.x - pt.y, 0.0);
			float path = below / ly;
			float2 q = pt.xz + L.xz * path + f.cameraFract.xz + fmod(f.cameraBlock.xz, 256.0);
			half hb = half(min(below, 60.0));
			half c = mix(1.0h, vlCaustic(q, ta, ta4, tb, tb4), half(p.water2.w) * (1.0h - exp2(-0.5771h * hb)) * exp2(-0.0721h * hb));
			float v = shadows ? vlShadow(map, cmp, c0o + c0d * t, c1o + c1d * t) : 1.0;
			float3 tr = float3(exp2(half3(-1.442695 * ext * min(path + t, 200.0)))) * (v * dt);
			sum += tr * float(c);
			sumAll += tr;
		}
		sum = max(sum - p.carve.y * sumAll, 0.0) / (1.0 - p.carve.y);
		float phase = 0.75 * vlHG(mu, 0.65) + 0.25 * 0.0795775;
		scatter = p.light.rgb * sum * (p.water.w * 3.14159265 * phase * p.shape.x) * float(L.y > 0.0);
	}

	half skyT = d <= 0.0 ? (VL_HDR && !shadeCloudDev(sl, SHADE_CLOUD_DEV_NO_VL) ? half(shadeCloudsScreen(dir, sl, sr.cloudScreen).a) : 1.0h) : 0.0h;
	if (p.crep.x > 0.0 && p.crep.z > 0.0 && !underwater && p.temporal.y < 0.5 && (d <= 0.0 || !VL_HDR || p.look2.x != 0.0)) {
		float2 toV = (p.sunPx.xy - fpos) / scale, fv = in.position.xy;
		float2 prevLiveV = p.carve.z > 0.0 ? p.carve.zw * float2(prevSky.get_width(), prevSky.get_height()) : p.size.xy;
		float2 liveV = p.size.xy;
		if (p.air7.w > 0.0) {
			float4 pcP = f.prevViewProj * float4(dir, 0.0), pcS = f.prevViewProj * float4(L, 0.0);
			if (pcP.w > 1e-4 && pcS.w > 1e-4) {
				fv = (pcP.xy / pcP.w * 0.5 + 0.5) * prevLiveV;
				toV = (pcS.xy / pcS.w * 0.5 + 0.5) * prevLiveV - fv;
				liveV = prevLiveV;
			}
		}
		int taps = int(p.crep.y);
		float open = 0.0, wsum = 0.0, w = 1.0;
		float openFar = 0.0, wFar = 0.0;
		int kFar = max(taps / 4, 1);
		bool smoothTaps = (int(p.sunPx.w) & 4) != 0;
		float xc = smoothTaps ? vlNoise(in.position.xy, p.temporal.z) : xi;
		constexpr sampler maskLin(filter::linear, address::clamp_to_edge);
		float2 maskTexel = 1.0 / float2(prevSky.get_width(), prevSky.get_height());
		for (int k = 0; k < taps; k++) {
			float s = (float(k) + xc) / float(taps);
			float2 q = fv + toV * (s * s);
			if (all(q >= 0.0) && all(q < liveV)) {
				float m = smoothTaps ? float(prevSky.sample(maskLin, clamp(q, 0.5, liveV - 0.5) * maskTexel).r)
				                     : float(prevSky.read(uint2(q)).r);
				open += m * w;
				if (k >= kFar) openFar += m * w;
			}
			wsum += w;
			if (k >= kFar) wFar += w;
			w *= 0.92;
		}
		open /= max(wsum, 1e-4);
		openFar /= max(wFar, 1e-4);
		float ang = saturate(mu);
		float fall = pow(ang, p.crep.w);
		if (d <= 0.0) {
			float fallX = p.art.w > 0.0 ? pow(ang, p.art.w) : fall;
			float extra = p.art.y * float(skyT * skyT) * (1.0 - (p.art.z > 0.5 ? openFar : open)) * fallX;
			if (VL_HDR) scatter -= p.light.rgb * (((1.0 - open) * fall + extra) * p.crep.x * p.crep.z * 1.5 * p.art.x);
			else scatter += p.light.rgb * (open * fall * p.crep.x * p.crep.z * 0.8);
		} else {
			float reach = saturate((dist + 6.0) / 48.0);
			float gaps = VL_HDR ? saturate((open - 0.25) / 0.75) : open;
			scatter += p.light.rgb * (gaps * fall * p.crep.x * p.crep.z * reach * (VL_HDR ? p.look2.x : 1.0));
		}
	}
	VlMarchOut o;
	o.light = half4(half3(clamp(scatter, -30000.0, 30000.0)), half(dist));
	o.sky = skyT;
	return o;
}

static inline half4 vlTemporalRaw(VlOut in, constant ShadeFrame &f, constant VlParams &p, texture2d<half> raw, texture2d<half> history) {
	constexpr sampler lin(filter::linear, address::clamp_to_edge);
	int2 px = int2(in.position.xy);
	int2 hi = int2(p.size.xy) - 1;
	half4 c = raw.read(uint2(px));
	float d = float(c.a);
	float3 sum = float3(c.rgb) * 2.0;
	float wsum = 2.0;
	float3 mn = float3(c.rgb), mx = mn;
	const int2 taps[4] = {int2(1, 0), int2(-1, 0), int2(0, 1), int2(0, -1)};
	for (int k = 0; k < 4; k++) {
		half4 s = raw.read(uint2(clamp(px + taps[k], int2(0), hi)));
		float w = exp(-abs(float(s.a) - d) / (0.03 * d + 0.3));
		sum += float3(s.rgb) * w;
		wsum += w;
		if (w > 0.35) {
			mn = min(mn, float3(s.rgb));
			mx = max(mx, float3(s.rgb));
		}
	}
	if ((int(p.sunPx.w) & 1) != 0) {
		const int2 diag[4] = {int2(1, 1), int2(-1, 1), int2(1, -1), int2(-1, -1)};
		for (int k = 0; k < 4; k++) {
			half4 s = raw.read(uint2(clamp(px + diag[k], int2(0), hi)));
			if (abs(float(s.a) - d) < 1.05 * (0.03 * d + 0.3)) {
				mn = min(mn, float3(s.rgb));
				mx = max(mx, float3(s.rgb));
			}
		}
	}
	float3 cur = sum / wsum;
	if (p.temporal.y > 0.5) return half4(half3(cur), half(d));
	float2 scale = p.size.zw / p.size.xy;
	float2 fpos = floor(in.position.xy * scale) + 0.5;
	float2 ndc = fpos / p.size.zw * 2.0 - 1.0;
	float3 dir = vlDirection(ndc, f);
	bool sky = d >= 9000.0;
	float4 pc = sky ? f.prevViewProj * float4(dir, 0.0) : f.prevViewProj * float4(shadeEye(f.invViewProj) + dir * d, 1.0);
	if (pc.w <= 1e-4) return half4(half3(cur), half(d));
	float2 puv = (pc.xy / pc.w) * 0.5 + 0.5;
	if (any(puv < 0.0) || any(puv > 1.0)) return half4(half3(cur), half(d));
	float2 hs = p.carve.z > 0.0 ? p.carve.zw : float2(1.0);
	float2 hdim = float2(history.get_width(), history.get_height());
	float2 hTexel = 0.5 / hdim;
	float dPrev = sky ? 1e4 : length(dir * d + p.camDelta.xyz);
	float tol = 0.08 * dPrev + 0.6;
	half4 h;
	bool valid;
	if ((int(p.sunPx.w) & 2) != 0) {
		float2 huv = clamp(puv * hs, hTexel, hs - hTexel);
		float2 hp = huv * hdim - 0.5;
		float2 hfl = floor(hp), hfr = hp - hfl;
		float4 hd4 = float4(history.gather(lin, (hfl + 1.0) / hdim, int2(0), component::w));
		if (all(abs(hd4 - dPrev) < tol)) {
			h = history.sample(lin, huv);
			valid = true;
		} else {
			int2 hb = int2(hfl), hhi = int2(hs * hdim + 0.5) - 1;
			float4 acc = 0.0;
			float wacc = 0.0;
			for (int j = 0; j <= 1; j++) {
				for (int i = 0; i <= 1; i++) {
					half4 s = history.read(uint2(clamp(hb + int2(i, j), int2(0), hhi)));
					float bw = (i == 0 ? 1.0 - hfr.x : hfr.x) * (j == 0 ? 1.0 - hfr.y : hfr.y) + 1e-4;
					if (abs(float(s.a) - dPrev) < tol) {
						acc += float4(s) * bw;
						wacc += bw;
					}
				}
			}
			valid = wacc > 0.0;
			h = half4(acc / max(wacc, 1e-6));
		}
	} else {
		h = history.sample(lin, clamp(puv * hs, hTexel, hs - hTexel));
		valid = abs(float(h.a) - dPrev) < tol;
	}
	if (!valid) return half4(half3(cur), half(d));
	float3 mid = 0.5 * (mn + mx), ext = 0.5 * (mx - mn) * 1.35 + 0.002 * (abs(mid) + 0.001);
	if (p.sunPx.z > 0.0) {
		float2 moved = puv * p.size.zw / scale - in.position.xy;
		float still = 1.0 - smoothstep(0.75, 2.0, length(moved));
		ext += still * p.sunPx.z * (abs(mid) + 0.5 * (mx - mn));
	}
	float3 hc = clamp(float3(h.rgb), mid - ext, mid + ext);
	return half4(half3(mix(cur, hc, p.temporal.x)), half(d));
}

static inline half4 vlTemporal(VlOut in, constant ShadeFrame &f, constant VlParams &p, texture2d<half> raw, texture2d<half> history) {
	half4 r = vlTemporalRaw(in, f, p, raw, history);
	return shadeBad(r) ? half4(0.0h, 0.0h, 0.0h, 10000.0h) : r;
}

fragment half4 vl_temporal(VlOut in [[stage_in]], constant ShadeFrame &f [[buffer(16)]], constant VlParams &p [[buffer(0)]],
                           texture2d<half> raw [[texture(0)]], texture2d<half> history [[texture(1)]]) {
	return vlTemporal(in, f, p, raw, history);
}

struct VlFoldOut {
	half4 history [[color(0)]];
	half4 lightT [[color(1)]];
};

static inline float vlVeilR(float3 light, float3 dir, float dist, constant VlParams &p) {
	return max(dot(light, float3(0.2126, 0.7152, 0.0722)), 0.0) / (vlMistDepth(dir, min(dist, p.shape.y), p) + 0.5);
}

fragment VlFoldOut vl_temporal_fold(VlOut in [[stage_in]], constant ShadeFrame &f [[buffer(16)]], constant VlParams &p [[buffer(0)]],
                                    constant VlState &st [[buffer(1)]], texture2d<half> raw [[texture(0)]], texture2d<half> history [[texture(1)]],
                                    texture2d<half> veil [[texture(2)]]) {
	VlFoldOut o;
	o.history = vlTemporal(in, f, p, raw, history);
	float3 light = float3(o.history.rgb);
	float T = 1.0;
	float canopy = p.gate.z >= 0.0 ? p.gate.z : max(st.canopy.x, p.tune.x);
	float mistK = p.light.w * canopy * p.mist.w * p.mist.z;
	bool air = p.water2.y < 0.5;
	bool hp = p.hp.w > 0.5 && p.temporal.y < 0.5 && air && canopy > 0.01;
	if ((mistK > 1e-5 && air) || hp) {
		float2 fpos = floor(in.position.xy * (p.size.zw / p.size.xy)) + 0.5;
		float2 ndc = fpos / p.size.zw * 2.0 - 1.0;
		float3 dir = vlDirection(ndc, f);
		float dist = float(o.history.a);
		if (hp) {
			float l = dot(light, float3(0.2126, 0.7152, 0.0722));
			if (l > 0.0) {
				constexpr sampler lin(filter::linear, address::clamp_to_edge);
				float2 vs = float2(veil.get_width(), veil.get_height());
				float2 live = ceil(p.size.xy * 0.25);
				float rbar = float(veil.sample(lin, clamp(in.position.xy * 0.25, float2(0.5), live - 0.5) / vs).r);
				float hw = saturate(canopy * 5.0);
				float keep = mix(1.0, p.hp.x, hw), gain = mix(1.0, p.hp.y, hw);
				float R = vlMistDepth(dir, min(dist, p.shape.y), p) + 0.5;
				light *= max(gain - (gain - keep) * rbar * R / l, 0.0);
			}
		}
		if (mistK > 1e-5 && air) {
			T = exp2(-1.442695 * mistK * vlMistDepth(dir, min(dist, p.shape.y), p));
			light += p.mistAmb.rgb * (1.0 - T);
		}
	}
	o.lightT = half4(half3(min(light, 30000.0)), half(T));
	return o;
}

fragment half4 vl_radial(VlOut in [[stage_in]], constant VlParams &p [[buffer(0)]], texture2d<half> lightT [[texture(0)]],
                         texture2d<half> hist [[texture(1)]]) {
	constexpr sampler lin(filter::linear, address::clamp_to_edge);
	float2 pos = in.position.xy;
	half4 c = lightT.read(uint2(pos));
	int n = int(p.radial.w);
	float2 ndc = pos / p.size.xy * 2.0 - 1.0;
	float2 d = (p.radial.xy - ndc * p.radial.z) * p.size.xy;
	float len = length(d);
	if (n <= 0 || len < 1e-4) return c;
	d *= p.tune2.w / len;
	float dc = float(hist.read(uint2(pos)).a);
	float tol = 0.06 * dc + 0.6;
	float2 tex = 1.0 / float2(lightT.get_width(), lightT.get_height());
	float2 hi = p.size.xy - 0.5;
	int2 last = int2(p.size.xy) - 1;
	float3 sum = float3(c.rgb);
	float wsum = 1.0, inv = 2.0 / float(n * n);
	for (int k = 1; k <= n; k++) {
		float wk = exp(-float(k * k) * inv);
		for (int sg = -1; sg <= 1; sg += 2) {
			float2 q = clamp(pos + d * float(k * sg), float2(0.5), hi);
			float ds = float(hist.read(uint2(clamp(int2(q), int2(0), last))).a);
			float w = wk * (abs(ds - dc) < tol ? 1.0 : 0.1);
			sum += float3(lightT.sample(lin, q * tex).rgb) * w;
			wsum += w;
		}
	}
	return half4(half3(sum / wsum), c.a);
}

fragment half vl_veil_down(VlOut in [[stage_in]], constant ShadeFrame &f [[buffer(16)]], constant VlParams &p [[buffer(0)]],
                           texture2d<half> history [[texture(0)]]) {
	int2 base = int2(in.position.xy) * 4, hi = int2(p.size.xy) - 1;
	float2 scale = p.size.zw / p.size.xy;
	float sum = 0.0;
	for (int j = 0; j < 4; j++) {
		for (int i = 0; i < 4; i++) {
			int2 q = min(base + int2(i, j), hi);
			half4 h = history.read(uint2(q));
			float2 fpos = floor((float2(q) + 0.5) * scale) + 0.5;
			sum += vlVeilR(float3(h.rgb), vlDirection(fpos / p.size.zw * 2.0 - 1.0, f), float(h.a), p);
		}
	}
	return half(min(sum * (1.0 / 16.0), 60000.0));
}

static inline half vlVeilBlur(float2 pos, float2 axis, constant VlParams &p, texture2d<half> src) {
	constexpr sampler lin(filter::linear, address::clamp_to_edge);
	float2 vs = float2(src.get_width(), src.get_height());
	float2 lo = float2(0.5), hi = ceil(p.size.xy * 0.25) - 0.5;
	const float w[5] = {0.2270270, 0.1945946, 0.1216216, 0.0540541, 0.0162162};
	float sum = w[0] * float(src.sample(lin, pos / vs).r);
	for (int k = 1; k < 5; k++) {
		float2 o = axis * (float(k) * p.hp.z);
		sum += w[k] * (float(src.sample(lin, clamp(pos + o, lo, hi) / vs).r) + float(src.sample(lin, clamp(pos - o, lo, hi) / vs).r));
	}
	return half(sum);
}
fragment half vl_veil_blur_h(VlOut in [[stage_in]], constant VlParams &p [[buffer(0)]], texture2d<half> src [[texture(0)]]) {
	return vlVeilBlur(in.position.xy, float2(1.0, 0.0), p, src);
}
fragment half vl_veil_blur_v(VlOut in [[stage_in]], constant VlParams &p [[buffer(0)]], texture2d<half> src [[texture(0)]]) {
	return vlVeilBlur(in.position.xy, float2(0.0, 1.0), p, src);
}

static inline half3 vlForward(half3 c) {
	half m = max(max(c.r, c.g), max(c.b, 1e-4h));
	half y = 1.0h - exp2(-1.5869637h * m);
	half tm = y * y * 1.006h / (y + 0.006h);
	return shadeToSrgb(saturate(c * (tm / m)));
}

static inline half3 vlInverse(half3 s) {
	half3 lin = shadeToLinear(s);
	half m = max(max(lin.r, lin.g), max(lin.b, 1e-4h));
	half y = min((m + sqrt(m * m + 0.024144h * m)) * 0.4970179h, 0.996h);
	half x = -log2(1.0h - y) * 0.6301338h;
	return lin * (x / m);
}

static inline half3 vlUpsample(texture2d<half> vl, float2 pos, float dist, constant VlParams &p) {
	float2 lp = pos * (p.size.xy / p.size.zw) - 0.5;
	float2 fl = floor(lp);
	half2 fr = half2(lp - fl);
	int2 base = int2(fl), hi = int2(p.size.xy) - 1;
	half invTol = half(1.0 / (0.04 * dist + 0.15));
	half hd = half(min(dist, 3e4));
	half3 sum = 0.0h, bestC = 0.0h;
	half wsum = 0.0h, best = 6e4h, bestD = 1.0h;
	for (int j = 0; j <= 1; j++) {
		for (int i = 0; i <= 1; i++) {
			half4 s = vl.read(uint2(clamp(base + int2(i, j), int2(0), hi)));
			half bw = (i == 0 ? 1.0h - fr.x : fr.x) * (j == 0 ? 1.0h - fr.y : fr.y) + 1e-3h;
			half diff = abs(s.a - hd);
			half x = diff * invTol;
			half w = bw / (1.0h + x * x * (1.0h + x));
			sum += s.rgb * w;
			wsum += w;
			if (diff < best) {
				best = diff;
				bestC = s.rgb;
				bestD = s.a;
			}
		}
	}
	if (wsum > 0.02h) return sum / wsum;
	half maxD = half(p.shape.y);
	return bestC * saturate((min(hd, maxD) + 0.5h) / (min(bestD, maxD) + 0.5h));
}

static inline half3 vlCompositeLight(float2 pos, float dist, float2 ndc, float exposure, constant ShadeFrame &f, constant VlParams &p,
                                     constant VlState &st, texture2d<half> vl, thread half &T) {
	half3 light = max(vlUpsample(vl, pos, dist, p), 0.0h) * half(exposure);
	T = 1.0h;
	float canopy = p.gate.z >= 0.0 ? p.gate.z : max(st.canopy.x, p.tune.x);
	float mistK = p.light.w * canopy * p.mist.w * p.mist.z;
	if (mistK > 1e-5 && p.water2.y < 0.5) {
		T = half(exp2(-1.442695 * mistK * vlMistDepth(vlDirection(ndc, f), min(dist, p.shape.y), p)));
		light += half3(p.mistAmb.rgb * exposure) * (1.0h - T);
	}
	return light;
}

fragment half4 vl_composite(VlOut in [[stage_in]], half4 dst [[color(0)]], constant ShadeFrame &f [[buffer(16)]],
                             constant VlParams &p [[buffer(0)]], constant VlState &st [[buffer(1)]], texture2d<half> vl [[texture(0)]],
                             depth2d<float> depth [[texture(1)]]) {
	uint2 px = uint2(in.position.xy);
	float2 ndc = in.position.xy / p.size.zw * 2.0 - 1.0;
	float dist = vlDistance(ndc, depth.read(px), f);
	half T;
	half3 light = vlCompositeLight(in.position.xy, dist, ndc, f.time.z, f, p, st, vl, T);
	if (p.gate.w == 1.0) return half4(vlForward(light), 1.0h);
	if (p.gate.w == 2.0) return half4(half3(saturate(dist / p.shape.y)), 1.0h);
	if (p.gate.w == 3.0) return half4(half3(T), 1.0h);
	if (max(light.r, max(light.g, light.b)) < half(1.0 / 1024.0) && T > 0.999h) return dst;
	half3 e = vlInverse(dst.rgb);
	half3 o = dst.rgb + (vlForward(e * T + light) - vlForward(e));
	float n0 = vlNoise(in.position.xy, p.temporal.z), n1 = vlNoise(in.position.xy + 17.0, p.temporal.z + 3.0);
	o += half(n0 + n1 - 1.0) * (1.0h / 255.0h);
	return half4(saturate(o), dst.a);
}

fragment half4 vl_composite_hdr(VlOut in [[stage_in]], constant ShadeFrame &f [[buffer(16)]], constant ShadeGpu &g [[buffer(18)]],
                                 constant VlParams &p [[buffer(0)]], constant VlState &st [[buffer(1)]], texture2d<half> vl [[texture(0)]],
                                 depth2d<float> depth [[texture(1)]]) {
	uint2 px = uint2(in.position.xy);
	float2 ndc = in.position.xy / p.size.zw * 2.0 - 1.0;
	float dist = vlDistance(ndc, depth.read(px), f);
	half T;
	half3 light = vlCompositeLight(in.position.xy, dist, ndc, g.exposure.x, f, p, st, vl, T);
	if (p.gate.w == 1.0) return half4(light, 0.0h);
	if (p.gate.w == 2.0) return half4(half3(saturate(dist / p.shape.y) * 0.5), 0.0h);
	if (p.gate.w == 3.0) return half4(half3(T * 0.5h), 0.0h);
	return half4(min(light, half3(30000.0h)), T);
}

#endif
