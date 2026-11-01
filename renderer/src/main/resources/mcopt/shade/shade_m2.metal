// Copyright 2026 mcopt-shade contributors
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
// License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
// law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
// OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
// limitations under the License.
//
// M2 shading functions, loaded after shade_look.metal and shade_common.metal (ShadeMsl.prelude):
//   - output: shadeOut / shadeFinish2 write pre-exposed linear radiance when the pipeline is built for the HDR world target
//     (MCOPT_HDR, defined by the format adapter for those variants), else M1's tone curve into RGBA8;
//   - lighting model v2 (shadeLit2), PCSS-lite shadows (shadeShadow2), height haze + mist + border fog (shadeFogAir2),
//   - the sky: procedural sun disc and aureole, moon with phases, stars and the Milky Way (shadeSkyColor2).
// Everything here is original (techniques: Lottes 2016, Hillaire 2015, Jimenez 2014 IGN, Neckel & Labs limb darkening,
// Lommel-Seeliger, Planck's law; see the bible's E01-E09). The integer hash is Chris Wellons' "lowbias32" (public domain).
#ifndef MCOPT_SHADE_M2
#define MCOPT_SHADE_M2

static inline uint shadeHash(uint x) {
	x ^= x >> 16; x *= 0x7feb352du; x ^= x >> 15; x *= 0x846ca68bu; x ^= x >> 16;
	return x;
}
static inline float shadeHash01(uint x) { return float(shadeHash(x) >> 8) * (1.0 / 16777216.0); }
static inline uint shadeHash3(int3 c, uint salt) {
	return shadeHash(uint(c.x) * 0x8da6b343u ^ shadeHash(uint(c.y) * 0xd8163841u ^ shadeHash(uint(c.z) * 0xcb1ab31fu ^ salt)));
}
static inline bool shadeFeature(constant ShadeLook &L, int bit) { return (L.tier.y & bit) != 0; }

static inline float shadeHG(float mu, float g) {
	float g2 = g * g;
	float x = max(1.0 + g2 - 2.0 * g * mu, 1e-4);
	return 0.0795775 * (1.0 - g2) / (x * sqrt(x));
}

static inline float shadeExposure(constant ShadeFrame &f, constant ShadeGpu &g) {
#ifdef MCOPT_HDR
	return g.exposure.x;
#else
	return f.time.z;
#endif
}

static inline float3 shadeOut(float3 radiance, constant ShadeFrame &f, float ex) {
#ifdef MCOPT_HDR
	return min(max(radiance, 0.0) * ex, 60000.0);
#else
	return shadeOutput(radiance, f);
#endif
}

static inline float3 shadeVanillaOut(float3 srgb, constant ShadeLook &L) {
#ifdef MCOPT_HDR
	return shadeLdrToHdr(srgb, L);
#else
	return srgb;
#endif
}

static inline float3 shadeFinish2(float3 radiance, float vanillaFog, constant ShadeFrame &f, constant ShadeLook &L, float ex) {
#ifdef MCOPT_HDR
	float3 c = shadeOut(radiance, f, ex);
	float a = vanillaFog * f.fogColor.a;
	if (a <= 0.0) return c;
	float3 t = f.flags.y == 1 ? pow(1.0 - a, float3(2.6, 1.25, 1.0)) : float3(1.0 - a);
	return c * t + shadeLdrToHdr(f.fogColor.rgb, L) * (1.0 - t);
#else
	return shadeFinish(radiance, vanillaFog, f);
#endif
}

static float3 shadeLit2(half3 albedo, half3 n, half sky, half block, half shadow, bool thin, bool foliage, constant ShadeFrame &f,
                        constant ShadeLook &L) {
	if (!shadeFeature(L, SHADE_FEATURE_LIGHT_V2)) return shadeLit(albedo, n, sky, block, shadow, thin, foliage, f);
	float3 Ld = f.lightDirWorld.xyz;
	half nl = half(dot(float3(n), Ld));
	half ndl = thin ? half(0.3) + half(0.5) * abs(nl)
		: foliage ? half(0.25) + half(0.75) * saturate(nl) + half(0.5 * L.user0.z) * saturate(-nl)
		: (nl > half(0.0) ? (nl + half(0.08)) / half(1.08) : half(0.0));
	half gate = f.flags.z != 0 ? smoothstep(half(0.3), half(0.8), sky) : sky * sky * sky * sky;
	half3 sun = half3(f.sunColor.xyz);
	half3 direct = sun * (ndl * shadow * gate);
	float2 axis = shadeSkyAxis(f);
	half towardSun = half(dot(float2(n.xz), axis)) * half(0.5) + half(0.5);
	half3 side = mix(half3(f.ambientAway.xyz), half3(f.ambientToward.xyz), towardSun);
	half3 ambient = n.y >= half(0.0) ? mix(side, half3(f.ambientSky.xyz), n.y) : mix(side, half3(f.ambientGround.xyz), -n.y);
	half g = shadeSkyCurve(sky);
	ambient *= g * (foliage ? half(1.0 + 0.35 * L.user0.z) : half(1.0)) * half3(L.ambientArt.rgb);
	half3 fill = sun * (half(L.ambientArt.w) * (half(1.0) - shadow * saturate(nl)) * g * g * gate) * half3(L.bounce.rgb);
	half b7 = block * block * block; b7 = b7 * b7 * block;
	half F = (half(2.2) * b7 + half(0.55) * pow(max(block, half(1e-3)), half(2.4))) * half(1.0 / 2.75);
	half day = half(saturate(f.sunDirWorld.w * 4.0 + 0.2));
	half3 torch = half3(f.ambientBlock.xyz) * F * (half(1.0) - half(0.25) * smoothstep(half(0.5), half(1.0), sky) * day);
	half3 minimum = half(f.ambientBlock.w) * mix(half3(0.9h, 1.0h, 1.38h), half3(1.0h), g);
	if (L.user3.x > 0.0) {
		half natural = half(min(L.user3.x, 1.0));
		ambient *= mix(half(1.0), mix(half(0.45), half(0.85), g), natural);
		fill *= mix(half(1.0), mix(half(0.35), half(0.65), g), natural);
		minimum *= mix(half(1.0), half(0.65), natural);
		if (L.user3.x > 1.0) {
			half extra = half(L.user3.x - 1.0);
			ambient *= mix(half(1.0), mix(half(0.65), half(0.90), g), extra);
			fill *= mix(half(1.0), mix(half(0.60), half(0.85), g), extra);
			minimum *= mix(half(1.0), half(0.75), extra);
		}
	}
	if (SHADE_CAMERA_UNDERWATER(f)) {
		direct *= half3(0.22h, 0.5h, 0.62h);
		ambient *= half3(0.35h, 0.62h, 0.8h);
		fill *= half3(0.3h, 0.55h, 0.7h);
	}
	half face = half(1.0) - half(0.06) * abs(n.x) * (half(1.0) - abs(n.y));
	return float3(albedo) * float3((direct + ambient + fill + torch + minimum) * face);
}

constant float2 SHADE_VOGEL[16] = {
	float2(0.1768, 0.0000), float2(-0.2263, 0.2072), float2(0.0388, -0.3934), float2(0.2990, 0.3399),
	float2(-0.5175, -0.0889), float2(0.4563, -0.3366), float2(-0.1529, 0.5996), float2(-0.3555, -0.5671),
	float2(0.6847, 0.2108), float2(-0.6649, 0.3468), float2(0.2700, -0.7279), float2(0.3346, 0.7428),
	float2(-0.7958, -0.2903), float2(0.8215, -0.3082), float2(-0.3472, 0.8343), float2(-0.2857, -0.9108)};

constant float2 SHADE_VOGEL8[8] = {
	float2(0.2500, 0.0000), float2(-0.3201, 0.2931), float2(0.0549, -0.5564), float2(0.4228, 0.4807),
	float2(-0.7319, -0.1257), float2(0.6453, -0.4760), float2(-0.2162, 0.8480), float2(-0.5028, -0.8020)};
#define SHADE_PCSS_TAPS 8
#define SHADE_PCSS_FAR 40.0

static half shadeShadow2(depth2d_array<float> map, sampler cmp, constant ShadeFrame &f, constant ShadeLook &L, float3 p, half3 n, bool thin,
                         bool foliage, float2 pixel, half sky) {
	if (f.flags.z == 0 || shadeDev(f, SHADE_DEV_NO_SHADOW)) return half(1.0);
	if (!shadeFeature(L, SHADE_FEATURE_PCSS)) return shadeShadow(map, cmp, f, p, n, thin);
	float3 Ld = f.lightDirWorld.xyz;
	float nl = thin ? 1.0 : dot(float3(n), Ld);
	if (nl <= 0.0) return half(1.0);
	float tanT = clamp(sqrt(max(1.0 - nl * nl, 0.0)) / max(nl, 0.02), 0.0, 12.0);
	float3 dir = thin ? Ld * 1.5 : float3(n) * 0.75 + Ld * (0.5 + 0.75 * tanT);
	float texelUv = f.shadowParams.x;
	float4 c = f.shadowViewProj[0] * float4(p + dir * f.cascadeSplits.z, 1.0);
	float m0 = max(abs(c.x), abs(c.y));
	if (c.z <= 0.0 || c.z >= 1.0) m0 = 2.0;
	float nearVis = 1.0;
	if (m0 < 1.0 && length(p) > SHADE_PCSS_FAR) {
		float mm;
		nearVis = shadeShadowCascade(map, cmp, 0u, f.shadowViewProj[0], p, dir * f.cascadeSplits.z, texelUv, 0.75, mm);
		if (m0 < 0.85) return half(nearVis);
	} else if (m0 < 1.0) {
		float2 uv = shadeShadowUV(c.xyz);
		float blocksPerTexel = f.cascadeSplits.z;
		float zBlocks = 1.0 / max(length(float3(f.shadowViewProj[0][0][2], f.shadowViewProj[0][1][2], f.shadowViewProj[0][2][2])), 1e-6);
		float tanSun = tan(L.user0.x * 1.6) * (foliage ? 1.5 : 1.0);
		float searchTex = clamp(tanSun * 16.0 / blocksPerTexel, 1.5, 6.0);
#ifdef SHADE_PCSS_TAA
		float rot = shadeNoise(pixel, f.flags2.x) * 6.2831853;
#else
		float rot = shadeNoise(pixel, 0) * 6.2831853;
#endif
		float2 cs = float2(cos(rot), sin(rot));
		float2x2 R = float2x2(cs.x, cs.y, -cs.y, cs.x);
		constexpr sampler pt(filter::nearest, address::clamp_to_edge);
		float2 o = R * float2(0.7, 0.25) * searchTex * texelUv;
		float4 d0 = map.gather(pt, uv + o, 0u);
		float4 d1 = map.gather(pt, uv - o, 0u);
		float4 b0 = select(float4(0.0), float4(1.0), d0 > c.z), b1 = select(float4(0.0), float4(1.0), d1 > c.z);
		float count = dot(b0 + b1, float4(1.0));
		if (count > 0.0) {
			float zb = (dot(d0, b0) + dot(d1, b1)) / count;
			float wTex = clamp((zb - c.z) * zBlocks * tanSun / blocksPerTexel, 0.6, searchTex);
			float s = 0.0;
			for (int i = 0; i < SHADE_PCSS_TAPS; i++) s += map.sample_compare(cmp, uv + R * SHADE_VOGEL8[i] * (wTex * texelUv), 0u, c.z);
			s *= 1.0 / float(SHADE_PCSS_TAPS);
			float k = clamp(2.0 / wTex, 1.0, 3.0);
			nearVis = saturate((s - 0.5) * k + 0.5);
		} else {
			nearVis = map.sample_compare(cmp, uv, 0u, c.z);
		}
		float lp = length(p);
		if (lp > SHADE_PCSS_FAR - 6.0) {
			float mt;
			float tent = shadeShadowCascade(map, cmp, 0u, f.shadowViewProj[0], p, dir * f.cascadeSplits.z, texelUv, 0.75, mt);
			nearVis = mix(nearVis, tent, smoothstep(SHADE_PCSS_FAR - 6.0, SHADE_PCSS_FAR, lp));
		}
		if (m0 < 0.85) return half(nearVis);
	}
	float m1;
	float far = shadeShadowCascade(map, cmp, 1u, f.shadowViewProj[1], p, dir * f.cascadeSplits.w, texelUv, 0.5, m1);
	far = mix(far, float(smoothstep(half(0.75), half(0.95), sky)), smoothstep(0.85, 1.0, m1));
	return half(m0 >= 1.0 ? far : mix(nearVis, far, smoothstep(0.85, 1.0, m0)));
}

static inline float shadeLayerDepth(float yc, float dy, float t, float y0, float H) {
	float y1 = yc + dy * t;
	float lo = min(yc, y1), hi = max(yc, y1);
	if (hi - lo < 1e-3 * max(t, 1.0)) return t * exp(-max(0.5 * (yc + y1) - y0, 0.0) / H);
	float below = clamp(y0 - lo, 0.0, hi - lo);
	float a = max(lo, y0);
	float above = hi > a ? H * (exp(-(a - y0) / H) - exp(-(hi - y0) / H)) : 0.0;
	return (below + above) * t / (hi - lo);
}

static inline float3 shadeHazeTransmittance(float3 p, constant ShadeFrame &f, constant ShadeLook &L) {
	float t = length(p);
	float dy = p.y / max(t, 1e-4);
	float yc = f.cameraBlock.y + f.cameraFract.y;
	float dR = shadeLayerDepth(yc, dy, t, L.fogMisc.x, L.fogR.w);
	float dM = shadeLayerDepth(yc, dy, t, L.fogM.z, L.fogM.y);
	return exp(-(L.fogR.rgb * dR + L.fogM.x * dM));
}

static float3 shadeHazeInscatterFrom(float3 p, float yc, constant ShadeFrame &f, constant ShadeLook &L, float sunVis, thread float3 &T,
                                     thread float3 &Ssun) {
	float t = length(p);
	float3 v = p / max(t, 1e-4);
	float dR = shadeLayerDepth(yc, v.y, t, L.fogMisc.x, L.fogR.w);
	float dM = shadeLayerDepth(yc, v.y, t, L.fogM.z, L.fogM.y);
	float3 tauR = L.fogR.rgb * dR;
	float tauM = L.fogM.x * dM;
	float3 tau = tauR + tauM;
	T = exp(-tau);
	float nu = dot(v, f.lightDirWorld.xyz);
	float phR = 0.0596831 * (1.0 + nu * nu);
#ifndef SHADE_HAZE_MIE_G
#define SHADE_HAZE_MIE_G 0.76
#endif
	float phM = 0.8 * shadeHG(nu, SHADE_HAZE_MIE_G) + 0.2 * shadeHG(nu, -0.2);
	float airSun = shadeAirSun(L);
	float3 E = f.sunColor.rgb * (3.14159265 * sunVis * airSun);
	float3 sky = f.ambientSky.rgb * (0.9 * shadeAirSky(L));
	float glow = 1.0 + L.fogMisc.w;
	float aM = L.fogM.w;
	float3 sR = (E * phR + sky) * (1.0 / (1.0 - 0.5));
	float3 sM = (E * phM * glow + sky) * aM / (1.0 - 0.5 * aM);
	float3 w = (1.0 - T) / max(tau, 1e-6);
	Ssun = w * E * (tauR * (phR * 2.0) + tauM * (phM * glow * aM / (1.0 - 0.5 * aM)));
	float cave = (1.0 - airSun) * L.eye.x * f.ambientBlock.w;
	return w * (tauR * sR + tauM * sM) + (1.0 - T) * cave;
}
static float3 shadeHazeInscatterFrom(float3 p, float yc, constant ShadeFrame &f, constant ShadeLook &L, float sunVis, thread float3 &T) {
	float3 Ssun;
	return shadeHazeInscatterFrom(p, yc, f, L, sunVis, T, Ssun);
}
static float3 shadeHazeInscatter(float3 p, constant ShadeFrame &f, constant ShadeLook &L, float sunVis, thread float3 &T) {
	return shadeHazeInscatterFrom(p, f.cameraBlock.y + f.cameraFract.y, f, L, sunVis, T);
}
static float3 shadeHazeInscatter(float3 p, constant ShadeFrame &f, constant ShadeLook &L, float sunVis, thread float3 &T, thread float3 &Ssun) {
	return shadeHazeInscatterFrom(p, f.cameraBlock.y + f.cameraFract.y, f, L, sunVis, T, Ssun);
}

static float3 shadeFogAir2(float3 radiance, float3 p, float vanillaFog, float chunkFade, texture2d<half> lut, sampler s,
                           constant ShadeFrame &f, constant ShadeLook &L, constant ShadeResources *R = nullptr) {
	if (!shadeFeature(L, SHADE_FEATURE_HAZE)) return shadeFogAir(radiance, p, vanillaFog, lut, s, f);
	float d = length(p);
	float3 v = p / max(d, 1e-4);
	bool air = f.fogColor.a < 0.5;
	float ap = (1.0 - exp(-d * f.fogSky.z)) * f.fogSky.w * (1.0 - f.fogColor.a);
	bool smoothAir = shadeAirSmooth(L);
	if (smoothAir) ap *= shadeAirSky(L);
	float apMix = smoothAir ? ap * 0.6 * smoothstep(0.002, 0.004, ap) : (ap > 0.002 ? ap * 0.6 : 0.0);
	if (shadeCloudDev(L, SHADE_CLOUD_DEV_NO_AP)) apMix = 0.0;
	float3 skyBehind = float3(0.0);
	float edge = 0.0;
	if (air) {
		if (L.fogMisc.z > 0.0) {
			float2 q = p.xz * p.xz;
			float l4 = sqrt(sqrt(dot(q, q)));
			float Rb = L.fogMisc.z;
			edge = max(smoothstep(L.fogMisc.y * Rb, Rb, l4), chunkFade);
		} else {
			edge = vanillaFog;
		}
	}
	bool needSky = apMix > 0.0 || (air && L.fogMisc.z > 0.0 && edge > 0.002);
	if (needSky) {
		float3 vs = float3(v.x, max(v.y, 0.0), v.z);
		skyBehind = shadeSkyLookup(vs, lut, s, f);
		if (R != nullptr && L.clouds0.w > 0.5) {
			float wc = v.y >= 0.0 ? 1.0 : smoothstep(0.0, 24.0, L.clouds0.x);
			if (!shadeCloudDev(L, SHADE_CLOUD_DEV_OLD_FOG)) {
				if (shadeCloudDev(L, SHADE_CLOUD_DEV_EARLY_FOG)) {
					wc *= smoothstep(0.015, 0.035, max(ap * 0.6, edge));
				} else {
					float late = saturate(edge * 3.333333 - 2.333333);
					wc *= late * late;
				}
			}
			if (shadeCloudDev(L, SHADE_CLOUD_DEV_NO_FOG)) wc = 0.0;
			if (wc > 0.0) {
				float4 cl = shadeCloudsScreen(v, L, R->cloudScreen);
				skyBehind = skyBehind * mix(1.0, cl.a, wc) + cl.rgb * wc;
			}
		}
	}
	if (apMix > 0.0) radiance = mix(radiance, skyBehind, apMix);
#ifndef MCOPT_HDR
	if (air && (smoothAir || d > 2.0)) {
		float3 T;
		float3 S = shadeHazeInscatter(p, f, L, 1.0, T);
		radiance = radiance * T + S;
	}
#endif
	if (air && edge > 0.002) radiance = mix(radiance, needSky ? skyBehind : shadeSkyLookup(float3(v.x, max(v.y, 0.0), v.z), lut, s, f), edge);
	return radiance;
}

static inline float3 shadeAerialSunGlow(float3 p, texture2d<half> lut, sampler s, constant ShadeFrame &f, constant ShadeLook &L) {
	if (shadeCloudDev(L, SHADE_CLOUD_DEV_AP_SUN) || shadeCloudDev(L, SHADE_CLOUD_DEV_NO_AP) || !shadeFeature(L, SHADE_FEATURE_HAZE)) return float3(0.0);
	float d = length(p);
	float3 v = p / max(d, 1e-4);
	if (dot(v.xz, shadeSkyAxis(f)) <= 0.0) return float3(0.0);
	float ap = (1.0 - exp(-d * f.fogSky.z)) * f.fogSky.w * (1.0 - f.fogColor.a);
	bool smoothAir = shadeAirSmooth(L);
	if (smoothAir) ap *= shadeAirSky(L);
	float apMix = smoothAir ? ap * 0.6 * smoothstep(0.002, 0.004, ap) : (ap > 0.002 ? ap * 0.6 : 0.0);
	if (apMix <= 0.0) return float3(0.0);
	float3 vs = float3(v.x, max(v.y, 0.0), v.z);
	return apMix * max(shadeSkyLookup(vs, lut, s, f) - shadeSkyLookup(vs, lut, s, f, 0.7071068), 0.0);
}

static inline float3 shadeBlackbody(float kelvin) {
	const float3 lam = float3(610e-9, 550e-9, 465e-9);
	float3 e = exp(1.4388e-2 / (lam * kelvin)) - 1.0;
	float3 b = 1.0 / (lam * lam * lam * lam * lam * e);
	b /= max(dot(b, float3(0.2126, 0.7152, 0.0722)), 1e-30);
	return mix(float3(1.0), b, 0.55);
}

static float3 shadeStars(float3 s, float airmass, float time, float sigmaCells) {
	float3 a = abs(s);
	float2 uv; uint face;
	if (a.x >= a.y && a.x >= a.z) { uv = s.yz / a.x; face = s.x > 0.0 ? 0u : 1u; }
	else if (a.y >= a.z) { uv = s.xz / a.y; face = s.y > 0.0 ? 2u : 3u; }
	else { uv = s.xy / a.z; face = s.z > 0.0 ? 4u : 5u; }
	const float N = 420.0;
	float2 cell = (uv * 0.5 + 0.5) * N;
	float2 base = floor(cell - 0.5);
	float3 sum = float3(0.0);
	for (int j = 0; j < 4; j++) {
		int2 c = int2(base) + int2(j & 1, j >> 1);
		uint h = shadeHash3(int3(c, int(face)), 0x5bd1e995u);
		if (shadeHash01(h) > 0.07) continue;
		float2 pos = float2(c) + 0.2 + 0.6 * float2(shadeHash01(h ^ 0x68e31da4u), shadeHash01(h ^ 0xb5297a4du));
		float2 dd = cell - pos;
		float r2 = dot(dd, dd);
		float sig2 = sigmaCells * sigmaCells;
		if (r2 > 9.0 * sig2) continue;
		float mag = -1.0 + 7.5 * pow(shadeHash01(h ^ 0x1b56c4e9u), 0.45);
		float flux = exp2(-1.328771 * mag);
		float pick = shadeHash01(h ^ 0x7f4a7c15u);
		float kelvin = pick < 0.55 ? mix(3200.0, 5200.0, pick / 0.55) : pick < 0.9 ? mix(5200.0, 7500.0, (pick - 0.55) / 0.35)
			: mix(7500.0, 15000.0, (pick - 0.9) / 0.1);
		float ph = shadeHash01(h ^ 0x2545f491u) * 64.0;
		float tw0 = shadeHash01(h ^ uint(floor(time * 7.0 + ph))), tw1 = shadeHash01(h ^ uint(floor(time * 7.0 + ph) + 1.0));
		float twinkle = 1.0 + 0.35 * min(airmass, 4.0) * (mix(tw0, tw1, fract(time * 7.0 + ph)) - 0.5);
		sum += shadeBlackbody(kelvin) * (flux * twinkle * exp(-0.5 * r2 / sig2));
	}
	return sum;
}

static inline float3 shadeMilkyWay(float3 s, texture2d<half> mw) {
	const float ct = 0.4540, st = 0.8910;
	float3 g = float3(s.x, ct * s.y - st * s.z, st * s.y + ct * s.z);
	float lon = atan2(g.y, g.x), lat = asin(clamp(g.z, -1.0, 1.0));
	constexpr sampler lin(filter::linear, address::repeat);
	return float3(mw.sample(lin, float2(lon * 0.1591549 + 0.5, lat * 0.3183099 + 0.5), level(0.0)).rgb);
}

static float3 shadeMoonDisc(float2 q, float3 md, float3 pole, float phase) {
	float r2 = dot(q, q);
	float mu = sqrt(max(1.0 - r2, 0.0));
	float3 nrm = float3(q, mu);
	float3 ls = float3(sin(phase), 0.0, cos(phase));
	float mu0 = dot(nrm, ls);
	float2 sp = float2(atan2(nrm.x, nrm.z), asin(clamp(nrm.y, -1.0, 1.0)));
	float maria = 0.0;
	maria += smoothstep(0.55, 0.15, length(sp - float2(-0.35, 0.25)));
	maria += 0.8 * smoothstep(0.45, 0.1, length(sp - float2(0.25, 0.35)));
	maria += 0.7 * smoothstep(0.40, 0.05, length(sp - float2(0.05, -0.2)));
	maria += 0.5 * smoothstep(0.30, 0.05, length(sp - float2(-0.55, -0.35)));
	float albedo = mix(0.17, 0.09, saturate(maria));
	float2 cg = sp * 7.0;
	float2 ci = floor(cg);
	for (int j = 0; j < 4; j++) {
		float2 c = ci + float2(j & 1, j >> 1) - 0.5;
		uint h = shadeHash3(int3(int2(c), 7), 0x9e3779b9u);
		float2 center = c + 0.5 + 0.4 * (float2(shadeHash01(h), shadeHash01(h ^ 0x85ebca6bu)) - 0.5);
		float rad = 0.12 + 0.25 * shadeHash01(h ^ 0xc2b2ae35u);
		float d = length(cg - center) / rad;
		albedo *= d < 1.0 ? mix(0.82, 1.0, d * d) : 1.0 + 0.25 * exp(-(d - 1.0) * (d - 1.0) * 30.0);
	}
	float lit = mu0 > 0.0 ? mu0 / (mu0 + max(mu, 0.05)) : 0.0;
	float earthshine = 0.012;
	float limb = smoothstep(1.0, 0.97, sqrt(r2));
	return float3(albedo * (lit * 3.2 + earthshine)) * limb;
}

static float3 shadeSkyRadiance2(float3 dir, texture2d<half> lut, sampler s, constant ShadeFrame &f, constant ShadeLook &L,
                                texture2d<half> milkyWay, float pixelAngle) {
	if (shadeDev(f, SHADE_DEV_FLAT_SKY)) return float3(0.3, 0.5, 0.8);
	float3 sky = shadeSkyLookup(dir, lut, s, f);
	float rain = f.weather.x;
	float above = smoothstep(-0.03, 0.01, dir.y);
	float3 sd = f.sunDirWorld.xyz;
	float cosA = dot(dir, sd);
	float sunUp = smoothstep(-0.12, 0.0, sd.y);
	if (sunUp > 0.0 && f.sunColor.w >= 0.0) {
		float3 sunLight = f.lightDirWorld.w > 0.5 ? f.sunColor.rgb : float3(0.0);
		float aureole = (shadeHG(cosA, 0.85) - shadeHG(cosA, 0.6)) * 0.35 * f.atmosphere.z * (1.0 - 0.7 * rain);
		sky += sunLight * max(aureole, 0.0) * above;
		float rs = L.sunGeom.y;
		float ang = acos(clamp(cosA, -1.0, 1.0));
		float rho = ang / rs;
		if (rho < 1.3) {
			float w = 1.5 * pixelAngle / rs;
			float mu = sqrt(saturate(1.0 - rho * rho));
			float3 u = float3(0.40, 0.55, 0.70), v = float3(0.25, 0.20, 0.12);
			float3 I = 1.0 - u * (1.0 - mu) - v * (1.0 - mu) * (1.0 - mu);
			float edge = smoothstep(1.0 + w, 1.0 - w, rho);
			sky += sunLight * (160.0 * (1.0 - 0.9 * rain)) * I * edge * above;
		}
	}
	bool procedural = shadeFeature(L, SHADE_FEATURE_STARS);
	float night = L.night.x;
	if (procedural && (night > 0.0 || dot(dir, f.moonDirWorld.xyz) > 0.999)) {
		float3 st = float3(dot(L.celestial[0].xyz, dir), dot(L.celestial[1].xyz, dir), dot(L.celestial[2].xyz, dir));
		float clear = (1.0 - rain) * above;
		float airmass = 1.0 / max(dir.y, 0.05);
		float ext = exp(-0.12 * min(airmass, 12.0));
		float3 behind = float3(0.0);
		if (night > 0.0 && clear > 0.0) {
			float sigma = clamp(0.7 * pixelAngle / (1.5707963 / 420.0), 0.25, 0.8);
			float3 stars = shadeStars(st, airmass, f.time.y, sigma);
			float3 mw = shadeMilkyWay(st, milkyWay) * L.night.y;
			behind = (stars * 0.9 + mw) * (night * clear * ext);
		}
		float cover = 0.0;
		float3 moonLight = float3(0.0);
		float3 md = f.moonDirWorld.xyz;
		float rm = L.sunGeom.z;
		float cm = dot(dir, md);
		if (cm > cos(rm * 1.2)) {
			float3 up = normalize(L.celestial[2].xyz - md * dot(L.celestial[2].xyz, md));
			float3 right = cross(up, md);
			float2 q = float2(dot(dir, right), dot(dir, up)) / sin(rm);
			if (dot(q, q) < 1.0) {
				float3 moon = shadeMoonDisc(q, md, up, L.night.w) * float3(0.92, 0.96, 1.05);
				float moonUp = smoothstep(-0.02, 0.02, md.y);
				cover = moonUp * (1.0 - smoothstep(0.9, 1.0, length(q)));
				moonLight = moon * 0.9 * (1.0 - 0.85 * rain);
			}
		}
		sky += behind * (1.0 - cover) + moonLight * cover;
	}
	return sky;
}

#endif
