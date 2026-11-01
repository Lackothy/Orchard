// Copyright 2026 mcopt-shade contributors
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
// License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
// law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
// OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
// limitations under the License.
//
// mcopt native shading, M3 weather (shader bible E13 + E08's coloured shadows), the receiver side: wet surfaces, puddles
// with reflections, raindrop ripples, snow cover, lightning light, and the tint of light that came through stained glass or
// water (the shadow pass's tint map). Original code; the techniques are public (Schlick 1994, Walter et al. 2007 GGX,
// Lagarde 2011 roughness-aware Fresnel, McGuire & Mara 2014 screen-space marching, value noise).
//
// Assembled by ShadeMsl.terrain after the prelude (shade_look + shade_common + shade_m2) and before water.metal, with the
// SHADE_WEATHER defines (mcopt.metal.shade.weather.ShadeWeather.prelude) in front. Inputs:
//   ShadeLook.wet    x wetness 0..1, y puddles 0..1, z snow cover 0..1, w rain intensity (smoothed rain level, + thunder)
//   ShadeLook.wind   xy wind direction (unit, world xz), z rain slant (horizontal blocks per block of fall), w snowfall at the camera 0..1
//   ShadeLook.flash  xyz unit direction toward the lightning (camera-relative), w its light (radiance on a white surface
//                    facing it, before exposure)
//   ShadeLook.ssr.y  1 when ShadeResources.prevDepth (5) holds the linear view depth of ShadeResources.prevScene (TAA history)
//   ShadeResources   prevScene (1), prevDepth (5, R16F, blocks along last frame's view axis), shadowTint (6, RGBA16F: rgb
//                    the transmittance of the translucent casters, a their reversed-Z light depth, near cascade only)
// UNITS: as shade_common.metal (camera-relative world, blocks; radiance before exposure; reversed-Z).
#ifndef MCOPT_SHADE_WEATHER_SURFACE
#define MCOPT_SHADE_WEATHER_SURFACE

#ifndef SHADE_PUDDLE_STEPS
#define SHADE_PUDDLE_STEPS 10
#endif
#ifndef SHADE_PUDDLE_DIST
#define SHADE_PUDDLE_DIST 64.0
#endif
#ifdef SHADE_PUDDLE_QUAD
#define SHADE_PUDDLE_N (SHADE_PUDDLE_STEPS / 4 > 2 ? SHADE_PUDDLE_STEPS / 4 : 2)
#else
#define SHADE_PUDDLE_N SHADE_PUDDLE_STEPS
#endif

#ifndef SHADE_WX_BUDGET
#ifdef SHADE_WX_LITE
#define SHADE_WX_BUDGET(f, bit) true
#else
#define SHADE_WX_BUDGET(f, bit) shadeDev(f, bit)
#endif
#endif

#if defined(SHADE_WX_B_GATE)
#define SHADE_WX_B_GATE_ON(f) true
#elif defined(SHADE_WX_B_DEV)
#define SHADE_WX_B_GATE_ON(f) shadeDev(f, SHADE_DEV_WX_SSR_GATE3)
#else
#define SHADE_WX_B_GATE_ON(f) false
#endif
#if defined(SHADE_WX_B_RIPPLE)
#define SHADE_WX_B_RIPPLE_ON(f) true
#elif defined(SHADE_WX_B_DEV)
#define SHADE_WX_B_RIPPLE_ON(f) shadeDev(f, SHADE_DEV_WX_RIPPLE_NEAR)
#else
#define SHADE_WX_B_RIPPLE_ON(f) false
#endif
#if defined(SHADE_WX_B_STEPS)
#define SHADE_WX_B_STEPS_ON(f) true
#elif defined(SHADE_WX_B_DEV)
#define SHADE_WX_B_STEPS_ON(f) shadeDev(f, SHADE_DEV_WX_SSR_STEPS8)
#else
#define SHADE_WX_B_STEPS_ON(f) false
#endif

constexpr sampler shadeWxLinear(filter::linear, address::clamp_to_edge, coord::normalized);
constexpr sampler shadeWxNearest(filter::nearest, address::clamp_to_edge, coord::normalized);

static inline float2 shadeWxXZ(float2 rel, constant ShadeFrame &f) {
	return rel + f.cameraFract.xz + fmod(f.cameraBlock.xz, 4096.0);
}

static inline float shadeWxHash(int2 c, uint salt) {
	return shadeHash01(shadeHash(uint(c.x) * 0x8da6b343u ^ shadeHash(uint(c.y) * 0xd8163841u ^ salt)));
}

static inline float shadeWxValue(float2 x, int mask, uint salt) {
	float2 i = floor(x), u = x - i;
	u = u * u * (3.0 - 2.0 * u);
	int2 c = int2(i);
	float a = shadeWxHash(c & mask, salt), b = shadeWxHash((c + int2(1, 0)) & mask, salt);
	float d = shadeWxHash((c + int2(0, 1)) & mask, salt), e = shadeWxHash((c + int2(1, 1)) & mask, salt);
	return mix(mix(a, b, u.x), mix(d, e, u.x), u.y);
}

#define SHADE_WX_NOISE_TILE 256.0
#define SHADE_WX_NOISE_SIZE 1024
static inline float shadeWxPuddleField(float2 xa) {
	float big = shadeWxValue(xa * (1.0 / 32.0), 7, 0xfbb04194u);
	return 0.5 * shadeWxValue(xa * 0.125, 31, 0x51ed270bu) + 0.3 * shadeWxValue(xa * 0.25, 63, 0x2c1b3c6du)
		+ 0.2 * shadeWxValue(xa * 0.5, 127, 0x297a2d39u) + 0.25 * (big - 0.5);
}

static inline float shadeWxSnowDrift(float2 xa) {
	return shadeWxValue(xa * 0.5, 127, 0x1f83d9abu) * 0.6 + shadeWxPuddleField(xa + 37.0) * 0.4;
}

#ifdef SHADE_WX_NOISE_BAKE
fragment half4 shade_wx_noise_fs(float4 pos [[position]]) {
	float2 xa = pos.xy * (SHADE_WX_NOISE_TILE / float(SHADE_WX_NOISE_SIZE));
	return half4(half(shadeWxPuddleField(xa)), half(shadeWxSnowDrift(xa)), 0.0h, 1.0h);
}
#endif

constexpr sampler shadeWxRepeat(filter::linear, address::repeat, coord::normalized);

static inline float2 shadeWxFields(float2 xa, texture2d<half> noise, bool baked, bool puddly, bool snowy) {
	if (baked) return float2(noise.sample(shadeWxRepeat, xa * (1.0 / SHADE_WX_NOISE_TILE), level(0.0)).rg);
#ifndef MCOPT_HDR
	float2 r = float2(0.0);
	if (puddly) r.x = 0.62 * shadeWxValue(xa * 0.125, 31, 0x51ed270bu) + 0.38 * shadeWxValue(xa * 0.25, 63, 0x2c1b3c6du)
		+ 0.25 * (shadeWxValue(xa * (1.0 / 32.0), 7, 0xfbb04194u) - 0.5);
	if (snowy) r.y = shadeWxValue(xa * 0.5, 127, 0x1f83d9abu) * 0.6 + shadeWxValue((xa + 37.0) * 0.125, 31, 0x51ed270bu) * 0.4;
	return r;
#else
	return float2(shadeWxPuddleField(xa), shadeWxSnowDrift(xa));
#endif
}

#define SHADE_RIPPLE_CELL 0.7
#ifndef SHADE_RIPPLE_LAYERS
#define SHADE_RIPPLE_LAYERS 2
#endif
static float2 shadeWxRipples(float2 xa, float t, float rate, float footprint) {
	float2 g = float2(0.0);
	float widen = max(1.0, footprint * (1.0 / 0.011));
	const float C = SHADE_RIPPLE_CELL;
	float lambda = 0.075 * widen, wid = 0.055 * widen, k = 6.2831853 / lambda;
	for (int layer = 0; layer < SHADE_RIPPLE_LAYERS; layer++) {
		float2 q = xa * (1.0 / C) + (layer == 0 ? float2(0.0) : float2(0.5, 0.37));
		float2 ci = floor(q);
		float2 lp = (q - ci) * C;
		uint h = shadeHash3(int3(int2(ci) & 8191, layer), 0x6a09e667u);
#if SHADE_RIPPLE_LAYERS < 2
		float period = 0.33 + 0.28 * shadeHash01(h);
#else
		float period = 0.45 + 0.4 * shadeHash01(h);
#endif
		float ph = t / period + shadeHash01(h ^ 0xbb67ae85u);
		float n = floor(ph);
		float age = (ph - n) * period;
		uint hk = shadeHash(h ^ (uint(int(n)) * 0x9e3779b9u));
		if (shadeHash01(hk) > rate) continue;
		float2 c0 = (0.3 + 0.4 * float2(shadeHash01(hk ^ 0x3c6ef372u), shadeHash01(hk ^ 0xa54ff53au))) * C;
		float2 d = lp - c0;
		float r = length(d);
		float e = r - age * 0.42;
		float env = exp(-age * 5.0) * (1.0 - smoothstep(0.2 * C, 0.29 * C, r)) * exp(-(e * e) / (wid * wid));
		float s;
		float c = sincos(k * e, s);
		float dh = env * (k * c - 2.0 * e / (wid * wid) * s);
		g += dh * d / max(r, 1e-4);
	}
	return g * (0.0055 / widen);
}

#ifdef SHADE_WET_ALL
#define SHADE_WET_ALL_ON(L) ((L).water.x > 0.5)
#else
#define SHADE_WET_ALL_ON(L) false
#endif

struct ShadeWet {
	half omega;
	half puddle;
	half snow;
	float2 slope;
	half film;
	half low;
	half var;
	half dark;
	half tex;
	half grain;
	half vegK;
};

static inline half shadeWetTexHeight(half3 texel, half3 mean, constant ShadeLook &L) {
	return half(clamp((float(shadeLumaH(texel)) / max(float(shadeLumaH(mean)), 0.02) - 1.0) * L.waterAbs.z, -1.0, 1.0));
}

static ShadeWet shadeWetSurface(float3 p, half3 n, half sky, bool thin, bool foliage, float footprint, constant ShadeFrame &f,
                                constant ShadeLook &L, texture2d<half> noise, bool baked, half veg = 0.0h) {
	ShadeWet w;
	w.omega = 0.0h;
	w.puddle = 0.0h;
	w.snow = 0.0h;
	w.slope = float2(0.0);
	w.film = 0.0h;
	w.low = 0.0h;
	w.var = 0.0h;
	w.dark = 0.0h;
	w.tex = 0.0h;
	w.grain = 0.0h;
	w.vegK = shadeWxVegLight(f) ? 1.0h : 0.0h;
	half open = smoothstep(0.90h, 0.995h, sky);
	if (open <= 0.0h || shadeDev(f, SHADE_DEV_WX_DRY)) return w;
	half face = n.y > 0.5h ? 1.0h : n.y < -0.5h ? 0.0h : 0.55h;
	half2 reach = shadeWxReach(p.y + f.cameraFract.y + f.cameraBlock.y, f);
	half rainHere = reach.y;
	w.omega = half(L.wet.x) * open * face * (thin ? 0.45h : foliage ? 0.7h : 1.0h) * rainHere;
	if (SHADE_WET_ALL_ON(L) && !thin && !foliage && (n.y > 0.5h || !shadeWxWalls(f))) {
		w.film = 1.0h;
		w.dark = half(L.waterAbs.x);
	}
	float2 xa = shadeWxXZ(p.xz, f);
	bool snowy = false, puddly = false;
#ifdef SHADE_SNOW_COVER
	snowy = L.wet.z > 0.001 && n.y > 0.5h && reach.x > 0.0h;
#endif
#ifdef SHADE_PUDDLES
	puddly = L.wet.y > 0.001 && n.y > 0.5h && !thin && !foliage && sky > 0.95h && rainHere > 0.0h;
#endif
	float2 fields = snowy || puddly ? shadeWxFields(xa, noise, baked && !shadeDev(f, SHADE_DEV_WX_ANALYTIC), puddly, snowy) : float2(0.0);
#ifdef SHADE_SNOW_COVER
	if (snowy) {
		float drift = fields.y;
		w.snow = half(smoothstep(1.0 - float(L.wet.z) * 1.15, 1.15 - float(L.wet.z) * 1.15, drift + 0.15) * saturate(float(L.wet.z) * 40.0)) * open * reach.x;
		w.omega *= 1.0h - w.snow;
	}
#endif
#ifdef SHADE_PUDDLES
	if (puddly) {
		float field = fields.x;
		float t = mix(0.80, 0.575, float(L.wet.y));
		half vegP = shadeWxVegPuddles(f) ? veg : 0.0h;
		t += 0.12 * float(vegP);
		half P = half(smoothstep(t, t + 0.035, field) * saturate(float(L.wet.y) * 40.0)) * smoothstep(0.95h, 0.995h, sky) * (1.0h - w.snow) * rainHere
			* (1.0h - 0.3h * vegP);
		w.omega = max(w.omega, half(smoothstep(t - 0.07, t, field)) * half(L.wet.y) * open * rainHere);
		w.puddle = P;
		if (SHADE_WET_ALL_ON(L)) {
			w.low = half(smoothstep(t - L.waterAbs.w, t, field)) * half(L.wet.y) * open * (1.0h - w.snow) * rainHere;
			w.var = half(clamp(field - 0.5, -0.3, 0.3));
		}
#ifdef SHADE_RIPPLES
		float2 rippleFade = SHADE_WX_B_RIPPLE_ON(f) ? float2(0.02, 0.045) : float2(0.03, 0.06);
		float fade = 1.0 - smoothstep(rippleFade.x, rippleFade.y, footprint);
		if (SHADE_WX_BUDGET(f, SHADE_DEV_WX_RIPPLE_GATE)) {
			float cv = saturate(-p.y * rsqrt(max(dot(p, p), 1e-6)));
			float m = 1.0 - cv;
			float Ff = 0.02 + 0.95 * m * m * m * m * m;
			fade *= smoothstep(0.02, 0.035, Ff * float(P));
		}
		if (P > 0.0h && L.wet.w > 0.01 && fade > 0.0 && !shadeDev(f, SHADE_DEV_WX_NO_RIPPLES)) w.slope = shadeWxRipples(xa, f.sunPath.w, min(L.wet.w * 1.1, 1.0), footprint) * (fade * 1.6);
#endif
	}
#endif
	return w;
}

static inline half3 shadeWetAlbedo(half3 albedo, ShadeWet w) {
	half3 a = albedo;
	half om = w.omega * (1.0h - 0.67h * w.vegK * shadeWxVegHue(albedo));
	if (om > 0.0h) a = pow(max(a, half3(1e-4h)), half3(1.0h + 0.65h * om)) * (1.0h - 0.08h * om);
	if (w.film > 0.0h) {
		if (w.dark != 0.0h) a = pow(max(a, half3(1e-4h)), half3(1.0h + w.dark * om));
		a *= (1.0h - 0.12h * w.low) * (1.0h - 0.2h * w.puddle);
	} else if (w.puddle > 0.0h) a *= 1.0h - 0.3h * w.puddle;
	if (w.snow > 0.0h) a = mix(a, half3(0.86h, 0.88h, 0.92h) * (1.0h + 0.35h * w.grain), w.snow);
	return a;
}

static inline float shadeWxFresnel(float cosT, float rough) {
	float m = 1.0 - saturate(cosT);
	float m2 = m * m;
	return 0.02 + (max(1.0 - rough, 0.02) - 0.02) * m2 * m2 * m;
}

static inline float shadeWxGgx(float3 n, float3 v, float3 l, float alpha) {
	float3 h = normalize(l - v);
	float nh = saturate(dot(n, h)), nl = saturate(dot(n, l)), nv = max(dot(n, -v), 1e-3);
	float a2 = alpha * alpha;
	float dd = nh * nh * (a2 - 1.0) + 1.0;
	float D = a2 / (3.14159265 * dd * dd);
	float gv = nl * sqrt(nv * nv * (1.0 - a2) + a2), gl = nv * sqrt(nl * nl * (1.0 - a2) + a2);
	return 3.14159265 * D * (0.5 / max(gv + gl, 1e-5)) * nl;
}

template <int N>
static float4 shadeWxHistMarch(float3 p, float3 r, float maxDist, int chunk, int chunks, float jitter, constant ShadeLook &L,
                               texture2d<half> histDepth, thread float3 &hitPoint) {
	float4 c0 = L.prevViewProjNoJitter * float4(p, 1.0);
	float4 dc = L.prevViewProjNoJitter * float4(r, 0.0);
	float len = maxDist;
	if (dc.w < 0.0) len = min(len, (c0.w - 0.2) / -dc.w * 0.9);
	if (len <= 0.2 || c0.w <= 0.1) return float4(0.0, 0.0, 0.0, -1.0);
	float4 c1 = c0 + dc * len;
	float2 s0 = c0.xy / c0.w, s1 = c1.xy / c1.w;
	float iw0 = 1.0 / c0.w, iw1 = 1.0 / c1.w;
	float2 d = s1 - s0;
	float tx = d.x > 1e-6 ? (1.0 - s0.x) / d.x : d.x < -1e-6 ? (-1.0 - s0.x) / d.x : 1e6;
	float ty = d.y > 1e-6 ? (1.0 - s0.y) / d.y : d.y < -1e-6 ? (-1.0 - s0.y) / d.y : 1e6;
	float tEnd = min(1.0, min(tx, ty));
	if (tEnd <= 0.0) return float4(0.0, 0.0, 0.0, -1.0);
	bool screenEnd = tEnd < 1.0;
	const int n = N;
	float inv = 1.0 / float(n * chunks);
	float u0 = max(float(chunk * n) - jitter, 0.0) * inv;
	float tPrev = u0 * (0.3 + 0.7 * u0) * tEnd;
	float4 back = float4(0.0, 0.0, 0.0, -1.0);
	float3 backPoint = float3(0.0);
	for (int i = 1; i <= n; i++) {
		float u = (float(chunk * n + i) - jitter) * inv;
		float t = u * (0.3 + 0.7 * u) * tEnd;
		float2 s = s0 + d * t;
		float wr = 1.0 / mix(iw0, iw1, t);
		float ws = float(histDepth.sample(shadeWxNearest, s * 0.5 + 0.5, level(0.0)).r);
		if (wr <= ws + 0.05 + 0.004 * ws) {
			tPrev = t;
			continue;
		}
		float lo = tPrev, hi = t;
		for (int j = 0; j < 3; j++) {
			float m = 0.5 * (lo + hi);
			float2 sm = s0 + d * m;
			float wm = 1.0 / mix(iw0, iw1, m);
			if (wm > float(histDepth.sample(shadeWxNearest, sm * 0.5 + 0.5, level(0.0)).r)) hi = m; else lo = m;
		}
		float2 sh = s0 + d * hi;
		float2 uv = sh * 0.5 + 0.5;
		float wh = float(histDepth.sample(shadeWxNearest, uv, level(0.0)).r);
		float wrh = 1.0 / mix(iw0, iw1, hi);
		float wp = 1.0 / mix(iw0, iw1, tPrev);
		float thick = 0.35 + 0.025 * wh + abs(wrh - wp) * 3.0;
		if (wh > 30000.0 || wrh - wh > thick) {
			if (back.w < 0.0 && wh < 30000.0 && wh > c0.w) {
				float2 eb = smoothstep(0.0, 0.06, min(uv, 1.0 - uv));
				float bf = eb.x * eb.y;
				if (!screenEnd) bf *= 1.0 - smoothstep(0.75, 1.0, hi / tEnd);
				back = float4(uv, bf, 11.0);
				backPoint = p + r * (len * hi * iw1 / mix(iw0, iw1, hi));
			}
			tPrev = t;
			continue;
		}
		float2 edge = smoothstep(0.0, 0.06, min(uv, 1.0 - uv));
		float fade = edge.x * edge.y * (1.0 - smoothstep(0.6, 1.0, (wrh - wh) / thick));
		if (!screenEnd) fade *= 1.0 - smoothstep(0.75, 1.0, hi / tEnd);
		hitPoint = p + r * (len * hi * iw1 / mix(iw0, iw1, hi));
		return float4(uv, fade, 1.0);
	}
	if (back.w > 0.0) hitPoint = backPoint;
	return back;
}

static inline float shadeWxQuadMax(float v) { v = max(v, quad_shuffle_xor(v, 1)); return max(v, quad_shuffle_xor(v, 2)); }
static inline float shadeWxQuadMin(float v) { v = min(v, quad_shuffle_xor(v, 1)); return min(v, quad_shuffle_xor(v, 2)); }
static inline float3 shadeWxQuadSum(float3 v) { v += quad_shuffle_xor(v, 1); return v + quad_shuffle_xor(v, 2); }

static float4 shadeWxHistSsr(float3 p, float3 r, float2 pixel, float maxDist, float prevExposure, constant ShadeFrame &f, constant ShadeLook &L,
                             texture2d<half> hist, texture2d<half> histDepth, float streak = 0.0) {
	float3 hp = float3(0.0);
#ifdef SHADE_PUDDLE_QUAD
	float3 pq = shadeWxQuadSum(p) * 0.25;
	float3 rq = normalize(shadeWxQuadSum(r));
	uint2 q = uint2(pixel);
	int chunk = int(q.x & 1u) + 2 * int(q.y & 1u);
	float jq = shadeNoise(floor(pixel * 0.5), f.flags2.x);
	float4 h = SHADE_WX_B_STEPS_ON(f) ? shadeWxHistMarch<2>(pq, rq, maxDist, chunk, 4, jq, L, histDepth, hp)
	                                  : shadeWxHistMarch<SHADE_PUDDLE_N>(pq, rq, maxDist, chunk, 4, jq, L, histDepth, hp);
	float key = h.w > 10.0 ? float(chunk) + 4.0 : h.w > 0.0 ? float(chunk) : 8.0;
	float best = shadeWxQuadMin(key);
	if (best > 7.5) return float4(0.0);
	bool mine = key == best;
	float3 a = shadeWxQuadSum(mine ? float3(h.xyz) : float3(0.0));
	hp = shadeWxQuadSum(mine ? hp : float3(0.0));
	h = float4(a, 1.0);
#else
	float jp = shadeNoise(pixel, f.flags2.x);
	float4 h = SHADE_WX_B_STEPS_ON(f) ? shadeWxHistMarch<8>(p, r, maxDist, 0, 1, jp, L, histDepth, hp)
	                                  : shadeWxHistMarch<SHADE_PUDDLE_N>(p, r, maxDist, 0, 1, jp, L, histDepth, hp);
	if (h.w < 0.0) return float4(0.0);
#endif
	float3 c = float3(hist.sample(shadeWxLinear, h.xy, level(0.0)).rgb) / prevExposure;
	if (streak > 0.0) {
		c = c * 0.5 + (float3(hist.sample(shadeWxLinear, h.xy + float2(0.0, streak), level(0.0)).rgb)
			+ float3(hist.sample(shadeWxLinear, h.xy - float2(0.0, streak), level(0.0)).rgb)) * (0.25 / prevExposure);
	}
	if (shadeFeature(L, SHADE_FEATURE_HAZE) && f.fogColor.a < 0.5) {
#ifdef SHADE_PUDDLE_QUAD
		float3 o = pq;
#else
		float3 o = p;
#endif
		float3 T;
		float3 S = shadeHazeInscatterFrom(hp - o, f.cameraBlock.y + f.cameraFract.y + o.y, f, L, 1.0, T);
		c = c * T + S;
	}
	return float4(c, h.z);
}

static float3 shadeWxSkyRefl(float3 r, float prevExposure, bool histOk, constant ShadeFrame &f, constant ShadeLook &L, texture2d<half> lut, sampler lutS,
                             texture2d<half> hist, texture2d<half> histDepth) {
	float3 sky = shadeSkyLookup(float3(r.x, max(r.y, 0.0), r.z), lut, lutS, f);
	if (histOk) {
		float4 c = L.prevViewProjNoJitter * float4(r, 0.0);
		if (c.w > 1e-4) {
			float2 uv = c.xy / c.w * 0.5 + 0.5;
			float2 edge = saturate(min(uv, 1.0 - uv) * 12.0);
			float wgt = edge.x * edge.y;
			if (wgt > 0.0 && float(histDepth.sample(shadeWxNearest, uv, level(0.0)).r) > 1000.0) {
				sky = mix(sky, float3(hist.sample(shadeWxLinear, uv, level(0.0)).rgb) / prevExposure, wgt);
			}
		}
	}
	return sky;
}

struct ShadeWetLight {
	float3 p;
	half3 n;
	half sky;
	half shadow;
	float2 pixel;
	float prevExposure;
	bool histOk;
};

static float3 shadeWetShade(float3 radiance, half3 albedo, ShadeWet w, ShadeWetLight in, constant ShadeFrame &f, constant ShadeLook &L, texture2d<half> lut,
                            sampler lutS, texture2d<half> hist, texture2d<half> histDepth) {
	half wet = max(w.omega, w.puddle);
#if defined(SHADE_PUDDLE_SSR) && defined(SHADE_PUDDLE_QUAD)
	bool quadPuddle = shadeWxQuadMax(float(w.puddle)) > 0.02;
	bool quadFilm = SHADE_WET_ALL_ON(L) && L.water.w > 0.0 && shadeWxQuadMax(in.n.y > 0.5h ? float(w.film * w.omega) : 0.0) > 0.3;
	if (wet <= 0.004h && !quadPuddle && !quadFilm) return radiance;
#else
	if (wet <= 0.004h) return radiance;
#endif
	float3 pe = in.p - shadeEye(f.invViewProj);
	float dist = length(pe);
	float3 v = pe / max(dist, 1e-4);
	float3 nf = float3(in.n);
	float3 n = nf;
	float P = float(w.puddle);
	if (P > 0.0) n = normalize(float3(-w.slope.x * P, 1.0, -w.slope.y * P));
	float veg = saturate((float(albedo.g) - max(float(albedo.r), float(albedo.b))) * 8.0);
	float rough = mix(min(mix(0.8, 0.38, float(w.omega)) + 0.25 * veg, 0.95), 0.03, P);
	bool walls = shadeWxWalls(f);
	if (walls && nf.y < 0.5) rough = max(rough, 0.75);
	float film = 0.0;
	if (w.film > 0.0h) {
		float g = max(float(albedo.g), 1e-3);
		float vegR = saturate(((g - max(float(albedo.r), float(albedo.b))) / g - 0.1) * 2.5);
		float soak = saturate(float(w.omega) * 1.15);
		float tx = float(w.tex) * (1.0 - float(w.low));
		float rS = clamp(L.water.y + L.water.z * vegR - 0.25 * float(w.var) + 0.18 * tx, 0.06, 0.6);
		float low2 = float(w.low) * float(w.low);
		rough = mix(mix(mix(0.8, rS, soak), 0.06, low2), 0.03, P);
		film = max(soak * mix(1.0, L.waterAbs.y, vegR) * (1.0 - 0.35 * saturate(tx)), low2);
	}
	float cosV = max(dot(-v, n), 0.02);
	float F = shadeWxFresnel(cosV, rough);
	float3 r = reflect(v, n);
	if (dot(r, nf) < 0.02) r = normalize(r + nf * (0.02 - dot(r, nf)));
	float open = float(shadeSkyCurve(in.sky));
	float blur = saturate(rough * 1.6);
	if (P <= 0.0 && SHADE_WX_BUDGET(f, SHADE_DEV_WX_FILM_SKY)) blur = max(blur, smoothstep(0.6, 0.8, L.wet.w));
	float3 hitC = float3(0.0);
	float wHit = 0.0;
#ifdef SHADE_PUDDLE_SSR
	float2 gate = SHADE_WX_BUDGET(f, SHADE_DEV_WX_SSR_GATE2) ? float2(0.05, 0.09) : float2(0.035, 0.07);
	if (SHADE_WX_B_GATE_ON(f)) gate = float2(0.06, 0.10);
	float ssrW = shadeDev(f, SHADE_DEV_WX_NO_SSR_GATE) ? (P > 0.02 ? 1.0 : 0.0) : smoothstep(gate.x, gate.y, F * P);
	float filmW = 0.0;
	if (film > 0.0 && L.water.w > 0.0 && nf.y > 0.5) filmW = smoothstep(L.water.w, L.water.w * 1.8, F * film * saturate((0.42 - rough) * 4.0)) * (1.0 - P);
	ssrW = max(ssrW, filmW);
#ifdef SHADE_PUDDLE_QUAD
	if ((quadPuddle || quadFilm) && shadeWxQuadMax(ssrW) > 0.0 && in.histOk && in.prevExposure > 0.0 && !shadeDev(f, SHADE_DEV_WX_NO_SSR)) {
#else
	if (ssrW > 0.0 && in.histOk && in.prevExposure > 0.0 && !shadeDev(f, SHADE_DEV_WX_NO_SSR)) {
#endif
		float streak = filmW > 0.0 ? 0.014 * saturate(rough * 3.3) * (1.0 - P) : 0.0;
		float4 hit = shadeWxHistSsr(in.p, r, in.pixel, SHADE_PUDDLE_DIST, in.prevExposure, f, L, hist, histDepth, streak);
		hitC = hit.rgb;
		wHit = hit.w * ssrW;
	}
#endif
	float3 refl = float3(0.0);
	if (wHit < 1.0) {
		refl = blur < 1.0 ? shadeWxSkyRefl(r, in.prevExposure, in.histOk && P > 0.0, f, L, lut, lutS, hist, histDepth) : float3(0.0);
		refl = mix(refl, float3(f.ambientSky.rgb) * 1.1, blur) * open;
		if (walls) refl = mix(float3(f.ambientGround.rgb) * open, refl, smoothstep(-0.15, 0.15, r.y));
	}
	refl = mix(refl, hitC, wHit);
	if (wet <= 0.004h) return radiance;
	float a = max(rough * rough, 0.0025);
	float3 sun = float3(0.0);
	float sunW = SHADE_WX_BUDGET(f, SHADE_DEV_WX_SUN_GATE) ? 1.0 - smoothstep(0.5, 0.8, L.wet.w) : 1.0;
	if (sunW > 0.0) sun = float3(f.sunColor.rgb) * (min(shadeWxGgx(n, v, f.lightDirWorld.xyz, a), 60.0) * float(in.shadow)
		* float(smoothstep(0.3h, 0.8h, in.sky)) * sunW);
	float k = mix(w.film > 0.0h ? film : float(w.omega) * 0.65 * (1.0 - 0.5 * veg), 1.0, P);
	return radiance * (1.0 - F * k) + (refl * F + sun * F) * k;
}

static inline float3 shadeWxFlash(half3 albedo, half3 n, half sky, constant ShadeLook &L) {
	if (L.flash.w <= 0.0) return float3(0.0);
	float ndl = saturate(dot(float3(n), L.flash.xyz)) * 0.65 + 0.35;
	half g = shadeSkyCurve(sky);
	return float3(albedo) * float3(0.78, 0.86, 1.0) * (L.flash.w * ndl * float(g * g));
}

static half3 shadeShadowTint(float3 p, half3 n, constant ShadeFrame &f, constant ShadeLook &L, texture2d<half> tint) {
	float rw = SHADE_WX_BUDGET(f, SHADE_DEV_WX_RAIN_TINT) ? 1.0 - smoothstep(0.5, 0.8, L.wet.w) : 1.0;
	if (rw <= 0.0) return half3(1.0h);
	float4 c = f.shadowViewProj[0] * float4(p + float3(n) * (0.75 * f.cascadeSplits.z), 1.0);
	if (any(c.xy < L.tintRect.xy) || any(c.xy > L.tintRect.zw)) return half3(1.0h);
	float m = max(abs(c.x), abs(c.y));
	if (m >= 1.0 || c.z <= 0.0 || c.z >= 1.0) return half3(1.0h);
	float2 tuv = L.ssr.w > 1.5 ? float2(0.5 * (c.x - L.tintRect.x), 0.5 * (L.tintRect.w - c.y)) : shadeShadowUV(c.xyz);
	half4 t = tint.sample(shadeWxLinear, tuv, level(0.0));
	float zBlocks = 1.0 / max(length(float3(f.shadowViewProj[0][0][2], f.shadowViewProj[0][1][2], f.shadowViewProj[0][2][2])), 1e-6);
	float gate = saturate(((float(t.a) - c.z) * zBlocks - 0.25) * 4.0) * (1.0 - smoothstep(0.85, 1.0, m));
	half3 T = t.rgb * min(rsqrt(max(dot(t.rgb, half3(0.2126h, 0.7152h, 0.0722h)), 0.05h)), 2.2h);
	return mix(half3(1.0h), T, half(gate * rw));
}

static inline float3 shadeShadowTintDelta(half3 albedo, half3 n, half sky, half shadow, half3 tint, bool thin, bool foliage, constant ShadeFrame &f) {
	if (all(tint == half3(1.0h))) return float3(0.0);
	half nl = half(dot(float3(n), f.lightDirWorld.xyz));
	half ndl = thin ? half(0.3) + half(0.5) * abs(nl)
		: foliage ? half(0.25) + half(0.75) * saturate(nl) + half(0.5) * saturate(-nl)
		: (nl > half(0.0) ? (nl + half(0.08)) / half(1.08) : half(0.0));
	half gate = smoothstep(half(0.3), half(0.8), sky);
	if (SHADE_CAMERA_UNDERWATER(f)) gate *= 0.5h;
	return float3(albedo * half3(f.sunColor.rgb) * (ndl * shadow * gate) * (tint - 1.0h));
}

#endif
