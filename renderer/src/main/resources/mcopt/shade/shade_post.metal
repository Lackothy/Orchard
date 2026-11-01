// Copyright 2026 mcopt-shade contributors
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
// License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
// law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
// OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
// limitations under the License.
//
// M2 post passes (compiled once by mcshade.m after shade_look + shade_common + shade_m2; driven by
// mcopt.metal.shade.ShadePost). All full-screen work is fragment passes into compressible render targets (apple-gpu §0.2).
//   shade_fs_vs              full-screen triangle (uv = (pixel + 0.5) / size, GL row order: no flip)
//   shade_bloom_down0        HDR scene -> bloom level 0 (half size): 13-tap downsample with a Karis average (no fireflies)
//   shade_bloom_down         level i -> i + 1: 13-tap downsample (Jimenez 2014, "Next generation post processing in CoD:AW")
//   shade_bloom_up           U_i = w_i L_i + tent(U_i+1): the octave spectrum accumulated from the smallest level up
//   shade_final              bloom (energy-conserving lerp) + fog glow, Purkinje, vignette, vibrance, Shade Filmic, dither -> RGBA8 sRGB
//   shade_bridge_encode      HDR -> RGBA8 display values (what a mod drawing display-referred colours, e.g. Distant Horizons, sees)
//   shade_bridge_decode      RGBA8 -> HDR where that mod changed a pixel (programmable blending reads the HDR value)
//   shade_histogram          compute: log-luminance histogram of bloom level 2 into ShadeGpu
//   shade_exposure           compute (1 thread): metered exposure, bounded around the reference, adapted -> ShadeGpu.exposure
//   shade_milkyway_bake      the Milky Way texture (once)
// The bloom composite follows the idea of SE Natural Bloom & Dirty Lens (c) 2019 sonicether, MIT License: a lerp toward
// the blurred image instead of an additive threshold, with octave weights i^s and a fog-weighted extra; the code is ours.

struct ShadeFsOut { float4 position [[position]]; float2 uv; };

vertex ShadeFsOut shade_fs_vs(uint vid [[vertex_id]]) {
	ShadeFsOut o;
	float2 p = float2(vid == 1 ? 3.0 : -1.0, vid == 2 ? 3.0 : -1.0);
	o.position = float4(p, 0.0, 1.0);
	o.uv = float2(p.x * 0.5 + 0.5, 0.5 - p.y * 0.5);
	return o;
}

constexpr sampler shadeLinearClamp(filter::linear, address::clamp_to_edge);

static inline half3 shadeDown13(texture2d<half> src, float2 uv, float2 texel, bool karis) {
	half3 a = src.sample(shadeLinearClamp, uv + texel * float2(-2, -2)).rgb;
	half3 b = src.sample(shadeLinearClamp, uv + texel * float2(0, -2)).rgb;
	half3 c = src.sample(shadeLinearClamp, uv + texel * float2(2, -2)).rgb;
	half3 d = src.sample(shadeLinearClamp, uv + texel * float2(-2, 0)).rgb;
	half3 e = src.sample(shadeLinearClamp, uv).rgb;
	half3 f = src.sample(shadeLinearClamp, uv + texel * float2(2, 0)).rgb;
	half3 g = src.sample(shadeLinearClamp, uv + texel * float2(-2, 2)).rgb;
	half3 h = src.sample(shadeLinearClamp, uv + texel * float2(0, 2)).rgb;
	half3 i = src.sample(shadeLinearClamp, uv + texel * float2(2, 2)).rgb;
	half3 j = src.sample(shadeLinearClamp, uv + texel * float2(-1, -1)).rgb;
	half3 k = src.sample(shadeLinearClamp, uv + texel * float2(1, -1)).rgb;
	half3 l = src.sample(shadeLinearClamp, uv + texel * float2(-1, 1)).rgb;
	half3 m = src.sample(shadeLinearClamp, uv + texel * float2(1, 1)).rgb;
	half3 g0 = (j + k + l + m) * 0.25h, g1 = (a + b + d + e) * 0.25h, g2 = (b + c + e + f) * 0.25h, g3 = (d + e + g + h) * 0.25h, g4 = (e + f + h + i) * 0.25h;
	if (!karis) return g0 * 0.5h + (g1 + g2 + g3 + g4) * 0.125h;
	half w0 = 1.0h / (1.0h + shadeLumaH(g0)), w1 = 1.0h / (1.0h + shadeLumaH(g1)), w2 = 1.0h / (1.0h + shadeLumaH(g2)),
	     w3 = 1.0h / (1.0h + shadeLumaH(g3)), w4 = 1.0h / (1.0h + shadeLumaH(g4));
	w0 *= 0.5h; w1 *= 0.125h; w2 *= 0.125h; w3 *= 0.125h; w4 *= 0.125h;
	return (g0 * w0 + g1 * w1 + g2 * w2 + g3 * w3 + g4 * w4) / (w0 + w1 + w2 + w3 + w4);
}

fragment half4 shade_bloom_down0(ShadeFsOut in [[stage_in]], texture2d<half> src [[texture(0)]], constant float4 &p [[buffer(0)]],
                                  device ShadeGpu &gw [[buffer(18)]]) {
	float2 texel = 1.0 / float2(src.get_width(), src.get_height());
	float2 uv = in.uv * p.xy, lo = texel, hi = p.xy - texel;
	half3 a = min(src.sample(shadeLinearClamp, clamp(uv + texel * float2(-1, -1), lo, hi)).rgb, half3(30000.0h));
	half3 b = min(src.sample(shadeLinearClamp, clamp(uv + texel * float2(1, -1), lo, hi)).rgb, half3(30000.0h));
	half3 c = min(src.sample(shadeLinearClamp, clamp(uv + texel * float2(-1, 1), lo, hi)).rgb, half3(30000.0h));
	half3 d = min(src.sample(shadeLinearClamp, clamp(uv + texel * float2(1, 1), lo, hi)).rgb, half3(30000.0h));
	half wa = 1.0h / (1.0h + shadeLumaH(a)), wb = 1.0h / (1.0h + shadeLumaH(b)), wc = 1.0h / (1.0h + shadeLumaH(c)), wd = 1.0h / (1.0h + shadeLumaH(d));
	if (p.z > 0.0) {
		float m = float(max(max(shadeLumaH(a), shadeLumaH(b)), max(shadeLumaH(c), shadeLumaH(d))));
		if (simd_any(m > p.z) && simd_is_first()
		    && atomic_load_explicit((device atomic_uint *) &gw.xdrHot, memory_order_relaxed) == 0u)
			atomic_fetch_or_explicit((device atomic_uint *) &gw.xdrHot, 1u, memory_order_relaxed);
	}
	return half4((a * wa + b * wb + c * wc + d * wd) / (wa + wb + wc + wd), 1.0h);
}

fragment half4 shade_bloom_down(ShadeFsOut in [[stage_in]], texture2d<half> src [[texture(0)]]) {
	float2 texel = 1.0 / float2(src.get_width(), src.get_height());
	return half4(shadeDown13(src, in.uv, texel, false), 1.0h);
}

struct ShadeBloomUp { float4 w; };

fragment half4 shade_bloom_up(ShadeFsOut in [[stage_in]], texture2d<half> level [[texture(0)]], texture2d<half> coarser [[texture(1)]],
                              constant ShadeBloomUp &p [[buffer(0)]]) {
	half3 l = level.sample(shadeLinearClamp, in.uv).rgb;
	float2 t = 1.0 / float2(coarser.get_width(), coarser.get_height());
	half3 u = coarser.sample(shadeLinearClamp, in.uv).rgb * 0.25h;
	u += coarser.sample(shadeLinearClamp, in.uv + float2(t.x, 0)).rgb * 0.125h + coarser.sample(shadeLinearClamp, in.uv - float2(t.x, 0)).rgb * 0.125h
	    + coarser.sample(shadeLinearClamp, in.uv + float2(0, t.y)).rgb * 0.125h + coarser.sample(shadeLinearClamp, in.uv - float2(0, t.y)).rgb * 0.125h;
	u += coarser.sample(shadeLinearClamp, in.uv + t).rgb * 0.0625h + coarser.sample(shadeLinearClamp, in.uv - t).rgb * 0.0625h
	    + coarser.sample(shadeLinearClamp, in.uv + float2(t.x, -t.y)).rgb * 0.0625h + coarser.sample(shadeLinearClamp, in.uv + float2(-t.x, t.y)).rgb * 0.0625h;
	return half4(l * half(p.w.x) + u * half(p.w.y), 1.0h);
}

struct ShadeFinalParams { float4 p; float4 q; float4 v; float4 r; float4 x; float4 a; };

template <bool LEAN = false>
static inline half3 shadeSharpen(texture2d<half> img, uint2 px, half3 m, half amount) {
	half3 n, s, e, w;
	if (LEAN) {
		constexpr sampler pxs(coord::pixel, filter::nearest, address::clamp_to_edge);
		float2 cc = float2(px) + 0.5;
		n = img.sample(pxs, cc, int2(0, 1)).rgb;
		s = img.sample(pxs, cc, int2(0, -1)).rgb;
		e = img.sample(pxs, cc, int2(1, 0)).rgb;
		w = img.sample(pxs, cc, int2(-1, 0)).rgb;
	} else {
		int2 c = int2(px), last = int2(img.get_width(), img.get_height()) - 1;
		n = img.read(uint2(min(c + int2(0, 1), last))).rgb;
		s = img.read(uint2(max(c - int2(0, 1), int2(0)))).rgb;
		e = img.read(uint2(min(c + int2(1, 0), last))).rgb;
		w = img.read(uint2(max(c - int2(1, 0), int2(0)))).rgb;
	}
	half3 lo = min(m, min(min(n, s), min(e, w)));
	half3 hi = max(m, max(max(n, s), max(e, w)));
	half range = dot(hi - lo, half3(0.2126h, 0.7152h, 0.0722h)) / max(dot(hi, half3(0.2126h, 0.7152h, 0.0722h)), 0.02h);
	half k = amount * 2.0h * (1.0h - smoothstep(0.15h, 0.9h, range));
	return clamp(m + (m - (n + s + e + w) * 0.25h) * k, lo, hi);
}

static inline half3 shadeVlScaled(half3 rgb, half rr, bool airNeg) {
	return airNeg ? max(rgb, 0.0h) * saturate(rr) + min(rgb, 0.0h) * min(rr, 16.0h) : rgb * saturate(rr);
}
static inline half4 shadeVlUpsample(texture2d<half> vlT, texture2d<half> vlD, float2 uv, float dist, float maxD, float2 uvScale,
                                    float mode = 0.0, float tolScale = 1.0, bool airNeg = false) {
	float2 size = float2(vlT.get_width(), vlT.get_height()) * uvScale;
	uv = min(uv * uvScale, uvScale - 0.5 / float2(vlT.get_width(), vlT.get_height()));
	float2 lp = uv / uvScale * size - 0.5;
	float2 fl = floor(lp);
	half2 fr = half2(lp - fl);
	int2 base = int2(fl), hi = int2(size) - 1;
	half invTol = half(1.0 / ((0.04 * dist + 0.15) * max(tolScale, 0.05)));
	half hd = half(min(dist, 3e4));
	half4 dd = vlD.gather(shadeLinearClamp, uv, int2(0), component::w);
	if (mode > 1.5) {
		half4 s = vlT.sample(shadeLinearClamp, uv);
		if (mode < 2.5) return s;
		half4 bw4 = half4((1.0h - fr.x) * fr.y, fr.x * fr.y, fr.x * (1.0h - fr.y), (1.0h - fr.x) * (1.0h - fr.y));
		half mD = half(maxD), avgD = dot(bw4, min(dd, mD));
		return half4(s.rgb * clamp((min(hd, mD) + 0.5h) / (avgD + 0.5h), 0.0h, 4.0h), s.a);
	}
	if (all(abs(dd - hd) * invTol < 0.5h)) return vlT.sample(shadeLinearClamp, uv);
	half4 sum = 0.0h, bestC = half4(0.0h, 0.0h, 0.0h, 1.0h);
	half wsum = 0.0h, best = 6e4h, bestD = 1.0h;
	for (int j = 0; j <= 1; j++) {
		for (int i = 0; i <= 1; i++) {
			uint2 q = uint2(clamp(base + int2(i, j), int2(0), hi));
			half4 s = vlT.read(q);
			half sd = j == 0 ? (i == 0 ? dd.w : dd.z) : (i == 0 ? dd.x : dd.y);
			half bw = (i == 0 ? 1.0h - fr.x : fr.x) * (j == 0 ? 1.0h - fr.y : fr.y) + 1e-3h;
			half diff = abs(sd - hd);
			half x = diff * invTol;
			half w = bw / (1.0h + x * x * (1.0h + x));
			sum += s * w;
			wsum += w;
			if (diff < best) {
				best = diff;
				bestC = s;
				bestD = sd;
			}
		}
	}
	if (wsum > 0.02h) return sum / wsum;
	half mD = half(maxD);
	if (mode > 0.5) {
		half4 acc = 0.0h;
		half bsum = 0.0h;
		for (int j = 0; j <= 1; j++) {
			for (int i = 0; i <= 1; i++) {
				half4 s = vlT.read(uint2(clamp(base + int2(i, j), int2(0), hi)));
				half sd = j == 0 ? (i == 0 ? dd.w : dd.z) : (i == 0 ? dd.x : dd.y);
				half bw = (i == 0 ? 1.0h - fr.x : fr.x) * (j == 0 ? 1.0h - fr.y : fr.y) + 1e-3h;
				acc += half4(shadeVlScaled(s.rgb, (min(hd, mD) + 0.5h) / (min(sd, mD) + 0.5h), airNeg), s.a) * bw;
				bsum += bw;
			}
		}
		return acc / bsum;
	}
	return half4(shadeVlScaled(bestC.rgb, (min(hd, mD) + 0.5h) / (min(bestD, mD) + 0.5h), airNeg), bestC.a);
}

static inline float3 shadeWorldFromDepth(float2 uv, float d, constant ShadeFrame &f) {
	float2 ndc = uv * 2.0 - 1.0;
	float4 w = f.invViewProj * float4(ndc, d, 1.0);
	return w.xyz / w.w;
}

template <bool DIMS, bool LEAN = false>
static float3 shadeFinalScene(ShadeFsOut in, texture2d<half> scene, texture2d<half> bloom, depth2d<float> depth, texture2d<half> bloom0,
                              texture2d<half> vlT, texture2d<half> vlD, constant ShadeFinalParams &P, constant ShadeFrame &f,
                              constant ShadeLook &L, constant ShadeGpu &g, constant ShadeResources &sr, thread float &lum,
                              texture2d<half> skyLut, sampler lutS) {
	uint2 px = uint2(in.position.xy);
	float ex = max(g.exposure.x, 1e-6);
	float2 dsize = P.r.x > 0.0 ? P.r.xy : float2(depth.get_width(), depth.get_height());
	int rw = int(P.r.w);
	float2 uv = (rw & 2) != 0 ? clamp(in.uv + 0.5 * f.jitter.xy, 0.0, 1.0) : in.uv;
#ifdef SHADE_HAZE_EDGE
	float2 tp = clamp(uv * dsize - 0.5, float2(0.0), dsize - 1.001);
	uint2 t0 = uint2(tp);
	float2 fw = tp - float2(t0);
	constexpr sampler gatherClamp(coord::normalized, address::clamp_to_edge, filter::nearest);
	float4 dg = depth.gather(gatherClamp, (float2(t0) + 1.0) / float2(depth.get_width(), depth.get_height()));
	float4 dq = float4(dg.w, dg.z, dg.x, dg.y);
	float d = fw.y >= 0.5 ? (fw.x >= 0.5 ? dq.w : dq.z) : (fw.x >= 0.5 ? dq.y : dq.x);
#else
	uint2 dpx = min(uint2(uv * dsize), uint2(dsize) - 1u);
	float d = depth.read(dpx);
#endif
	bool dims = DIMS && (L.tier.y & SHADE_FEATURE_DIMS) != 0 && L.dimTint.x > 0.0 && f.flags.x != SHADE_DIM_OVERWORLD;
	uint2 sp = px;
	if (dims && (L.tier.y & SHADE_FEATURE_SHIMMER) != 0 && shadeDimKind(L) == 1 && d > 0.0) {
		float dist = length(shadeWorldFromDepth(in.uv, d, f));
		sp = uint2(clamp(int2(px) + int2(round(shadeShimmer(in.uv, dist, f, sr.milkyWay))), int2(0), int2(scene.get_width(), scene.get_height()) - 1));
	}
	half3 c0;
	if (LEAN) {
		constexpr sampler pxc(coord::pixel, filter::nearest, address::clamp_to_edge);
		c0 = scene.sample(pxc, float2(sp) + 0.5).rgb;
	} else {
		c0 = scene.read(sp).rgb;
	}
	if (P.r.z > 0.0) c0 = shadeSharpen<LEAN>(scene, sp, c0, half(P.r.z));
	float3 c = float3(c0);
	float3 T = float3(1.0);
	bool hazeOn = (L.tier.y & SHADE_FEATURE_HAZE) != 0 && f.flags.x == SHADE_DIM_OVERWORLD && f.fogColor.a < 0.5;
	float hazeNear = shadeAirSmooth(L) ? 0.0 : 4.0;
	float3 wp = d > 0.0 ? shadeWorldFromDepth(uv, d, f) : float3(0.0);
	bool hazeEdge = false;
	float4 dkq = float4(1e4), wqq = float4(0.0);
	float3 sunGlow = float3(0.0);
#ifdef SHADE_HAZE_EDGE
	if (hazeOn) {
		uint2 t1 = t0 + 1u;
		float dlo = min(min(dq.x, dq.y), min(dq.z, dq.w)), dhi = max(max(dq.x, dq.y), max(dq.z, dq.w));
		if (dhi > 0.0 && (dlo <= 0.0 || dhi > dlo * 1.25)) {
			float4 wq = float4((1.0 - fw.x) * (1.0 - fw.y), fw.x * (1.0 - fw.y), (1.0 - fw.x) * fw.y, fw.x * fw.y);
			float3 Ts = 0.0, Ss = 0.0, Us = 0.0;
			for (int k = 0; k < 4; k++) {
				float3 Tk = float3(1.0), Sk = float3(0.0), Uk = float3(0.0);
				if (dq[k] > 0.0) {
					uint2 tk = uint2(k & 1 ? t1.x : t0.x, k & 2 ? t1.y : t0.y);
					float3 pk = shadeWorldFromDepth((float2(tk) + 0.5) / dsize, dq[k], f);
					if (dot(pk, pk) > hazeNear) Sk = shadeHazeInscatter(pk, f, L, 1.0, Tk, Uk);
					dkq[k] = length(pk);
				}
				Ts += Tk * wq[k];
				Ss += Sk * wq[k];
				Us += Uk * wq[k];
			}
			T = Ts;
			c = c * Ts + Ss * ex;
			sunGlow = Us * ex;
			hazeEdge = true;
			wqq = wq;
		} else if (d > 0.0 && dot(wp, wp) > hazeNear) {
			float3 U;
			float3 S = shadeHazeInscatter(wp, f, L, 1.0, T, U);
			c = c * T + S * ex;
			sunGlow = U * ex;
		}
	}
#else
	if (d > 0.0 && hazeOn) {
		if (dot(wp, wp) > hazeNear) {
			float3 U;
			float3 S = shadeHazeInscatter(wp, f, L, 1.0, T, U);
			c = c * T + S * ex;
			sunGlow = U * ex;
		}
	}
#endif
	bool dhDrew = (int(P.r.w) & 1) != 0;
	bool endSkyHere = !dhDrew || (shadeDimsBit(L, SHADE_DIMS_DH_SKY) && shadeLuma(c) <= shadeLuma(shadeLdrToHdr(f.fogColor.rgb, L)) * 1.5 + 1e-4);
	if (dims && d <= 0.0 && shadeDimKind(L) == 3 && f.flags.y == 0 && endSkyHere) {
		float pixelAngle = 2.0 * atan(1.0 / max(abs(f.proj[1][1]), 1e-3)) / float(scene.get_height());
		c = shadeEndSky(normalize(shadeViewRay(uv * 2.0 - 1.0, f.invViewProj)), f, L, sr.milkyWay, pixelAngle) * ex;
	}
	if (dims && f.flags.y == 0) {
		c = shadeDimsFog(c, wp, d > 0.0, f, L, ex, dhDrew, sr.milkyWay);
		T = float3(d > 0.0 ? exp(-L.dimTint.x * length(wp)) : 0.0);
	}
	if (DIMS && (L.tier.y & SHADE_FEATURE_MEDIA) != 0 && (f.flags.y == 2 || f.flags.y == 3)) c = shadeMedia(c, in.uv, d > 0.0 ? length(wp) : 1e4, f, sr.milkyWay);
	float tv = 1.0;
	if (P.v.x > 0.5) {
		float2 vlScale = P.v.z > 0.0 ? P.v.zw : float2(1.0);
		const int edgeFix = LEAN ? 0 : (P.a.x > 0.0 ? int(P.a.y) : 0);
		const float upMode = LEAN ? 1.0 : P.q.y;
		half4 v;
		if (hazeEdge && (edgeFix & 1) != 0) {
			v = 0.0h;
			for (int k = 0; k < 4; k++) {
				if (wqq[k] > 0.0) v += shadeVlUpsample(vlT, vlD, uv, dkq[k], P.v.y, vlScale, upMode, P.q.z, (edgeFix & 2) != 0) * half(wqq[k]);
			}
		} else {
			v = shadeVlUpsample(vlT, vlD, uv, d > 0.0 ? length(wp) : 1e4, P.v.y, vlScale, upMode, P.q.z, (edgeFix & 2) != 0);
		}
		tv = P.v.x > 1.5 ? 1.0 : float(v.a);
		float3 lit = c * tv;
		float3 add = clamp(float3(v.rgb) * ex, -30000.0, 30000.0);
		if (P.q.w > 0.0) {
			float la = shadeLuma(max(add, 0.0)) / P.q.w;
			add *= rsqrt(1.0 + la * la);
		}
		float3 lo = lit * (P.a.x > 0.0 && d > 0.0 ? P.a.x : 0.55);
		if (P.a.z > 0.0 && P.a.x > 0.0 && d > 0.0) {
			lo = max(lit - sunGlow * tv, 0.0);
			if (any(add < -sunGlow * tv)) lo = max(lo - shadeAerialSunGlow(wp, skyLut, lutS, f, L) * (ex * tv), 0.0);
		}
		c = max(lit + add, lo);
	}
	if (P.p.y > 0.5) {
		float3 b = float3(bloom.sample(shadeLinearClamp, in.uv).rgb + bloom0.sample(shadeLinearClamp, in.uv).rgb * half(P.q.x)) * P.p.x;
		float m = L.bloom.x + (d > 0.0 ? L.bloom.z * (1.0 - shadeLuma(T) * tv) : L.bloom.z * (0.25 + 0.75 * (1.0 - tv)));
		c = mix(c, b, saturate(m));
	}
	lum = shadeLuma(c) / ex;
	if (lum < 0.04 && L.grade1.w > 0.0) {
		float rods = (1.0 - smoothstep(0.004, 0.04, lum)) * L.grade1.w;
		c = mix(c, float3(shadeLuma(c)) * float3(0.72, 0.88, 1.3), rods);
	}
	float2 r = (in.uv - 0.5) * float2(float(scene.get_width()) / float(scene.get_height()), 1.0);
	float vr = pow(saturate(length(r) * 1.25), 2.4);
	c *= 1.0 - L.user0.y * vr * (1.0 - 0.5 * saturate(shadeLuma(c) - 1.0));
	float Y = shadeLuma(c);
	float mx = max(max(c.r, c.g), c.b), mn = min(min(c.r, c.g), c.b);
	float chroma = (mx - mn) / max(mx, 1e-4);
	c = max(Y + (c - Y) * (1.0 + L.grade0.z * (1.0 - saturate(chroma / 0.6))), 0.0);
	return c;
}

template <bool LEAN = false>
static inline float3 shadeFinalEncode(float3 y, float4 position, constant ShadeFinalParams &P, constant ShadeLook &L, float lum) {
	float phi = L.grade2.w;
	if (any(y < 8.0 * phi)) y += phi * exp(-3.0 * y / max(phi, 1e-6));
	float3 srgb = shadeLinearToSrgbExact(saturate(y));
	float n0 = shadeNoise(position.xy, int(P.p.z)), n1 = shadeNoise(position.xy + 17.0, int(P.p.z) + 7);
	srgb += (n0 + n1 - 1.0) * (L.output.z / 255.0);
	if (!LEAN && P.p.w == 4.0) srgb = float3(saturate(log2(max(lum, 1e-6)) / 16.0 + 0.6));
	return saturate(srgb);
}

#define SHADE_FINAL_ARGS ShadeFsOut in [[stage_in]], texture2d<half> scene [[texture(0)]], texture2d<half> bloom [[texture(1)]], \
	depth2d<float> depth [[texture(2)]], texture2d<half> bloom0 [[texture(3)]], texture2d<half> vlT [[texture(4)]], \
	texture2d<half> vlD [[texture(5)]], constant ShadeFinalParams &P [[buffer(0)]], constant ShadeFrame &f [[buffer(16)]], \
	constant ShadeLook &L [[buffer(17)]], constant ShadeGpu &g [[buffer(18)]], constant ShadeResources &sr [[buffer(19)]], \
	texture2d<half> skyLut [[texture(17)]], sampler lutS [[sampler(13)]]

template <bool DIMS, bool LEAN = false>
static inline float4 shadeFinalSdr(ShadeFsOut in, texture2d<half> scene, texture2d<half> bloom, depth2d<float> depth, texture2d<half> bloom0,
                                   texture2d<half> vlT, texture2d<half> vlD, constant ShadeFinalParams &P, constant ShadeFrame &f,
                                   constant ShadeLook &L, constant ShadeGpu &g, constant ShadeResources &sr, texture2d<half> skyLut, sampler lutS) {
	float lum;
	float3 c = shadeFinalScene<DIMS, LEAN>(in, scene, bloom, depth, bloom0, vlT, vlD, P, f, L, g, sr, lum, skyLut, lutS);
	return float4(shadeFinalEncode<LEAN>(shadeDisplay(c, L), in.position, P, L, lum), 1.0);
}

fragment float4 shade_final(SHADE_FINAL_ARGS) { return shadeFinalSdr<false>(in, scene, bloom, depth, bloom0, vlT, vlD, P, f, L, g, sr, skyLut, lutS); }
fragment float4 shade_final_dims(SHADE_FINAL_ARGS) { return shadeFinalSdr<true>(in, scene, bloom, depth, bloom0, vlT, vlD, P, f, L, g, sr, skyLut, lutS); }
fragment float4 shade_final_lean(SHADE_FINAL_ARGS) { return shadeFinalSdr<false, true>(in, scene, bloom, depth, bloom0, vlT, vlD, P, f, L, g, sr, skyLut, lutS); }

struct ShadeFinalXdrOut { float4 sdr [[color(0)]]; half4 excess [[color(1)]]; };

template <bool DIMS, bool LEAN = false>
static inline ShadeFinalXdrOut shadeFinalXdr(ShadeFsOut in, texture2d<half> scene, texture2d<half> bloom, depth2d<float> depth,
                                             texture2d<half> bloom0, texture2d<half> vlT, texture2d<half> vlD, constant ShadeFinalParams &P,
                                             constant ShadeFrame &f, constant ShadeLook &L, constant ShadeGpu &g, constant ShadeResources &sr,
                                             texture2d<half> skyLut, sampler lutS) {
	float lum;
	float3 c = shadeFinalScene<DIMS, LEAN>(in, scene, bloom, depth, bloom0, vlT, vlD, P, f, L, g, sr, lum, skyLut, lutS);
	float3 y = shadeDisplay(c, L);
	float lc = shadeLuma(c), h = 0.0, boost = 0.0;
	float3 excess = float3(0.0);
	if (lc > P.x.z && P.x.y > 1.0) {
		h = saturate(log2(max(lc, 1e-6) / P.x.z) / P.x.w);
		boost = (P.x.y - 1.0) * h * h * (3.0 - 2.0 * h);
		excess = y / max(shadeLuma(y), 0.05) * boost;
	}
	ShadeFinalXdrOut o;
	o.sdr = float4(shadeFinalEncode<LEAN>(y, in.position, P, L, lum), 0.0);
	if (!LEAN && P.p.w == 8.0) o.sdr = float4(saturate(float3(boost / max(P.x.y - 1.0, 1e-3), h, 0.0)), 0.0);
	o.excess = half4(half3(min(max(excess, 0.0), 60000.0)), 1.0h);
	return o;
}

static inline float3 shadeFullHdr(float3 scene, constant ShadeLook &L, constant ShadeFinalParams &P) {
	float3 c = max(shadeRows(L.gradeIn, max(scene * P.a.w, 0.0)), 0.0);
	c = shadeLottes(c, float4(L.tonemap.xy, P.x.zw));
	float3 y = max(shadeRows(L.gradeOut, c), 0.0);
	y = max(y * L.grade3.rgb + L.grade2.rgb, 0.0);
	float phi = L.grade2.w;
	if (any(y < 8.0 * phi)) y += phi * exp(-3.0 * y / max(phi, 1e-6));
	return y / max(1.0, max(max(y.r, y.g), y.b) / P.x.y);
}

template <bool DIMS, bool LEAN = false>
static inline ShadeFinalXdrOut shadeFinalHdr(ShadeFsOut in, texture2d<half> scene, texture2d<half> bloom, depth2d<float> depth,
                                             texture2d<half> bloom0, texture2d<half> vlT, texture2d<half> vlD,
                                             constant ShadeFinalParams &P, constant ShadeFrame &f, constant ShadeLook &L,
                                             constant ShadeGpu &g, constant ShadeResources &sr, texture2d<half> skyLut, sampler lutS) {
	float lum;
	float3 c = shadeFinalScene<DIMS, LEAN>(in, scene, bloom, depth, bloom0, vlT, vlD, P, f, L, g, sr, lum, skyLut, lutS);
	ShadeFinalXdrOut o;
	o.sdr = float4(shadeFinalEncode<LEAN>(shadeDisplay(c, L), in.position, P, L, lum), 0.0);
	o.excess = half4(half3(shadeFullHdr(c, L, P)), 1.0h);
	return o;
}

fragment ShadeFinalXdrOut shade_final_hdr(SHADE_FINAL_ARGS) { return shadeFinalHdr<false>(in, scene, bloom, depth, bloom0, vlT, vlD, P, f, L, g, sr, skyLut, lutS); }
fragment ShadeFinalXdrOut shade_final_hdr_dims(SHADE_FINAL_ARGS) { return shadeFinalHdr<true>(in, scene, bloom, depth, bloom0, vlT, vlD, P, f, L, g, sr, skyLut, lutS); }
fragment ShadeFinalXdrOut shade_final_hdr_lean(SHADE_FINAL_ARGS) { return shadeFinalHdr<false, true>(in, scene, bloom, depth, bloom0, vlT, vlD, P, f, L, g, sr, skyLut, lutS); }

fragment ShadeFinalXdrOut shade_final_xdr(SHADE_FINAL_ARGS) { return shadeFinalXdr<false>(in, scene, bloom, depth, bloom0, vlT, vlD, P, f, L, g, sr, skyLut, lutS); }
fragment ShadeFinalXdrOut shade_final_xdr_dims(SHADE_FINAL_ARGS) { return shadeFinalXdr<true>(in, scene, bloom, depth, bloom0, vlT, vlD, P, f, L, g, sr, skyLut, lutS); }
fragment ShadeFinalXdrOut shade_final_xdr_lean(SHADE_FINAL_ARGS) { return shadeFinalXdr<false, true>(in, scene, bloom, depth, bloom0, vlT, vlD, P, f, L, g, sr, skyLut, lutS); }

fragment float4 shade_bridge_encode(ShadeFsOut in [[stage_in]], texture2d<half> scene [[texture(0)]], constant ShadeLook &L [[buffer(17)]]) {
	float3 c = float3(scene.read(uint2(in.position.xy)).rgb);
	return float4(shadeLinearToSrgbExact(saturate(shadeDisplay(c, L))), 1.0);
}

fragment float4 shade_bridge_decode(ShadeFsOut in [[stage_in]], float4 hdr [[color(0)]], texture2d<half> ldr [[texture(0)]],
                                    constant ShadeLook &L [[buffer(17)]]) {
	float3 now = float3(ldr.read(uint2(in.position.xy)).rgb);
	float3 was = round(shadeLinearToSrgbExact(saturate(shadeDisplay(hdr.rgb, L))) * 255.0) / 255.0;
	if (all(abs(now - was) < 1.5 / 255.0)) return hdr;
	return float4(shadeLdrToHdr(now, L), hdr.a);
}

#define SHADE_HIST_MIN -14.0
#define SHADE_HIST_MAX 8.0

kernel void shade_histogram(texture2d<half> src [[texture(0)]], device ShadeGpu &g [[buffer(18)]], uint2 id [[thread_position_in_grid]],
                            uint lid [[thread_index_in_threadgroup]]) {
	threadgroup atomic_uint bins[64];
	if (lid < 64) atomic_store_explicit(&bins[lid], 0u, memory_order_relaxed);
	threadgroup_barrier(mem_flags::mem_threadgroup);
	uint w = src.get_width(), h = src.get_height();
	if (id.x < w && id.y < h) {
		float ex = max(g.exposure.x, 1e-6);
		float lum = float(shadeLumaH(src.read(id).rgb)) / ex;
		float t = (log2(max(lum, 1e-7)) - SHADE_HIST_MIN) / (SHADE_HIST_MAX - SHADE_HIST_MIN);
		uint bin = uint(clamp(t * 64.0, 0.0, 63.0));
		float2 r = (float2(id) + 0.5) / float2(w, h) - 0.5;
		float weight = exp(-dot(r, r) / 0.3);
		atomic_fetch_add_explicit(&bins[bin], uint(weight * 16.0 + 0.5), memory_order_relaxed);
	}
	threadgroup_barrier(mem_flags::mem_threadgroup);
	if (lid < 64) {
		uint v = atomic_load_explicit(&bins[lid], memory_order_relaxed);
		if (v != 0) atomic_fetch_add_explicit((device atomic_uint *)&g.histogram[lid], v, memory_order_relaxed);
	}
}

kernel void shade_exposure(device ShadeGpu &g [[buffer(18)]], constant ShadeLook &L [[buffer(17)]], uint id [[thread_position_in_grid]]) {
	if (id != 0) return;
	float total = 0.0;
	for (int i = 0; i < 64; i++) total += float(g.histogram[i]);
	float logRef = log2(max(L.exposureRef.x, 1e-6)) + L.grade0.x;
	float target = logRef;
	float mean = g.scene.x;
	if (total > 0.0) {
		float upper = L.user3.x > 0.0 ? mix(0.92, 0.98, min(L.user3.x, 1.0)) : 0.92;
		float lo = total * 0.40, hi = total * upper, acc = 0.0, sum = 0.0, wsum = 0.0;
		for (int i = 0; i < 64; i++) {
			float n = float(g.histogram[i]);
			float a = max(lo - acc, 0.0), b = min(hi - acc, n);
			float take = max(b - a, 0.0);
			float l = SHADE_HIST_MIN + (float(i) + 0.5) / 64.0 * (SHADE_HIST_MAX - SHADE_HIST_MIN);
			sum += take * l;
			wsum += take;
			acc += n;
		}
		if (wsum > 0.0) mean = sum / wsum;
		float metered = log2(max(L.exposureRef.z, 1e-4)) - mean;
		float lambda = L.exposureRef.y;
		target = lambda * metered + (1.0 - lambda) * logRef;
		target = clamp(target, logRef - L.exposureCfg.x, logRef + L.exposureCfg.y);
	}
	float now = log2(max(g.exposure.x, 1e-6));
	float next;
	if (L.exposureRef.w > 0.5 || !(now > -30.0 && now < 30.0)) {
		next = target;
	} else {
		float tau = target > now ? L.exposureCfg.z : L.exposureCfg.w;
		next = now + (target - now) * (1.0 - exp(-L.dt.x / max(tau, 1e-3)));
	}
	g.exposure = float4(exp2(next), exp2(target), g.exposure.x, next);
	g.scene.x = mean;
	g.scene.w += 1.0;
	for (int i = 0; i < 64; i++) g.histogram[i] = 0u;
}

static float shadeValueNoise(float3 x, uint salt) {
	float3 i = floor(x), fr = x - i;
	float3 u = fr * fr * (3.0 - 2.0 * fr);
	int3 c = int3(i);
	float n000 = shadeHash01(shadeHash3(c, salt)), n100 = shadeHash01(shadeHash3(c + int3(1, 0, 0), salt));
	float n010 = shadeHash01(shadeHash3(c + int3(0, 1, 0), salt)), n110 = shadeHash01(shadeHash3(c + int3(1, 1, 0), salt));
	float n001 = shadeHash01(shadeHash3(c + int3(0, 0, 1), salt)), n101 = shadeHash01(shadeHash3(c + int3(1, 0, 1), salt));
	float n011 = shadeHash01(shadeHash3(c + int3(0, 1, 1), salt)), n111 = shadeHash01(shadeHash3(c + int3(1, 1, 1), salt));
	return mix(mix(mix(n000, n100, u.x), mix(n010, n110, u.x), u.y), mix(mix(n001, n101, u.x), mix(n011, n111, u.x), u.y), u.z);
}
static float shadeFbm(float3 x, int octaves, uint salt) {
	float s = 0.0, a = 0.5, n = 0.0;
	for (int i = 0; i < octaves; i++) {
		s += a * shadeValueNoise(x, salt + uint(i) * 101u);
		n += a;
		x = x * 2.03 + 17.1;
		a *= 0.5;
	}
	return s / n;
}

fragment half4 shade_milkyway_bake(ShadeFsOut in [[stage_in]]) {
	float lon = (in.uv.x - 0.5) * 6.2831853, lat = (in.uv.y - 0.5) * 3.1415927;
	float3 dir = float3(cos(lat) * cos(lon), cos(lat) * sin(lon), sin(lat));
	float lc = abs(lon);
	float b = abs(lat) * 57.29578, l = lc * 57.29578;
	float disc = exp(-b / (3.0 + 3.0 * pow(cos(lon * 0.5), 2.0))) * (0.6 + 0.4 * shadeFbm(dir * 6.0, 4, 11u));
	float bulge = 2.2 * exp(-(l * l / 144.0 + b * b / 36.0));
	float dust = 1.0 - 0.75 * (1.0 - abs(2.0 * shadeFbm(dir * 9.0, 3, 23u) - 1.0)) * exp(-b / 2.0);
	float clouds = 0.3 * pow(shadeFbm(dir * 40.0, 2, 37u), 4.0) * exp(-b / 8.0);
	float v = (disc + bulge) * dust + clouds;
	float3 col = mix(float3(1.0, 0.86, 0.66), float3(0.74, 0.82, 1.0), saturate(l / 70.0));
	return half4(half3(col * v * 0.035), half(shadeDimsBakeNoise(in.uv)));
}

fragment float4 shade_upscale(ShadeFsOut in [[stage_in]], texture2d<float> src [[texture(0)]], constant float4 &P [[buffer(0)]]) {
	float2 size = float2(src.get_width(), src.get_height()), inv = 1.0 / size;
	float2 sp = in.uv * size;
	float2 t1 = floor(sp - 0.5) + 0.5;
	float2 fr = sp - t1;
	float2 w0 = fr * (-0.5 + fr * (1.0 - 0.5 * fr));
	float2 w1 = 1.0 + fr * fr * (-2.5 + 1.5 * fr);
	float2 w2 = fr * (0.5 + fr * (2.0 - 1.5 * fr));
	float2 w3 = fr * fr * (-0.5 + 0.5 * fr);
	float2 w12 = w1 + w2;
	float2 t0 = (t1 - 1.0) * inv, t3 = (t1 + 2.0) * inv, t12 = (t1 + w2 / w12) * inv;
	float4 a = src.sample(shadeLinearClamp, float2(t12.x, t0.y)), b = src.sample(shadeLinearClamp, float2(t0.x, t12.y));
	float4 c = src.sample(shadeLinearClamp, t12), d = src.sample(shadeLinearClamp, float2(t3.x, t12.y));
	float4 e = src.sample(shadeLinearClamp, float2(t12.x, t3.y));
	float wa = w12.x * w0.y, wb = w0.x * w12.y, wc = w12.x * w12.y, wd = w3.x * w12.y, we = w12.x * w3.y;
	float3 cr = (a.rgb * wa + b.rgb * wb + c.rgb * wc + d.rgb * wd + e.rgb * we) / (wa + wb + wc + wd + we);
	float2 base = t1 * inv;
	float3 q0 = src.sample(shadeLinearClamp, base).rgb, q1 = src.sample(shadeLinearClamp, base + float2(inv.x, 0.0)).rgb;
	float3 q2 = src.sample(shadeLinearClamp, base + float2(0.0, inv.y)).rgb, q3 = src.sample(shadeLinearClamp, base + inv).rgb;
	float3 lo = min(min(q0, q1), min(q2, q3)), hi = max(max(q0, q1), max(q2, q3));
	cr = clamp(cr, lo, hi);
	float3 n = src.sample(shadeLinearClamp, in.uv + float2(0.0, inv.y)).rgb, s = src.sample(shadeLinearClamp, in.uv - float2(0.0, inv.y)).rgb;
	float3 ea = src.sample(shadeLinearClamp, in.uv + float2(inv.x, 0.0)).rgb, wv = src.sample(shadeLinearClamp, in.uv - float2(inv.x, 0.0)).rgb;
	float3 nlo = min(lo, min(min(n, s), min(ea, wv))), nhi = max(hi, max(max(n, s), max(ea, wv)));
	float range = dot(nhi - nlo, float3(0.2126, 0.7152, 0.0722)) / max(dot(nhi, float3(0.2126, 0.7152, 0.0722)), 0.02);
	float k = P.x * 2.0 * (1.0 - smoothstep(0.15, 0.9, range));
	float3 o = clamp(cr + (cr - (n + s + ea + wv) * 0.25) * k, nlo, nhi);
	return float4(o, c.a);
}
